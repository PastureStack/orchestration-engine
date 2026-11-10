#!/usr/bin/env python3
"""Exercise exact builder dependency downloads with no network or installation."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parent.parent
VERSION = "3.9.16"
SHA512 = "831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6"
PRIMARY = "https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz"
ARCHIVE = "https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz"
PAYLOAD = b"Synthetic Maven distribution fixture; never installed."


def shell():
    found = shutil.which("sh")
    if found:
        return found
    git = shutil.which("git")
    if os.name == "nt" and git:
        candidate = Path(git).resolve().parents[1] / "usr/bin/sh.exe"
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError("A POSIX shell is required for the actual builder block test")


def block(name):
    source = (REPO / name).read_text(encoding="utf-8")
    match = re.search(r'(?m)^    \(curl -fsSL [^\n]* -o /tmp/apache-maven\.tar\.gz [^\n]*\n     curl [^\n]*\n    echo "\$\{MAVEN_SHA512\}  /tmp/apache-maven.tar.gz" \| sha512sum -c - && \\\n', source)
    if not match:
        raise AssertionError("Exact grouped download followed by checksum is required")
    return source, match[0].rsplit(" && \\", 1)[0]


class MavenDistributionDownloadTest(unittest.TestCase):
    def run_block(self, name, mode):
        _, production = block(name)
        with tempfile.TemporaryDirectory(prefix="engine-maven-download-") as directory:
            root = Path(directory)
            output = (root / "archive.tar.gz").as_posix()
            log = root / "calls.txt"
            env = dict(os.environ, PST_DOWNLOAD_MODE=mode, PST_DOWNLOAD_LOG=log.as_posix(),
                       PST_PYTHON=Path(sys.executable).as_posix(),
                       MAVEN_VERSION=VERSION, MAVEN_SHA512=hashlib.sha512(PAYLOAD).hexdigest())
            # Only the temporary output path is remapped. URL/flags/control flow
            # and the checksum command are the actual production block.
            script = r'''
set -eu
curl() {
    previous=''; destination=''; url=''
    for value in "$@"; do
        if [ "$previous" = '-o' ]; then destination="$value"; fi
        previous="$value"; url="$value"
    done
    printf '%s\n' "$url" >> "$PST_DOWNLOAD_LOG"
    case "$PST_DOWNLOAD_MODE:$url" in
        all-fail:*|fallback:*dlcdn.apache.org*|fallback-bad:*dlcdn.apache.org*) return 28 ;;
    esac
    case "$PST_DOWNLOAD_MODE" in
        primary-bad|fallback-bad) printf 'corrupted fixture' > "$destination" ;;
        *) printf 'Synthetic Maven distribution fixture; never installed.' > "$destination" ;;
    esac
}
sha512sum() {
    "$PST_PYTHON" -c 'import hashlib,pathlib,sys; expected,path=sys.stdin.readline().strip().split("  ",1); sys.exit(0 if hashlib.sha512(pathlib.Path(path).read_bytes()).hexdigest()==expected else 1)'
}
''' + production.replace("/tmp/apache-maven.tar.gz", output)
            result = subprocess.run([shell(), "-c", script], env=env, capture_output=True, timeout=10)
            return result.returncode, log.read_text(encoding="utf-8").splitlines()

    def test_only_official_exact_version_and_existing_sha512_are_used(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                source, command = block(name)
                self.assertIn("ARG MAVEN_VERSION=" + VERSION + "\n", source)
                self.assertIn("ARG MAVEN_SHA512=" + SHA512 + "\n", source)
                self.assertNotIn("TEMURIN", command)
                self.assertNotIn("jdk", command)
                urls = re.findall(r'"(https://[^\"]+)"', command)
                self.assertEqual([PRIMARY, ARCHIVE], [url.replace("${MAVEN_VERSION}", VERSION) for url in urls])
                self.assertEqual(2, command.count("--retry 5 --retry-all-errors --retry-delay 2 --connect-timeout 10 --max-time 300"))
                self.assertNotIn("--insecure", command)
                self.assertTrue(command.rstrip().endswith("| sha512sum -c -"))

    def test_primary_success_and_transport_fallback_keep_bounded_origin_order(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                self.assertEqual((0, [PRIMARY]), self.run_block(name, "primary"))
                self.assertEqual((0, [PRIMARY, ARCHIVE]), self.run_block(name, "fallback"))

    def test_all_transport_failures_stop_after_two_fixed_origin_commands(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                code, calls = self.run_block(name, "all-fail")
                self.assertNotEqual(0, code)
                self.assertEqual([PRIMARY, ARCHIVE], calls)

    def test_checksum_failure_is_terminal_and_cannot_retry_another_origin(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                code, calls = self.run_block(name, "primary-bad")
                self.assertNotEqual(0, code)
                self.assertEqual([PRIMARY], calls)
                code, calls = self.run_block(name, "fallback-bad")
                self.assertNotEqual(0, code)
                self.assertEqual([PRIMARY, ARCHIVE], calls)



GUAVA_URL = "https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar"
GUAVA_SHA256 = "b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c"
GUAVA_PAYLOAD = b"Synthetic Guava fixture; never installed."


def guava_block(name):
    source = (REPO / name).read_text(encoding="utf-8")
    match = re.search(r'(?m)^RUN curl -fsSL .* /tmp/guava-33\.7\.2-jre\.jar "https://[^"\n]+" && \\\n    echo \'[0-9a-f]{64}  /tmp/guava-33\.7\.2-jre\.jar\' \| sha256sum -c -$', source)
    if not match:
        raise AssertionError("HTTP-status-aware Guava download followed by checksum is required")
    return match[0].removeprefix("RUN ")


class GuavaDownloadTest(unittest.TestCase):
    def run_block(self, name, mode):
        production = guava_block(name)
        with tempfile.TemporaryDirectory(prefix="engine-guava-download-") as directory:
            root = Path(directory)
            output = (root / "guava.jar").as_posix()
            log = root / "calls.txt"
            env = dict(os.environ, PST_DOWNLOAD_MODE=mode, PST_DOWNLOAD_LOG=log.as_posix(),
                       PST_PYTHON=Path(sys.executable).as_posix())
            script = r'''
set -eu
curl() {
    previous=''; destination=''; url=''
    for value in "$@"; do
        if [ "$previous" = '-o' ]; then destination="$value"; fi
        previous="$value"; url="$value"
    done
    printf '%s\n' "$url" >> "$PST_DOWNLOAD_LOG"
    case "$PST_DOWNLOAD_MODE" in
        http-error) return 22 ;;
        transport-error) return 28 ;;
        bad-bytes) printf 'corrupted fixture' > "$destination" ;;
        *) printf 'Synthetic Guava fixture; never installed.' > "$destination" ;;
    esac
}
sha256sum() {
    printf '%s\n' 'checksum' >> "$PST_DOWNLOAD_LOG"
    "$PST_PYTHON" -c 'import hashlib,pathlib,sys; expected,path=sys.stdin.readline().strip().split("  ",1); sys.exit(0 if hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()==expected else 1)'
}
''' + production.replace("/tmp/guava-33.7.2-jre.jar", output).replace(
                GUAVA_SHA256, hashlib.sha256(GUAVA_PAYLOAD).hexdigest())
            result = subprocess.run([shell(), "-c", script], env=env, capture_output=True, timeout=10)
            return result.returncode, log.read_text(encoding="utf-8").splitlines()

    def test_exact_existing_url_hash_and_bounded_http_error_flags(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                command = guava_block(name)
                self.assertEqual([GUAVA_URL], re.findall(r'"(https://[^\"]+)"', command))
                self.assertIn("curl -fsSL --retry 5 --retry-all-errors --retry-delay 2 --connect-timeout 10 --max-time 300", command)
                self.assertIn("echo '" + GUAVA_SHA256 + "  /tmp/guava-33.7.2-jre.jar' | sha256sum -c -", command)
                self.assertNotIn("--insecure", command)
                self.assertNotIn("||", command)

    def test_success_requires_checksum_before_installation(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                self.assertEqual((0, [GUAVA_URL, "checksum"]), self.run_block(name, "success"))

    def test_http_and_transport_errors_stop_before_checksum(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            for mode in ("http-error", "transport-error"):
                with self.subTest(builder=name, mode=mode):
                    code, calls = self.run_block(name, mode)
                    self.assertNotEqual(0, code)
                    self.assertEqual([GUAVA_URL], calls)

    def test_bad_bytes_fail_terminal_checksum_without_retry_or_install(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                code, calls = self.run_block(name, "bad-bytes")
                self.assertNotEqual(0, code)
                self.assertEqual([GUAVA_URL, "checksum"], calls)


if __name__ == "__main__":
    unittest.main()
