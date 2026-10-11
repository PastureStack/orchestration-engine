#!/usr/bin/env python3
"""Validate and boot the exact release WAR, without bundle-only CLI commands."""

import argparse
import base64
import collections
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


JAVA_OPTION_KEYS = ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")
ISOLATED_ENVIRONMENT_KEYS = (
    "HOME", "USERPROFILE", "TMPDIR", "TMP", "TEMP", "CATTLE_HOME",
    "CATTLE_HTTP_PORT", "CATTLE_HTTP_PROXIED_PORT", "CATTLE_HOST_API_PROXY_MODE",
    "CATTLE_DB_CATTLE_DATABASE", "CATTLE_DB_CATTLE_H2_URL",
    "CATTLE_DB_CATTLE_H2_RENDER_NAME_STYLE", "CATTLE_DB_CONFIG_ALIAS", "CATTLE_DB_LIQUIBASE_ALIAS",
)

# The Windows interop host contributes its own environment before WSLENV is
# applied. Sanitize at the native child boundary instead of trusting Linux's
# environment dictionary. The job owns only this invocation and its children.
WINDOWS_BRIDGE_SOURCE = r"""
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

public static class StandaloneJavaBridge {
    [StructLayout(LayoutKind.Sequential)]
    struct BasicLimits {
        public long ProcessTime, JobTime;
        public uint Flags;
        public UIntPtr MinWorkingSet, MaxWorkingSet;
        public uint ActiveProcesses;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct IoCounters {
        public ulong ReadOperations, WriteOperations, OtherOperations;
        public ulong ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct ExtendedLimits {
        public BasicLimits Basic;
        public IoCounters Io;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern SafeFileHandle CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetInformationJobObject(SafeFileHandle job, int type,
        ref ExtendedLimits limits, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool AssignProcessToJobObject(SafeFileHandle job, IntPtr process);

    static string Quote(string value) {
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value) {
            if (c == '\\') { slashes++; continue; }
            result.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
            result.Append(c);
            slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }

    public static int Run(string[] command, string cwd, string[] keys, string[] values, int timeout) {
        using (var job = CreateJobObject(IntPtr.Zero, null)) {
            if (job.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            var limits = new ExtendedLimits();
            limits.Basic.Flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf(limits)))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            var start = new ProcessStartInfo(command[0], String.Join(" ", command.Skip(1).Select(Quote)));
            start.WorkingDirectory = cwd;
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            start.RedirectStandardOutput = start.RedirectStandardError = true;
            start.RedirectStandardInput = true;
            foreach (string key in start.EnvironmentVariables.Keys.Cast<string>().ToArray()) {
                string upper = key.ToUpperInvariant();
                if (upper.StartsWith("CATTLE_") || upper.StartsWith("DEFAULT_CATTLE_") ||
                    upper == "JAVA_TOOL_OPTIONS" || upper == "_JAVA_OPTIONS" || upper == "JDK_JAVA_OPTIONS")
                    start.EnvironmentVariables.Remove(key);
            }
            for (int i = 0; i < keys.Length; i++) start.EnvironmentVariables[keys[i]] = values[i];
            using (var process = new Process()) {
                process.StartInfo = start;
                process.OutputDataReceived += (sender, e) => { if (e.Data != null) Console.Out.WriteLine(e.Data); };
                process.ErrorDataReceived += (sender, e) => { if (e.Data != null) Console.Out.WriteLine(e.Data); };
                bool started = false;
                try {
                    started = process.Start();
                    if (!AssignProcessToJobObject(job, process.Handle))
                        throw new Win32Exception(Marshal.GetLastWin32Error());
                    process.StandardInput.Close();
                    process.BeginOutputReadLine();
                    process.BeginErrorReadLine();
                    if (!process.WaitForExit(timeout)) throw new TimeoutException("Java child timed out");
                    process.WaitForExit(); // Drain both redirected output streams.
                    return process.ExitCode;
                } finally {
                    if (started && !process.HasExited) {
                        process.Kill();
                        process.WaitForExit();
                    }
                }
            }
        }
    }
}
"""


