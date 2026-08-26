"""Maintained GDAPI compatibility client for PastureStack integration tests.

This module keeps the small runtime surface used by the preserved Cattle API
test suites.  It intentionally excludes the retired command-line interface.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
from pathlib import Path
from urllib.parse import urljoin, urlsplit

import requests


DEFAULT_HEADERS = {
    "Accept": "application/json",
    "Content-Type": "application/json",
}
DEFAULT_REQUEST_TIMEOUT = 60
DEFAULT_CACHE_TIME = 24 * 60 * 60

GET_METHOD = "GET"
POST_METHOD = "POST"
PUT_METHOD = "PUT"
DELETE_METHOD = "DELETE"


class RestObject:
    """Attribute-oriented representation of a GDAPI object or collection."""

    def _is_list(self):
        return isinstance(self.__dict__.get("data"), list)

    def __iter__(self):
        if not self._is_list():
            raise TypeError("GDAPI object is not a collection")
        return iter(self.data)

    def __len__(self):
        if not self._is_list():
            raise TypeError("GDAPI object is not a collection")
        return len(self.data)

    def __getitem__(self, key):
        if not self._is_list():
            raise TypeError("GDAPI object is not a collection")
        return self.data[key]

    def __repr__(self):
        public = {
            key: value
            for key, value in self.__dict__.items()
            if key not in {"links", "actions", "id", "type"}
            and not callable(value)
        }
        return repr(public)


class Schema:
    def __init__(self, text, objects):
        self.text = text
        self.types = {}
        for item in objects:
            if getattr(item, "type", None) != "schema":
                continue
            self.types[item.id] = item
            item.creatable = POST_METHOD in getattr(
                item, "collectionMethods", []
            )
            item.updatable = PUT_METHOD in getattr(item, "resourceMethods", [])
            item.deletable = DELETE_METHOD in getattr(
                item, "resourceMethods", []
            )
            item.listable = GET_METHOD in getattr(
                item, "collectionMethods", []
            )
            if not hasattr(item, "collectionFilters"):
                item.collectionFilters = RestObject()

    def __str__(self):
        return self.text


class ApiError(Exception):
    def __init__(self, error):
        self.error = error
        code = getattr(error, "code", "API error")
        message = getattr(error, "message", repr(error))
        super().__init__(f"{code}: {message}")


class ClientApiError(Exception):
    pass


class Client:
    def __init__(
        self,
        access_key=None,
        secret_key=None,
        url=None,
        cache=False,
        cache_time=DEFAULT_CACHE_TIME,
        strict=False,
        headers=None,
        request_timeout=DEFAULT_REQUEST_TIMEOUT,
        session=None,
        **_ignored,
    ):
        self._access_key = access_key
        self._secret_key = secret_key
        self._auth = (
            (access_key, secret_key)
            if access_key is not None or secret_key is not None
            else None
        )
        self._url = self._validate_url(url)
        self._cache = bool(cache)
        self._cache_time = int(cache_time or DEFAULT_CACHE_TIME)
        self._strict = bool(strict)
        self._headers = dict(headers or DEFAULT_HEADERS)
        self._request_timeout = float(request_timeout)
        if self._request_timeout <= 0:
            raise ValueError("request_timeout must be positive")
        self._session = session or requests.Session()
        self.schema = None
        if self._url is not None:
            self._load_schemas()

    @staticmethod
    def _validate_url(url):
        if url is None:
            return None
        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("GDAPI URL must be an absolute HTTP(S) URL")
        if parsed.username is not None or parsed.password is not None:
            raise ValueError(
                "GDAPI credentials must not be embedded in the URL"
            )
        return url

    @staticmethod
    def _origin(url):
        parsed = urlsplit(url)
        default_port = 443 if parsed.scheme.lower() == "https" else 80
        return (
            parsed.scheme.lower(),
            parsed.hostname.lower(),
            parsed.port or default_port,
        )

    def _same_origin_url(self, url):
        candidate = self._validate_url(urljoin(self._url, url))
        if self._origin(candidate) != self._origin(self._url):
            raise ClientApiError(
                "GDAPI links must use the configured API origin"
            )
        return candidate

    def valid(self):
        return self._url is not None and self.schema is not None

    def object_hook(self, value):
        result = RestObject()
        for key, item in value.items():
            setattr(result, key, item)

        pagination = getattr(result, "pagination", None)
        for name in ("next", "prev"):
            url = getattr(pagination, name, None)
            if url is not None:
                setattr(result, name, lambda url=url: self._get(url))

        if isinstance(getattr(result, "type", None), str):
            links = vars(getattr(result, "links", RestObject()))
            for name, url in links.items():
                def callback(_url=url, **kw):
                    return self._get(_url, data=kw)

                target = name + "_link" if hasattr(result, name) else name
                setattr(result, target, callback)
            for name in vars(getattr(result, "actions", RestObject())):
                callback = (
                    lambda *args, _name=name, _result=result, **kw:
                    self.action(_result, _name, *args, **kw)
                )
                target = name + "_action" if hasattr(result, name) else name
                setattr(result, target, callback)
        return result

    def _request(self, method, url, *, params=None, data=None):
        url = self._same_origin_url(url)
        response = self._session.request(
            method,
            url,
            auth=self._auth,
            params=params,
            data=data,
            headers=self._headers,
            timeout=self._request_timeout,
            allow_redirects=False,
        )
        if not 200 <= response.status_code < 300:
            try:
                error = self._unmarshall(response.text)
            except (TypeError, ValueError):
                error = RestObject()
                error.message = "non-JSON API error"
            if not isinstance(error, RestObject):
                error = RestObject()
                error.message = "empty or non-object API error"
            if not hasattr(error, "status"):
                error.status = response.status_code
            raise ApiError(error)
        return response

    def _get_raw(self, url, data=None):
        return self._request(GET_METHOD, url, params=data).text

    def _get_response(self, url, data=None):
        return self._request(GET_METHOD, url, params=data)

    def _get(self, url, data=None):
        return self._unmarshall(self._get_raw(url, data=data))

    def _post(self, url, data=None):
        response = self._request(
            POST_METHOD, url, data=self._marshall(data)
        )
        return self._unmarshall(response.text)

    def _put(self, url, data=None):
        response = self._request(PUT_METHOD, url, data=self._marshall(data))
        return self._unmarshall(response.text)

    def _delete(self, url):
        response = self._request(DELETE_METHOD, url)
        return self._unmarshall(response.text)

    def _unmarshall(self, text):
        if text in (None, ""):
            return text
        return json.loads(text, object_hook=self.object_hook)

    def _marshall(self, obj, indent=None, sort_keys=False):
        if obj is None:
            return None
        return json.dumps(
            self._to_dict(obj), indent=indent, sort_keys=sort_keys
        )

    def _load_schemas(self, force=False):
        if self.schema is not None and not force:
            return
        schema_text = self._get_cached_schema()
        if force or not schema_text:
            response = self._get_response(self._url)
            schema_url = response.headers.get("X-API-Schemas")
            schema_text = (
                self._get_raw(schema_url)
                if schema_url and schema_url != self._url
                else response.text
            )
            self._cache_schema(schema_text)
        schema = Schema(schema_text, self._unmarshall(schema_text))
        if schema.types:
            self._bind_methods(schema)
            self.schema = schema

    def reload_schema(self):
        self._load_schemas(force=True)

    def by_id(self, resource_type, resource_id, **kw):
        url = self.schema.types[resource_type].links.collection.rstrip("/")
        try:
            return self._get(url + "/" + str(resource_id), self._to_dict(**kw))
        except ApiError as error:
            if int(getattr(error.error, "status", 0)) == 404:
                return None
            raise

    def update_by_id(self, resource_type, resource_id, *args, **kw):
        url = self.schema.types[resource_type].links.collection.rstrip("/")
        return self._put_and_retry(url + "/" + str(resource_id), *args, **kw)

    def update(self, obj, *args, **kw):
        return self._put_and_retry(obj.links.self, *args, **kw)

    def _put_and_retry(self, url, *args, **kw):
        return self._retry_conflict(self._put, url, *args, **kw)

    def _post_and_retry(self, url, *args, **kw):
        return self._retry_conflict(self._post, url, *args, **kw)

    def _retry_conflict(self, operation, url, *args, **kw):
        payload = dict(kw)
        retries = int(payload.pop("retries", 3))
        if retries < 1 or retries > 10:
            raise ValueError("retries must be between 1 and 10")
        last_error = None
        for attempt in range(retries):
            try:
                return operation(url, data=self._to_dict(*args, **payload))
            except ApiError as error:
                if int(getattr(error.error, "status", 0)) != 409:
                    raise
                last_error = error
                if attempt + 1 < retries:
                    time.sleep(0.1)
        raise last_error

    def _validate_list(self, resource_type, **kw):
        if not self._strict:
            return
        filters = self.schema.types[resource_type].collectionFilters
        for key in kw:
            if hasattr(filters, key):
                continue
            allowed = any(
                key == f"{name}_{modifier}"
                for name, value in vars(filters).items()
                for modifier in getattr(value, "modifiers", [])
            )
            if not allowed:
                raise ClientApiError(key + " is not a searchable field")

    def list(self, resource_type, **kw):
        if resource_type not in self.schema.types:
            raise ClientApiError(resource_type + " is not a valid type")
        self._validate_list(resource_type, **kw)
        url = self.schema.types[resource_type].links.collection
        return self._get(url, data=self._to_dict(**kw))

    def reload(self, obj):
        return self.by_id(obj.type, obj.id)

    def create(self, resource_type, *args, **kw):
        url = self.schema.types[resource_type].links.collection
        return self._post(url, data=self._to_dict(*args, **kw))

    def delete(self, *objects):
        for obj in objects:
            if isinstance(obj, RestObject):
                return self._delete(obj.links.self)
        return None

    def action(self, obj, action_name, *args, **kw):
        return self._post_and_retry(
            getattr(obj.actions, action_name), *args, **kw
        )

    def _is_list(self, obj):
        return isinstance(obj, list) or (
            isinstance(obj, RestObject)
            and obj.__dict__.get("type") == "collection"
        )

    def _to_value(self, value):
        if isinstance(value, dict):
            return {key: self._to_value(item) for key, item in value.items()}
        if isinstance(value, (list, tuple)):
            return [self._to_value(item) for item in value]
        if isinstance(value, RestObject):
            return {
                key: self._to_value(item)
                for key, item in vars(value).items()
                if not key.startswith("_") and not callable(item)
            }
        return value

    def _to_dict(self, *args, **kw):
        if not kw and len(args) == 1 and self._is_list(args[0]):
            return [self._to_dict(item) for item in args[0]]
        result = {}
        for value in args:
            converted = self._to_value(value)
            if isinstance(converted, dict):
                result.update(converted)
        result.update(
            {key: self._to_value(value) for key, value in kw.items()}
        )
        return result

    @staticmethod
    def _type_name_variants(name):
        variants = [name]
        python_name = re.sub(r"([a-z])([A-Z])", r"\1_\2", name).lower()
        if python_name != name:
            variants.append(python_name)
        return variants

    def _bind_methods(self, schema):
        bindings = (
            ("list", "collectionMethods", GET_METHOD, self.list),
            ("by_id", "collectionMethods", GET_METHOD, self.by_id),
            ("update_by_id", "resourceMethods", PUT_METHOD, self.update_by_id),
            ("create", "collectionMethods", POST_METHOD, self.create),
        )
        for resource_type, item in schema.types.items():
            for variant in self._type_name_variants(resource_type):
                for name, methods, verb, operation in bindings:
                    if verb not in getattr(item, methods, []):
                        continue
                    callback = (
                        lambda *args, _type=resource_type, _op=operation, **kw:
                        _op(_type, *args, **kw)
                    )
                    setattr(self, f"{name}_{variant}", callback)

    def _get_cached_schema_file_name(self):
        if not self._cache:
            return None
        key = (self._url + "\0" + (self._access_key or "")).encode("utf-8")
        digest = hashlib.sha256(key).hexdigest()
        directory = Path(os.path.expanduser("~/.gdapi"))
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        return directory / ("schema-" + digest + ".json")

    def _cache_schema(self, text):
        path = self._get_cached_schema_file_name()
        if path is None:
            return
        temporary = path.with_suffix(".tmp")
        temporary.write_text(text, encoding="utf-8")
        os.replace(temporary, path)

    def _get_cached_schema(self):
        path = self._get_cached_schema_file_name()
        if path is None or not path.exists():
            return None
        if time.time() - path.stat().st_mtime >= self._cache_time:
            return None
        return path.read_text(encoding="utf-8")


def from_env(prefix="GDAPI_", factory=Client, **kw):
    if not prefix.endswith("_"):
        prefix += "_"
    prefix = prefix.upper()
    values = {
        "access_key": None,
        "secret_key": None,
        "url": None,
        "cache": None,
        "cache_time": None,
        "strict": None,
        "request_timeout": None,
    }
    values.update(kw)
    result = {}
    for key, value in values.items():
        selected = (
            value
            if value is not None
            else os.environ.get(prefix + key.upper())
        )
        if selected is not None:
            result[key] = selected
    for key in ("cache", "strict"):
        if key in result and isinstance(result[key], str):
            result[key] = result[key].lower() == "true"
    for key in ("cache_time", "request_timeout"):
        if key in result:
            result[key] = float(result[key])
    return factory(**result)
