"""Cattle-specific helpers built on the maintained local GDAPI client."""

import time

import gdapi
from gdapi import ApiError, ClientApiError, RestObject


DEFAULT_TIMEOUT = 45


class Client(gdapi.Client):
    def wait_success(self, obj, timeout=-1):
        obj = self.wait_transitioning(obj, timeout)
        if obj.transitioning != "no":
            raise gdapi.ClientApiError(obj.transitioningMessage)
        return obj

    def wait_transitioning(self, obj, timeout=-1, sleep=0.01):
        timeout = DEFAULT_TIMEOUT if timeout == -1 else timeout
        started = time.monotonic()
        obj = self.reload(obj)
        while obj.transitioning == "yes":
            time.sleep(sleep)
            sleep = min(sleep * 2, 2)
            obj = self.reload(obj)
            if time.monotonic() - started > timeout:
                raise TimeoutError(
                    f"Timed out waiting for [{obj.type}:{obj.id}] "
                    f"after {timeout} seconds"
                )
        return obj


def from_env(prefix="CATTLE_", **kw):
    return gdapi.from_env(prefix=prefix, factory=Client, **kw)


__all__ = [
    "ApiError",
    "Client",
    "ClientApiError",
    "RestObject",
    "from_env",
]