class GateError(Exception):
    def __init__(self, message, status=1):
        super().__init__(message)
        self.status = status


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def manifest_attributes(data):
    unfolded = []
    for line in data.decode("utf-8").splitlines():
        if not line:
            break
        if line.startswith(" "):
            if not unfolded:
                raise GateError("invalid manifest continuation")
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)
    attributes = {}
    for line in unfolded:
        key, separator, value = line.partition(": ")
        key = key.lower()
        if not separator or key in attributes:
            raise GateError("invalid or duplicate manifest attribute")
        attributes[key] = value
    return attributes


def validate_artifact(artifact, expected_version):
    try:
        with zipfile.ZipFile(artifact) as archive:
            counts = collections.Counter(archive.namelist())
            if any(count != 1 for count in counts.values()):
                raise GateError("duplicate archive entry")
            required = (
                "META-INF/MANIFEST.MF",
                "META-INF/maven/io.cattle/cattle-app/pom.properties",
                "io/cattle/platform/launcher/Main.class",
                "io/cattle/platform/launcher/jetty/Main.class",
                "WEB-INF/web.xml",
                "WEB-INF/content/WEB-INF/web.xml",
                f"WEB-INF/lib/cattle-resources-{expected_version}.jar",
                f"WEB-INF/lib/cattle-app-config-{expected_version}.jar",
            )
            for entry in required:
                if counts[entry] != 1:
                    raise GateError("missing WAR asset: " + entry)
            attributes = manifest_attributes(archive.read(required[0]))
            if attributes.get("main-class") != "io.cattle.platform.launcher.Main":
                raise GateError("artifact is not the release WAR launcher")
            if attributes.get("implementation-version") != expected_version:
                raise GateError("manifest version mismatch")
            properties = archive.read(required[1]).decode("utf-8").splitlines()
            versions = [line[len("version="):] for line in properties if line.startswith("version=")]
            if versions != [expected_version]:
                raise GateError("Maven version mismatch")
            for entry in required[2:4]:
                data = archive.read(entry)
                if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") != 69:
                    raise GateError("launcher class is not JDK25 bytecode: " + entry)
            if b"cattle.http.bind.address" not in archive.read(required[3]):
                raise GateError("WAR launcher does not support isolated loopback binding")
            web_xml = archive.read("WEB-INF/web.xml")
            if web_xml != archive.read("WEB-INF/content/WEB-INF/web.xml"):
                raise GateError("embedded web.xml differs from release web.xml")
            if ET.fromstring(web_xml).tag.rsplit("}", 1)[-1] != "web-app":
                raise GateError("invalid web application descriptor")
            if archive.testzip() is not None:
                raise GateError("corrupt archive entry")
    except (OSError, zipfile.BadZipFile, UnicodeError, ET.ParseError) as error:
        raise GateError("invalid release WAR: " + str(error)) from error
    return sha256(artifact)


def native_windows():
    return os.name == "nt"


def windows_absolute_path(value):
    return re.match(r"^(?:[A-Za-z]:[\\/]|[\\/]{2}[^\\/]+[\\/][^\\/]+)", value) is not None


def wsl_path(value, option):
    converter = shutil.which("wslpath")
    if not converter:
        raise GateError("WSL Windows Java requires wslpath", 2)
    try:
        result = subprocess.run([converter, option, str(value)], check=True,
                                capture_output=True, text=True, timeout=10)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise GateError("WSL path conversion failed", 2) from error
    converted = result.stdout.rstrip("\r\n")
    if (not converted or any(char in converted for char in "\r\n\0")
            or (option == "-u" and not converted.startswith("/"))
            or (option == "-w" and not windows_absolute_path(converted))):
        raise GateError("WSL path conversion returned an invalid absolute path")
    return converted


def windows_java(java):
    return not native_windows() and java.lower().endswith(".exe")


def path_for_java(path, java):
    return wsl_path(path, "-w") if windows_java(java) else str(path)


