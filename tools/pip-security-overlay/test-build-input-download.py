"""Stdlib-only download controls. No network, wheel build, install or real sleep."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import ssl
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("pip_input_builder", Path(__file__).with_name("build-pip-overlay.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
URL = "https://files.pythonhosted.org/packages/fixed/source.tar.gz?private=never-print-this"
PAYLOAD = b"exact official pinned input fixture\n"


class InputDownloadTest(unittest.TestCase):
    def item(self):
        return {"url": URL, "sha256": hashlib.sha256(PAYLOAD).hexdigest()}

    def assert_safe_log(self, log):
        rows = [json.loads(line) for line in log.splitlines()]
        self.assertTrue(rows)
        self.assertTrue(all(row["input"] == "source.tar.gz" for row in rows))
        self.assertNotIn(URL, log)
        self.assertNotIn("never-print-this", log)

    def test_timeout_and_http5xx_retry_exact_url_then_promote_only_verified_bytes(self):
        failures = [TimeoutError("never-print-this"), URLError(TimeoutError("never-print-this")),
                    HTTPError(URL, 503, "never-print-this", {}, None)]
        for error in failures:
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as directory:
                cached = Path(directory) / "source.tar.gz"
                def succeed(*args, **kwargs):
                    self.assertEqual((args, kwargs), ((URL,), {"timeout": 90}))
                    self.assertFalse(cached.exists())
                    self.assertEqual(len(list(cached.parent.glob("*.part"))), 1)
                    return io.BytesIO(PAYLOAD)
                opener = Mock()
                calls = iter([error, None])
                def open_input(*args, **kwargs):
                    result = next(calls)
                    if result is not None:
                        raise result
                    return succeed(*args, **kwargs)
                opener.open.side_effect = open_input
                log = io.StringIO()
                with patch.object(builder.time, "sleep") as sleep, contextlib.redirect_stderr(log):
                    builder.download_input(self.item(), cached, opener)
                self.assertEqual(opener.open.call_count, 2)
                sleep.assert_called_once_with(2)
                self.assertEqual(cached.read_bytes(), PAYLOAD)
                self.assertEqual(list(cached.parent.glob("*.part")), [])
                self.assert_safe_log(log.getvalue())

    def test_transient_failures_stop_after_three_attempts_without_a_cache_or_half_file(self):
        for error in (TimeoutError("never-print-this"), HTTPError(URL, 502, "never-print-this", {}, None)):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as directory:
                cached = Path(directory) / "source.tar.gz"
                opener = Mock()
                opener.open.side_effect = error
                log = io.StringIO()
                with patch.object(builder.time, "sleep") as sleep, contextlib.redirect_stderr(log):
                    with self.assertRaises(RuntimeError) as caught:
                        builder.download_input(self.item(), cached, opener)
                self.assertEqual(opener.open.call_count, 3)
                self.assertEqual(sleep.call_count, 2)
                self.assertFalse(cached.exists())
                self.assertEqual(list(cached.parent.iterdir()), [])
                self.assert_safe_log(log.getvalue() + "")
                self.assertNotIn("never-print-this", str(caught.exception))

    def test_404_tls_and_hash_mismatch_fail_once_and_never_promote(self):
        errors = [HTTPError(URL, 404, "never-print-this", {}, None),
                  URLError(ssl.SSLCertVerificationError("never-print-this")),
                  ssl.SSLError("never-print-this"), URLError("permanent failure")]
        for error in errors + [None]:
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as directory:
                cached = Path(directory) / "source.tar.gz"
                opener = Mock()
                if error is None:
                    opener.open.return_value = io.BytesIO(b'{"metadata":"not the pinned archive"}')
                else:
                    opener.open.side_effect = error
                log = io.StringIO()
                with patch.object(builder.time, "sleep") as sleep, contextlib.redirect_stderr(log):
                    with self.assertRaises((RuntimeError, AssertionError)):
                        builder.download_input(self.item(), cached, opener)
                self.assertEqual(opener.open.call_count, 1)
                sleep.assert_not_called()
                self.assertFalse(cached.exists())
                self.assertEqual(list(cached.parent.iterdir()), [])
                self.assert_safe_log(log.getvalue())

    def test_timeout_after_partial_body_is_discarded_before_retry(self):
        class Partial(io.BytesIO):
            def read(self, size=-1):
                if self.tell():
                    raise TimeoutError("never-print-this")
                return super().read(3)
        with tempfile.TemporaryDirectory() as directory:
            cached = Path(directory) / "source.tar.gz"
            opener = Mock()
            opener.open.side_effect = [Partial(b"partial bytes"), io.BytesIO(PAYLOAD)]
            log = io.StringIO()
            with patch.object(builder.time, "sleep"), contextlib.redirect_stderr(log):
                builder.download_input(self.item(), cached, opener)
            self.assertEqual(cached.read_bytes(), PAYLOAD)
            self.assertEqual(list(cached.parent.glob("*.part")), [])
            self.assertEqual(opener.open.call_args_list[0], opener.open.call_args_list[1])
            self.assert_safe_log(log.getvalue())

    def test_failure_does_not_remove_or_overwrite_an_existing_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            cached = Path(directory) / "source.tar.gz"
            cached.write_bytes(PAYLOAD)
            opener = Mock()
            opener.open.side_effect = HTTPError(URL, 404, "never-print-this", {}, None)
            with patch.object(builder.time, "sleep"), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(RuntimeError):
                    builder.download_input(self.item(), cached, opener)
            self.assertEqual(cached.read_bytes(), PAYLOAD)
            self.assertEqual(list(cached.parent.glob("*.part")), [])


if __name__ == "__main__":
    unittest.main()
