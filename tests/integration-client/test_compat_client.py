import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import cattle
import gdapi


class ApiHandler(BaseHTTPRequestHandler):
    action_attempts = 0
    action_payloads = []
    widget_name = "widget"

    def log_message(self, _format, *_args):
        return

    @property
    def base_url(self):
        host, port = self.server.server_address
        return f"http://{host}:{port}"

    def send_json(self, status, value, headers=None):
        body = json.dumps(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for key, item in (headers or {}).items():
            self.send_header(key, item)
        self.end_headers()
        self.wfile.write(body)

    def widget(self, name=None):
        name = type(self).widget_name if name is None else name
        return {
            "id": "1",
            "type": "widget",
            "name": name,
            "transitioning": "no",
            "transitioningMessage": "",
            "links": {"self": self.base_url + "/widgets/1"},
            "actions": {
                "activate": self.base_url + "/widgets/1?action=activate"
            },
        }

    def do_GET(self):
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", self.base_url + "/widgets/1")
            self.end_headers()
            return
        if self.path == "/v1":
            self.send_json(
                200,
                {"type": "collection", "data": []},
                {"X-API-Schemas": self.base_url + "/schemas"},
            )
            return
        if self.path == "/schemas":
            self.send_json(
                200,
                {
                    "type": "collection",
                    "data": [
                        {
                            "id": "widget",
                            "type": "schema",
                            "collectionMethods": ["GET", "POST"],
                            "resourceMethods": ["GET", "PUT", "DELETE"],
                            "links": {
                                "collection": self.base_url + "/widgets"
                            },
                            "collectionFilters": {
                                "name": {"modifiers": ["eq", "like"]}
                            },
                        }
                    ],
                },
            )
            return
        if self.path.startswith("/widgets/missing"):
            self.send_json(
                404, {"status": 404, "code": "NotFound", "message": "missing"}
            )
            return
        if self.path.startswith("/widgets/1"):
            self.send_json(200, self.widget())
            return
        if self.path.startswith("/widgets"):
            self.send_json(
                200, {"type": "collection", "data": [self.widget()]}
            )
            return
        self.send_json(404, {"status": 404, "message": "missing"})

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def do_POST(self):
        payload = self.read_json()
        if self.path == "/widgets":
            type(self).widget_name = payload.get("name", "widget")
            self.send_json(201, self.widget())
            return
        if self.path == "/widgets/1?action=activate":
            type(self).action_attempts += 1
            type(self).action_payloads.append(payload)
            if type(self).action_attempts == 1:
                self.send_json(
                    409,
                    {"status": 409, "code": "Conflict", "message": "retry"},
                )
            else:
                type(self).widget_name = "active"
                self.send_json(200, self.widget())
            return
        self.send_json(404, {"status": 404, "message": "missing"})

    def do_PUT(self):
        payload = self.read_json()
        type(self).widget_name = payload.get("name", "updated")
        self.send_json(200, self.widget())

    def do_DELETE(self):
        self.send_json(200, self.widget("removed"))


class CompatibilityClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), ApiHandler)
        cls.thread = threading.Thread(
            target=cls.server.serve_forever, daemon=True
        )
        cls.thread.start()
        host, port = cls.server.server_address
        cls.url = f"http://{host}:{port}/v1"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        ApiHandler.action_attempts = 0
        ApiHandler.action_payloads = []
        ApiHandler.widget_name = "widget"

    def test_dynamic_crud_and_conflict_retry(self):
        client = gdapi.Client(url=self.url, request_timeout=2)
        self.assertTrue(client.valid())

        created = client.create_widget(name="created")
        self.assertEqual("created", created.name)
        self.assertEqual("created", client.by_id_widget("1").name)
        self.assertIsNone(client.by_id_widget("missing"))
        self.assertEqual(1, len(client.list_widget(name_like="cre%")))
        self.assertEqual(
            "updated", client.update_by_id_widget("1", name="updated").name
        )

        activated = created.activate(retries=2, requested=True)
        self.assertEqual("active", activated.name)
        self.assertEqual(2, ApiHandler.action_attempts)
        self.assertEqual(
            [{"requested": True}, {"requested": True}],
            ApiHandler.action_payloads,
        )
        self.assertEqual("removed", client.delete(created).name)

    def test_cattle_environment_and_transition_wait(self):
        environment = {
            "CATTLE_URL": self.url,
            "CATTLE_REQUEST_TIMEOUT": "2",
            "CATTLE_CACHE": "false",
        }
        with patch.dict(os.environ, environment, clear=False):
            client = cattle.from_env()
        obj = client.create_widget(name="ready")
        self.assertEqual("ready", client.wait_success(obj).name)

    def test_invalid_or_credential_bearing_urls_are_rejected(self):
        urls = (
            "ftp://example.test/api",
            "https://user:pass@example.test/api",
        )
        for url in urls:
            with self.subTest(url=url), self.assertRaises(ValueError):
                gdapi.Client(url=url)

    def test_server_links_are_same_origin_and_redirects_fail_closed(self):
        client = gdapi.Client(url=self.url, request_timeout=2)
        self.assertEqual("widget", client._get("/widgets/1").name)
        with self.assertRaisesRegex(
            gdapi.ClientApiError, "configured API origin"
        ):
            client._get("http://127.0.0.1:9/metadata")
        with self.assertRaises(gdapi.ApiError) as error:
            client._get("/redirect")
        self.assertEqual(302, error.exception.error.status)


if __name__ == "__main__":
    unittest.main()
