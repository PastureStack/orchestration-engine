#!/usr/bin/env python3
"""Focused positive and negative fixtures for the release content gate."""

import io
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


GATE = Path(__file__).with_name("check-release-artifact-no-dev.py")


def jar_bytes(entries):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as jar:
        for name, contents in entries.items():
            jar.writestr(name, contents)
    return data.getvalue()


class ReleaseArtifactNoDevTest(unittest.TestCase):
    def check_fixture(self, entries, expected_marker=None):
        with tempfile.TemporaryDirectory() as tempdir:
            artifact = Path(tempdir) / "cattle.jar"
            with zipfile.ZipFile(artifact, "w") as jar:
                for name, contents in entries.items():
                    jar.writestr(name, contents)
            result = subprocess.run(
                [sys.executable, str(GATE), str(artifact)],
                text=True,
                capture_output=True,
                check=False,
            )
        if expected_marker:
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertIn(expected_marker, result.stderr)
        else:
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_clean_archive(self):
        self.check_fixture({
            "WEB-INF/lib/cattle-app-config-0.183.321.jar":
                jar_bytes({"META-INF/cattle/defaults/defaults.properties": "safe=true"}),
        })

    def test_dev_jar(self):
        self.check_fixture({
            "WEB-INF/lib/cattle-dev-0.183.321.jar": jar_bytes({"safe.txt": ""}),
        }, "ENGINE_RELEASE_DEV_JAR_FORBIDDEN")

    def test_outer_dev_defaults(self):
        self.check_fixture({
            "WEB-INF/classes/META-INF/cattle/defaults/dev-defaults.properties": "api.dev=true",
        }, "ENGINE_RELEASE_DEV_DEFAULTS_FORBIDDEN")

    def test_nested_dev_defaults_in_other_module(self):
        self.check_fixture({
            "WEB-INF/lib/cattle-app-config-0.183.321.jar": jar_bytes({
                "META-INF/cattle/defaults/dev-defaults.properties": "api.dev=true",
            }),
        }, "ENGINE_RELEASE_DEV_DEFAULTS_FORBIDDEN")

    def test_nested_dev_defaults_outside_web_inf_lib(self):
        self.check_fixture({
            "WEB-INF/jetty/other.jar": jar_bytes({
                "META-INF/cattle/defaults/dev-defaults.properties": "api.dev=true",
            }),
        }, "ENGINE_RELEASE_DEV_DEFAULTS_FORBIDDEN")

    def test_corrupt_library_fails_closed(self):
        self.check_fixture({
            "WEB-INF/lib/cattle-app-config-0.183.321.jar": b"not a jar",
        }, "ENGINE_RELEASE_INVALID_LIBRARY")


if __name__ == "__main__":
    unittest.main()
