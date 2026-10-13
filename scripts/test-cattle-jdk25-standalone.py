#!/usr/bin/env python3
"""Offline contract tests; no application, database, Docker, or network is used."""

import contextlib
import base64
import ctypes
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock
import warnings
import zipfile


SCRIPT = Path(__file__).with_name("check-cattle-jdk25-standalone.py")
SPEC = importlib.util.spec_from_file_location("standalone_gate", SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)
VERSION = "0.183.346"
MANIFEST = "META-INF/MANIFEST.MF"
PROPERTIES = "META-INF/maven/io.cattle/cattle-app/pom.properties"
LAUNCHER = "io/cattle/platform/launcher/Main.class"
JETTY = "io/cattle/platform/launcher/jetty/Main.class"
WEB_XML = "WEB-INF/web.xml"
EMBEDDED_XML = "WEB-INF/content/WEB-INF/web.xml"


def entries():
    return {
        MANIFEST: ("Manifest-Version: 1.0\r\nMain-Class: io.cattle.platform.launcher.Main\r\n"
                   "Implementation-Version: " + VERSION + "\r\n\r\n").encode(),
        PROPERTIES: ("artifactId=cattle-app\r\ngroupId=io.cattle\r\nversion=" + VERSION + "\r\n").encode(),
        LAUNCHER: b"\xca\xfe\xba\xbe\x00\x00\x00\x45",
        JETTY: b"\xca\xfe\xba\xbe\x00\x00\x00\x45cattle.http.bind.address",
        WEB_XML: b"<web-app/>", EMBEDDED_XML: b"<web-app/>",
        f"WEB-INF/lib/cattle-resources-{VERSION}.jar": b"fixture",
        f"WEB-INF/lib/cattle-app-config-{VERSION}.jar": b"fixture",
    }


class StandaloneGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="standalone tests ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.artifact = self.root / "artifact with spaces.war"
        self.write_war(entries())

    def write_war(self, content):
        with zipfile.ZipFile(self.artifact, "w") as archive:
            for name, value in content.items():
                archive.writestr(name, value)

    def run_gate(self):
        with contextlib.redirect_stdout(io.StringIO()):
            gate.run_gate(self.artifact, sys.executable, VERSION, self.root, timeout=2)

    def runner(self, start_output="Startup succeeded", start_status=0, calls=None):
        def run(command, cwd, environment, log_path, timeout):
            if calls is not None:
                calls.append((command, cwd, environment.copy(), timeout))
            if command[-1] == "-version":
                log_path.write_text('openjdk version "25.0.4.1"')
                return 0
            log_path.write_text(start_output)
            return start_status
        return run

    def test_valid_war_and_folded_manifest(self):
        data = entries()
        data[MANIFEST] = data[MANIFEST].replace(b"launcher.Main", b"launcher.\r\n Main")
        self.write_war(data)
        self.assertEqual(gate.validate_artifact(self.artifact, VERSION), gate.sha256(self.artifact))

    def test_invalid_contracts_fail_before_java(self):
        cases = [
            (MANIFEST, entries()[MANIFEST].replace(b"launcher.Main", b"packaging.Bootstrap")),
            (MANIFEST, entries()[MANIFEST].replace(VERSION.encode(), b"0.183.337")),
            (PROPERTIES, b"version=0.183.337\n"),
            (PROPERTIES, ("version=" + VERSION + "\nversion=0.183.337\n").encode()),
            (EMBEDDED_XML, None),
            (EMBEDDED_XML, b"<web-app changed='true'/>"),
            (LAUNCHER, b"\xca\xfe\xba\xbe\x00\x00\x00\x44"),
            (JETTY, b"\xca\xfe\xba\xbe\x00\x00\x00\x45"),
            (f"WEB-INF/lib/cattle-resources-{VERSION}.jar", None),
        ]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                content = entries()
                if value is None:
                    del content[key]
                else:
                    content[key] = value
                self.write_war(content)
                with mock.patch.object(gate, "run_process") as runner:
                    with self.assertRaises(gate.GateError):
                        self.run_gate()
                    runner.assert_not_called()

    def test_duplicate_and_corrupt_archives_fail_closed(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.artifact, "a") as archive:
                archive.writestr(MANIFEST, entries()[MANIFEST])
        with self.assertRaisesRegex(gate.GateError, "duplicate"):
            gate.validate_artifact(self.artifact, VERSION)
        self.artifact.write_bytes(b"not an archive")
        with self.assertRaisesRegex(gate.GateError, "invalid release WAR"):
            gate.validate_artifact(self.artifact, VERSION)

    def test_invalid_descriptor_and_duplicate_manifest_attribute_rejected(self):
        data = entries()
        data[WEB_XML] = data[EMBEDDED_XML] = b"<not-web-app/>"
        self.write_war(data)
        with self.assertRaisesRegex(gate.GateError, "descriptor"):
            gate.validate_artifact(self.artifact, VERSION)
        with self.assertRaisesRegex(gate.GateError, "duplicate manifest"):
            gate.manifest_attributes(b"Main-Class: one\r\nMain-Class: two\r\n")

    def test_one_startup_uses_exact_artifact_and_isolates_even_version_probe(self):
        calls = []
        inherited = {"CATTLE_HTTP_PORT": "8080", "CATTLE_DB_CATTLE_DATABASE": "mysql",
                     "DEFAULT_CATTLE_LOGBACK_LOG_DIR": "/outside-owned-root",
                     "CATTLE_SECRET_TEST": "not-a-real-secret", "JAVA_TOOL_OPTIONS": "bad", "_JAVA_OPTIONS": "bad",
                     "JDK_JAVA_OPTIONS": "bad", "PATH": os.environ.get("PATH", "")}
        with mock.patch.dict(os.environ, inherited, clear=True), mock.patch.object(
                gate, "run_process", side_effect=self.runner(calls=calls)):
            self.run_gate()
        self.assertEqual(len(calls), 2)
        probe, startup = calls
        self.assertEqual(probe[0][-1], "-version")
        self.assertEqual(probe[1:], startup[1:])
        command, cwd, environment, _ = startup
        self.assertEqual(command[-3:], ["-jar", str(self.artifact.resolve()), "--exit"])
        self.assertNotIn("version", command)
        self.assertNotIn("war", command)
        self.assertIn("-Dcattle.http.bind.address=127.0.0.1", command)
        self.assertIn("-Djava.io.tmpdir=" + environment["TMPDIR"], command)
        self.assertTrue(Path(environment["TMPDIR"]).is_relative_to(cwd.parent))
        self.assertIn("-Dhost.api.proxy.mode=off", command)
        self.assertIn("-Ddb.cattle.database=h2", command)
        self.assertEqual(environment["CATTLE_HTTP_PORT"], "0")
        self.assertEqual(environment["CATTLE_HTTP_PROXIED_PORT"], "0")
        self.assertEqual(environment["CATTLE_HOST_API_PROXY_MODE"], "off")
        self.assertEqual(environment["HOME"], environment["CATTLE_HOME"])
        self.assertTrue(Path(environment["HOME"]).is_relative_to(cwd.parent))
        self.assertTrue(environment["CATTLE_DB_CATTLE_H2_URL"].startswith("jdbc:h2:" + Path(environment["HOME"]).as_posix()))
        for key in ("CATTLE_SECRET_TEST", "DEFAULT_CATTLE_LOGBACK_LOG_DIR",
                    "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
            self.assertNotIn(key, environment)

    def test_failed_startup_or_missing_success_never_passes(self):
        for output, status in [("Startup succeeded", 1), ("still starting", 0),
                               ("Startup failed\nStartup succeeded", 0)]:
            with self.subTest(output=output, status=status), mock.patch.object(
                    gate, "run_process", side_effect=self.runner(output, status)):
                with self.assertRaises(gate.GateError):
                    self.run_gate()

    def test_jdk_mismatch_never_starts_application(self):
        def old_java(command, cwd, environment, log_path, timeout):
            log_path.write_text('openjdk version "21.0.1"')
            return 0
        with mock.patch.object(gate, "run_process", side_effect=old_java) as runner:
            with self.assertRaisesRegex(gate.GateError, "JDK25"):
                self.run_gate()
            self.assertEqual(runner.call_count, 1)

    def test_artifact_change_before_or_during_startup_is_rejected(self):
        for mutate_on_probe in (True, False):
            self.write_war(entries())
            good_runner = self.runner()
            def mutate(command, cwd, environment, log_path, timeout):
                result = good_runner(command, cwd, environment, log_path, timeout)
                if (command[-1] == "-version") == mutate_on_probe:
                    with self.artifact.open("ab") as stream:
                        stream.write(b"changed")
                return result
            with self.subTest(probe=mutate_on_probe), mock.patch.object(gate, "run_process", side_effect=mutate):
                with self.assertRaisesRegex(gate.GateError, "artifact changed"):
                    self.run_gate()

    def test_timeout_kills_and_reaps_only_owned_child(self):
        real_popen = subprocess.Popen
        unrelated = real_popen([sys.executable, "-c", "import time; time.sleep(30)"])
        self.addCleanup(unrelated.wait)
        self.addCleanup(lambda: unrelated.poll() is None and unrelated.kill())
        owned = []
        def capture(*args, **kwargs):
            child = real_popen(*args, **kwargs)
            owned.append(child)
            return child
        with mock.patch.object(gate.subprocess, "Popen", side_effect=capture):
            with self.assertRaisesRegex(gate.GateError, "timed out"):
                gate.run_process([sys.executable, "-c", "import time; time.sleep(30)"],
                                 self.root, os.environ.copy(), self.root / "timeout.log", 0.05)
        self.assertIsNotNone(owned[0].poll())
        self.assertIsNone(unrelated.poll())
        unrelated.kill()
        unrelated.wait()

    def test_timeout_configuration_is_bounded(self):
        for value in (0, -1, 601, float("nan"), float("inf")):
            with self.subTest(value=value), self.assertRaises(gate.GateError):
                gate.run_gate(self.artifact, "unused", VERSION, timeout=value)

    def test_java_home_with_spaces_is_used_when_java_is_not_on_path(self):
        java_home = self.root / "jdk with spaces"
        java_bin = java_home / "bin"
        java_bin.mkdir(parents=True)
        java = java_bin / "java.exe"
        java.write_bytes(b"fixture")
        with mock.patch.dict(os.environ, {"JAVA_HOME": str(java_home)}), mock.patch.object(
                gate.shutil, "which", return_value=None):
            self.assertEqual(gate.resolve_java("java"), str(java.resolve()))
            self.assertEqual(gate.resolve_java("explicit-java"), "explicit-java")

    def test_native_windows_and_linux_paths_do_not_use_wslpath(self):
        for windows, java, path in ((True, r"C:\Java\bin\java.exe", r"C:\owned space\home"),
                                    (False, "/usr/bin/java", "/tmp/owned space/home")):
            with self.subTest(windows=windows), mock.patch.object(gate, "native_windows", return_value=windows), \
                    mock.patch.object(gate, "wsl_path") as converter:
                self.assertEqual(gate.path_for_java(path, java), path)
                converter.assert_not_called()

    def test_wslpath_uses_argv_for_drive_unc_and_java_home_paths(self):
        cases = (("-w", "/mnt/c/release with spaces/app.war", r"C:\release with spaces\app.war"),
                 ("-w", "/tmp/owned space/home", r"\\wsl.localhost\Ubuntu\tmp\owned space\home"),
                 ("-u", r"C:\Program Files\Java", "/mnt/c/Program Files/Java"))
        for option, source, converted in cases:
            with self.subTest(source=source), mock.patch.object(gate.shutil, "which", return_value="/usr/bin/wslpath"), \
                    mock.patch.object(gate.subprocess, "run", return_value=subprocess.CompletedProcess(
                        [], 0, converted + "\n", "")) as command:
                self.assertEqual(gate.wsl_path(source, option), converted)
                command.assert_called_once_with(["/usr/bin/wslpath", option, source], check=True,
                                                capture_output=True, text=True, timeout=10)

    def test_wslpath_missing_failed_or_invalid_output_fails_closed(self):
        with mock.patch.object(gate.shutil, "which", return_value=None), \
                mock.patch.object(gate.subprocess, "run") as command:
            with self.assertRaisesRegex(gate.GateError, "requires wslpath"):
                gate.wsl_path("/tmp/home", "-w")
            command.assert_not_called()
        failures = (OSError("not executable"), subprocess.CalledProcessError(1, ["wslpath"]),
                    subprocess.TimeoutExpired(["wslpath"], 10))
        for failure in failures:
            with self.subTest(failure=type(failure).__name__), \
                    mock.patch.object(gate.shutil, "which", return_value="/usr/bin/wslpath"), \
                    mock.patch.object(gate.subprocess, "run", side_effect=failure):
                with self.assertRaisesRegex(gate.GateError, "conversion failed"):
                    gate.wsl_path("/tmp/home", "-w")
        for option, output in (("-w", ""), ("-w", "relative/path"), ("-w", "/tmp/home"),
                               ("-w", "C:\\home\nC:\\other"), ("-w", "C:\\home\0bad"),
                               ("-u", r"C:\home")):
            with self.subTest(output=output), mock.patch.object(gate.shutil, "which", return_value="/usr/bin/wslpath"), \
                    mock.patch.object(gate.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output, "")):
                with self.assertRaisesRegex(gate.GateError, "invalid absolute path"):
                    gate.wsl_path("/tmp/home", option)

    def test_wsl_windows_java_home_and_explicit_executable_are_resolved(self):
        java_home = r"C:\Program Files\Java"
        converted_home = "/mnt/c/Program Files/Java"
        converted_java = converted_home + "/bin/java.exe"
        with mock.patch.object(gate, "native_windows", return_value=False), \
                mock.patch.dict(os.environ, {"JAVA_HOME": java_home}), \
                mock.patch.object(gate.shutil, "which", return_value=None), \
                mock.patch.object(gate, "wsl_path", return_value=converted_home) as converter, \
                mock.patch.object(gate.Path, "is_file", lambda path: str(path).replace("\\", "/").endswith("/bin/java.exe")):
            self.assertEqual(gate.resolve_java("java"), converted_java)
            converter.assert_called_once_with(java_home, "-u")
        with mock.patch.object(gate, "native_windows", return_value=False), \
                mock.patch.object(gate, "wsl_path", return_value=converted_java) as converter, \
                mock.patch.object(gate.shutil, "which", return_value=converted_java):
            self.assertEqual(gate.resolve_java(java_home + r"\bin\java.exe"), converted_java)
            converter.assert_called_once_with(java_home + r"\bin\java.exe", "-u")

    def test_mock_wsl_windows_java_receives_converted_paths_and_keeps_owned_host_cwd(self):
        # This is a simulated interop contract, not a real WSL/Windows JVM run.
        calls, converted_paths = [], []
        def convert(path, option):
            self.assertEqual(option, "-w")
            converted_paths.append(Path(path))
            if Path(path) == self.artifact.resolve():
                return r"C:\release with spaces\app.war"
            if str(path) == "/mnt/c/Java/bin/java.exe":
                return r"C:\Java\bin\java.exe"
            return r"\\wsl.localhost\Ubuntu\tmp\owned space" + "\\" + Path(path).name
        inherited = {"WSLENV": "EXISTING/u:HOME/p:TMPDIR/p:JAVA_TOOL_OPTIONS/u",
                     "JAVA_TOOL_OPTIONS": "untrusted fixture", "CATTLE_HTTP_PORT": "8080"}
        with mock.patch.object(gate, "native_windows", return_value=False), \
                mock.patch.object(gate, "resolve_java", return_value="/mnt/c/Java/bin/java.exe"), \
                mock.patch.object(gate, "wsl_path", side_effect=convert), \
                mock.patch.dict(os.environ, inherited, clear=True), \
                mock.patch.object(gate, "run_windows_process", side_effect=self.runner(calls=calls)), \
                mock.patch.object(gate, "run_process") as direct:
            self.run_gate()
            direct.assert_not_called()
        self.assertEqual(len(calls), 2)
        probe, startup = calls
        self.assertEqual(probe[1:], startup[1:])
        command, cwd, environment, _ = startup
        root = converted_paths[1].parent
        self.assertTrue(root.is_relative_to(self.root))
        self.assertEqual(converted_paths, [self.artifact.resolve(), root / "home", root / "tmp",
                                          Path("/mnt/c/Java/bin/java.exe"), root / "cwd"])
        self.assertEqual(cwd, r"\\wsl.localhost\Ubuntu\tmp\owned space\cwd")
        self.assertEqual(command[0], r"C:\Java\bin\java.exe")
        self.assertEqual(command[-3:], ["-jar", r"C:\release with spaces\app.war", "--exit"])
        self.assertIn("-Dcattle.home=" + environment["CATTLE_HOME"], command)
        self.assertIn("-Djava.io.tmpdir=" + environment["TMPDIR"], command)
        self.assertIn("-Ddb.cattle.h2.url=" + environment["CATTLE_DB_CATTLE_H2_URL"], command)
        self.assertEqual(environment["HOME"], r"\\wsl.localhost\Ubuntu\tmp\owned space\home")
        self.assertTrue(environment["CATTLE_DB_CATTLE_H2_URL"].startswith(
            "jdbc:h2://wsl.localhost/Ubuntu/tmp/owned space/home/database/db-cattle;"))
        self.assertNotIn("JAVA_TOOL_OPTIONS", environment)

    def test_windows_bridge_payload_preserves_paths_and_only_transfers_owned_values(self):
        environment = gate.isolated_environment(r"\\wsl.localhost\Ubuntu\owned space\home",
                                                r"C:\owned space\tmp", {"UNRELATED": "private"})
        command = [r"C:\Program Files\Java\bin\java.exe", '-Dquote=a"b', "C:\\trailing space\\", "--exit"]
        with mock.patch.object(gate.shutil, "which", return_value="powershell.exe"):
            invocation, encoded_payload = gate.windows_bridge_command(
                command, r"\\wsl.localhost\Ubuntu\owned space\cwd", environment, 2)
        script = base64.b64decode(invocation[-1]).decode("utf-16-le")
        self.assertIn("[Console]::In.ReadToEnd()", script)
        payload = json.loads(encoded_payload)
        self.assertEqual(payload["command"], command)
        self.assertEqual(payload["cwd"], r"\\wsl.localhost\Ubuntu\owned space\cwd")
        self.assertEqual(payload["timeout"], 2000)
        self.assertEqual(payload["keys"], list(gate.ISOLATED_ENVIRONMENT_KEYS))
        self.assertNotIn("UNRELATED", payload["keys"])
        with mock.patch.object(gate.shutil, "which", return_value=None):
            with self.assertRaisesRegex(gate.GateError, "requires native powershell") as error:
                gate.windows_bridge_command(command, "cwd", environment, 2)
        self.assertEqual(error.exception.status, 2)

    @unittest.skipUnless(os.name == "nt", "requires the real Windows process boundary")
    def test_native_windows_bridge_cleans_host_environment_and_preserves_argv(self):
        # This executes Windows PowerShell and a native child; it is not a WSL run.
        home, temp = self.root / "home", self.root / "tmp"
        home.mkdir()
        temp.mkdir()
        environment = gate.isolated_environment(home, temp)
        arguments = ['quote="value"', "has spaces", "trailing slash\\", "繁體中文"]
        inspected_keys = list(gate.ISOLATED_ENVIRONMENT_KEYS) + list(gate.JAVA_OPTION_KEYS) + [
            "CATTLE_LOGBACK_LOG_DIR", "DEFAULT_CATTLE_LOGBACK_LOG_DIR"]
        code = "import json,os,sys; keys=" + repr(inspected_keys) + "; print(json.dumps({'env':{k:os.environ[k] for k in keys if k in os.environ},'argv':sys.argv[1:],'cwd':os.getcwd()},ensure_ascii=True))"
        log = self.root / "native-bridge.log"
        host_only = {"CATTLE_LOGBACK_LOG_DIR": "outside-root", "DEFAULT_CATTLE_LOGBACK_LOG_DIR": "outside-root",
                     "JAVA_TOOL_OPTIONS": "host-only", "_JAVA_OPTIONS": "host-only", "JDK_JAVA_OPTIONS": "host-only"}
        with mock.patch.dict(os.environ, host_only):
            status = gate.run_windows_process([sys.executable, "-c", code] + arguments,
                                              self.root, environment, log, 5)
        self.assertEqual(status, 0, log.read_text(errors="replace"))
        result = json.loads(log.read_text())
        self.assertEqual(result["argv"], arguments)
        self.assertEqual(Path(result["cwd"]), self.root)
        for key in host_only:
            self.assertNotIn(key, result["env"])
        for key in gate.ISOLATED_ENVIRONMENT_KEYS:
            self.assertEqual(result["env"][key], environment[key])

    @unittest.skipUnless(os.name == "nt", "requires the real Windows process boundary")
    def test_native_windows_bridge_timeout_stops_owned_child_only(self):
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        self.addCleanup(unrelated.wait)
        self.addCleanup(lambda: unrelated.poll() is None and unrelated.kill())
        pid_file = self.root / "owned.pid"
        code = "import os,pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(30)"
        environment = gate.isolated_environment(self.root, self.root)
        log = self.root / "native-timeout.log"
        with self.assertRaisesRegex(gate.GateError, "timed out"):
            gate.run_windows_process([sys.executable, "-c", code, str(pid_file)],
                                     self.root, environment, log, 1)
        pid = int(pid_file.read_text())
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.restype = ctypes.c_void_p
        handle = kernel.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE, only this fixture PID.
        if handle:
            try:
                kernel.WaitForSingleObject.argtypes = (ctypes.c_void_p, ctypes.c_uint32)
                self.assertEqual(kernel.WaitForSingleObject(handle, 5000), 0)
            finally:
                kernel.CloseHandle.argtypes = (ctypes.c_void_p,)
                kernel.CloseHandle(handle)
        self.assertIsNone(unrelated.poll())

    @unittest.skipUnless(os.name == "nt", "requires the real Windows process boundary")
    def test_native_windows_bridge_termination_closes_owned_job(self):
        pid_file = self.root / "job-child.pid"
        code = "import os,pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(30)"
        environment = gate.isolated_environment(self.root, self.root)
        command, payload = gate.windows_bridge_command(
            [sys.executable, "-c", code, str(pid_file)], self.root, environment, 20)
        log = self.root / "job-close.log"
        with log.open("wb") as output:
            bridge = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT)
            try:
                bridge.stdin.write(payload)
                bridge.stdin.close()
                deadline = time.monotonic() + 10
                while not pid_file.exists() and bridge.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.05)
                self.assertTrue(pid_file.exists(), log.read_text(errors="replace"))
                pid = int(pid_file.read_text())
                kernel = ctypes.WinDLL("kernel32", use_last_error=True)
                kernel.OpenProcess.restype = ctypes.c_void_p
                kernel.WaitForSingleObject.argtypes = (ctypes.c_void_p, ctypes.c_uint32)
                kernel.TerminateProcess.argtypes = (ctypes.c_void_p, ctypes.c_uint32)
                kernel.CloseHandle.argtypes = (ctypes.c_void_p,)
                handle = kernel.OpenProcess(0x00100001, False, pid)  # SYNCHRONIZE | PROCESS_TERMINATE
                self.assertTrue(handle)
                try:
                    bridge.kill()
                    bridge.wait(timeout=5)
                    self.assertEqual(kernel.WaitForSingleObject(handle, 5000), 0)
                finally:
                    # On an assertion failure clean up only this recorded fixture.
                    if kernel.WaitForSingleObject(handle, 0) != 0:
                        kernel.TerminateProcess(handle, 1)
                        kernel.WaitForSingleObject(handle, 5000)
                    kernel.CloseHandle(handle)
            finally:
                if bridge.poll() is None:
                    bridge.kill()
                bridge.wait(timeout=5)

    def test_wsl_path_failure_never_starts_java_and_retains_owned_evidence(self):
        with mock.patch.object(gate, "native_windows", return_value=False), \
                mock.patch.object(gate, "resolve_java", return_value="/mnt/c/Java/bin/java.exe"), \
                mock.patch.object(gate, "wsl_path", side_effect=gate.GateError("conversion failed")), \
                mock.patch.object(gate, "run_process") as runner:
            with self.assertRaisesRegex(gate.GateError, "conversion failed"):
                self.run_gate()
            runner.assert_not_called()
        evidence = list(self.root.glob("cattle-standalone-*"))
        self.assertEqual(len(evidence), 1)
        self.assertTrue((evidence[0] / "home").is_dir())

    def test_stale_dist_copy_cannot_mask_current_packaged_war(self):
        reference = self.root / "different app.war"
        reference.write_bytes(b"different build")
        with mock.patch.object(gate, "run_process") as runner:
            with self.assertRaisesRegex(gate.GateError, "differs from"):
                gate.run_gate(self.artifact, "unused", VERSION, reference_artifact=reference)
            runner.assert_not_called()

    def test_ci_calls_standalone_before_unit_early_return(self):
        source = SCRIPT.with_name("ci").read_text()
        self.assertLess(source.index("./check-cattle-jdk25-bytecode"), source.index("./check-cattle-jdk25-standalone"))
        self.assertLess(source.index("./check-cattle-jdk25-standalone"), source.index('if [ "$CI_PLAN" = "unit" ]'))
        wrapper = SCRIPT.with_suffix("").read_text()
        self.assertIn("dist/artifacts/cattle.jar", wrapper)
        self.assertNotIn("code/packaging/bundle", wrapper)
        self.assertIn('if [ -f "$app_war" ]; then\n    args+=(--reference-artifact "$app_war")', wrapper)


if __name__ == "__main__":
    unittest.main()