def isolated_environment(home, temp, inherited=None):
    source = os.environ if inherited is None else inherited
    environment = {
        key: value for key, value in source.items()
        if not key.upper().startswith(("CATTLE_", "DEFAULT_CATTLE_"))
        and key.upper() not in JAVA_OPTION_KEYS
    }
    h2_url = "jdbc:h2:" + str(home).replace("\\", "/").rstrip("/") + "/database/db-cattle" + (
        ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=KEY,VALUE"
    )
    environment.update({
        "HOME": str(home), "USERPROFILE": str(home),
        "TMPDIR": str(temp), "TMP": str(temp), "TEMP": str(temp),
        "CATTLE_HOME": str(home),
        "CATTLE_HTTP_PORT": "0", "CATTLE_HTTP_PROXIED_PORT": "0",
        "CATTLE_HOST_API_PROXY_MODE": "off",
        "CATTLE_DB_CATTLE_DATABASE": "h2", "CATTLE_DB_CATTLE_H2_URL": h2_url,
        "CATTLE_DB_CATTLE_H2_RENDER_NAME_STYLE": "upper",
        "CATTLE_DB_CONFIG_ALIAS": "cattle", "CATTLE_DB_LIQUIBASE_ALIAS": "cattle",
    })
    return environment


def startup_command(java, artifact, home, environment):
    return [java,
            "-Dcattle.home=" + str(home),
            "-Djava.io.tmpdir=" + environment["TMPDIR"],
            "-Dcattle.http.bind.address=127.0.0.1",
            "-Dcattle.http.port=0", "-Dcattle.http.proxied.port=0", "-Dcattle.https.port=0",
            "-Dhost.api.proxy.mode=off", "-Ddb.cattle.database=h2",
            "-Ddb.cattle.h2.url=" + environment["CATTLE_DB_CATTLE_H2_URL"],
            "-Ddb.cattle.h2.render.name.style=upper",
            "-Ddb.config.alias=cattle", "-Ddb.liquibase.alias=cattle",
            "-jar", str(artifact), "--exit"]


def resolve_java(command):
    if not native_windows() and windows_absolute_path(command):
        command = wsl_path(command, "-u")
    resolved = shutil.which(command)
    if resolved:
        return resolved
    if command == "java" and os.environ.get("JAVA_HOME"):
        java_home = os.environ["JAVA_HOME"]
        converted_home = not native_windows() and windows_absolute_path(java_home)
        if converted_home:
            java_home = wsl_path(java_home, "-u")
        for name in ("java", "java.exe"):
            candidate = java_home.rstrip("/\\") + "/bin/" + name
            if Path(candidate).is_file():
                return candidate if converted_home else str(Path(candidate).resolve())
    return command


def run_process(command, cwd, environment, log_path, timeout, input_data=None):
    try:
        with log_path.open("wb") as log:
            process = subprocess.Popen(command, cwd=cwd, env=environment,
                                       stdin=subprocess.PIPE if input_data is not None else subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT)
            try:
                if input_data is not None:
                    process.communicate(input=input_data, timeout=timeout)
                    return process.returncode
                return process.wait(timeout=timeout)
            except subprocess.TimeoutExpired as error:
                process.kill()
                process.wait()
                raise GateError("child timed out and was stopped: " + log_path.name) from error
    except OSError as error:
        raise GateError("cannot launch Java: " + str(error), 2) from error


def windows_bridge_command(command, cwd, environment, timeout):
    powershell = shutil.which("powershell.exe")
    if not powershell:
        raise GateError("WSL Windows Java requires native powershell.exe", 2)
    payload = {
        "command": command, "cwd": str(cwd), "timeout": math.ceil(timeout * 1000),
        "keys": list(ISOLATED_ENVIRONMENT_KEYS),
        "values": [environment[key] for key in ISOLATED_ENVIRONMENT_KEYS],
    }
    script = "$ErrorActionPreference = 'Stop'\n$ProgressPreference = 'SilentlyContinue'\ntry {\nAdd-Type -TypeDefinition @'\n" + WINDOWS_BRIDGE_SOURCE + "\n'@\n"
    script += "$request = [Console]::In.ReadToEnd() | ConvertFrom-Json\n"
    script += "exit [StandaloneJavaBridge]::Run([string[]]$request.command, [string]$request.cwd, [string[]]$request.keys, [string[]]$request.values, [int]$request.timeout)\n"
    script += "} catch {\n[Console]::Error.WriteLine($_.Exception.ToString())\n"
    script += "if ($_.Exception.GetBaseException() -is [TimeoutException]) { exit 124 }; exit 125\n}\n"
    encoded_script = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    command = [powershell, "-NoLogo", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text",
               "-EncodedCommand", encoded_script]
    # Keep arbitrary paths and arguments out of the PowerShell command line and
    # below Windows' command-line limit. JSON's ASCII escapes survive interop.
    return command, json.dumps(payload).encode("ascii")


def run_windows_process(command, cwd, environment, log_path, timeout):
    bridge, payload = windows_bridge_command(command, cwd, environment, timeout)
    # Native Java gets the requested timeout. Allow bounded bridge compilation
    # and teardown time; closing its job also terminates an owned Java child.
    status = run_process(bridge, None, os.environ.copy(), log_path, timeout + 30, input_data=payload)
    if status == 124:
        raise GateError("Java child timed out and was stopped: " + log_path.name)
    if status == 125:
        raise GateError("native Windows Java bridge failed: " + log_path.name, 2)
    return status


def run_gate(artifact, java, expected_version, temporary_root=None, keep=False, timeout=180, reference_artifact=None):
    if not math.isfinite(timeout) or timeout <= 0 or timeout > 600:
        raise GateError("timeout must be positive and at most 600 seconds")
    artifact = Path(artifact).resolve(strict=True)
    digest = validate_artifact(artifact, expected_version)
    if reference_artifact is not None and sha256(Path(reference_artifact).resolve(strict=True)) != digest:
        raise GateError("release WAR differs from the packaged app WAR")
    print(f"CATTLE_STANDALONE_ARTIFACT_OK format=war version={expected_version} sha256={digest}", flush=True)
    if temporary_root is not None:
        temporary_root = Path(temporary_root).resolve()
        temporary_root.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix="cattle-standalone-", dir=temporary_root)).resolve()
    succeeded = False
    try:
        cwd, home, temp = (root / name for name in ("cwd", "home", "tmp"))
        for directory in (cwd, home, temp):
            directory.mkdir()
        java = resolve_java(java)
        java_artifact, java_home, java_temp = (path_for_java(path, java) for path in (artifact, home, temp))
        environment = isolated_environment(java_home, java_temp)
        if windows_java(java):
            java = wsl_path(java, "-w")
            process_cwd = wsl_path(cwd, "-w")
            runner = run_windows_process
        else:
            process_cwd, runner = cwd, run_process
        probe_log = root / "java-version.log"
        if runner([java, "-version"], process_cwd, environment, probe_log, min(timeout, 30)) != 0:
            raise GateError("Java version probe failed", 2)
        if not re.search(r'version\s+"?25(?:[.\s"]|$)', probe_log.read_text(errors="replace")):
            raise GateError("JDK25 is required for standalone validation", 2)
        if sha256(artifact) != digest:
            raise GateError("artifact changed before startup")
        start_log = root / "start.log"
        status = runner(startup_command(java, java_artifact, java_home, environment),
                        process_cwd, environment, start_log, timeout)
        output = start_log.read_text(errors="replace")
        if status != 0:
            raise GateError(f"standalone startup exited with status {status}")
        if not re.search(r"Startup succeeded", output, re.IGNORECASE) or re.search(r"Startup failed", output, re.IGNORECASE):
            raise GateError("standalone startup did not complete successfully")
        if sha256(artifact) != digest:
            raise GateError("artifact changed during startup")
        succeeded = True
        print(f"CATTLE_JDK25_STANDALONE_OK format=war version={expected_version} sha256={digest} loopback=127.0.0.1 port=0 database=h2 startup_exit=0")
        print("failure_count=0")
    finally:
        if keep or not succeeded:
            print("Standalone evidence directory: " + str(root), flush=True)
        else:
            shutil.rmtree(root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--temporary-root")
    parser.add_argument("--reference-artifact")
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--timeout", type=float, default=180)
    args = parser.parse_args()
    try:
        run_gate(args.artifact, args.java, args.expected_version, args.temporary_root, args.keep,
                 args.timeout, args.reference_artifact)
    except (GateError, OSError) as error:
        print("CATTLE_JDK25_STANDALONE_FAIL " + str(error), file=sys.stderr)
        return getattr(error, "status", 1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
