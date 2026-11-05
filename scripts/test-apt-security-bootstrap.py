#!/usr/bin/env python3
"""Exercise the exact APT bootstrap security boundary without network or installation."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parent.parent
VERSION = "3.2.1ubuntu0.1"
PACKAGES = {
    "apt": "0de729b8fe0e1c67c04e8e8d5bd6e0420dbd47f75b93f0b71c8e740edc2d5ffc",
    "libapt-pkg7.0": "94e5d41303265b66af0f8c70ade18bfb77260ad286edee6c8045faddef82b94c",
}


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
    start = source.index("    for package in apt libapt-pkg7.0; do \\\n")
    end = source.index("    . /licenses/ubuntu-apt.lock; \\\n", start)
    return source, source[start:end]


class AptSecurityBootstrapTest(unittest.TestCase):
    def run_block(self, name, mode):
        _, production = block(name)
        with tempfile.TemporaryDirectory(prefix="engine-apt-security-") as directory:
            root = Path(directory)
            log = root / "calls.txt"
            commands = {
                "dpkg-query": r"""case "$*" in
    *db:Status-Status*)
        if [ "$PST_APT_MODE" = missing-installed ]; then printf 'not-installed'; else printf 'installed'; fi ;;
    *)
        if [ "$PST_APT_MODE" = wrong-installed ]; then printf '3.2.0'; else printf '3.2.1ubuntu0.1'; fi ;;
esac
""",
                "dpkg-deb": r"""package=${2##*/}; package=${package%.deb}
case "$3" in
    Package) if [ "$PST_APT_MODE" = wrong-package ]; then printf 'other'; else printf '%s' "$package"; fi ;;
    Version)
        if [ "$PST_APT_MODE" = wrong-version ] && [ "$package" = libapt-pkg7.0 ]; then printf '3.2.0'; else printf '3.2.1ubuntu0.1'; fi ;;
    Architecture) if [ "$PST_APT_MODE" = wrong-arch ]; then printf 'arm64'; else printf 'amd64'; fi ;;
    *) exit 99 ;;
esac
""",
                "dpkg": r"""printf '%s\n' "$*" >> "$PST_APT_LOG"
if [ "$PST_APT_MODE" = install-failure ]; then exit 1; fi
""",
            }
            for command, body in commands.items():
                target = root / command
                target.write_text("#!/bin/sh\nset -eu\n" + body, encoding="utf-8", newline="\n")
                target.chmod(0o755)
            # Commands with hyphens are portable executable names, not POSIX
            # shell function names. Use the same /bin/sh path as the builders.
            bin_path = root.as_posix()
            if os.name == "nt":
                bin_path = "/" + root.drive[0].lower() + bin_path[2:]
            env = dict(os.environ, PST_APT_MODE=mode, PST_APT_LOG=log.as_posix(), PST_APT_BIN=bin_path)
            script = 'set -eu\nPATH="$PST_APT_BIN"\nexport PATH\n' + production
            script += '\nprintf "%s\\n" first-apt-operation >> "$PST_APT_LOG"\n'
            result = subprocess.run([shell(), "-c", script], env=env, capture_output=True, timeout=10)
            self.assertEqual(b"", result.stderr, "A harness/shell error must never count as a valid denial")
            return result.returncode, log.read_text(encoding="utf-8").splitlines() if log.exists() else []

    def test_only_exact_official_two_package_pins_and_existing_snapshot_are_used(self):
        lock = (REPO / "ubuntu-security-packages.lock").read_text(encoding="utf-8")
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                source, production = block(name)
                for package, digest in PACKAGES.items():
                    expected = "ADD --checksum=sha256:" + digest + " https://security.ubuntu.com/ubuntu/pool/main/a/apt/" + package + "_" + VERSION + "_amd64.deb /tmp/" + package + ".deb"
                    self.assertEqual(1, source.splitlines().count(expected))
                    self.assertIn(package + "=" + VERSION + "\n", lock)
                self.assertIn('Version)" = ' + VERSION, production)
                self.assertIn('Architecture)" = amd64', production)
                self.assertIn("${db:Status-Status}", production)
                self.assertIn("${Version}", production)
                self.assertLess(source.index(production), source.index("    apt-get update;"))
                self.assertIn("Acquire::https::Verify-Peer", source)
                self.assertNotIn("--allow-downgrades", production)
                self.assertNotIn("--force", production)

    def test_verified_pair_installs_before_any_apt_operation(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                self.assertEqual((0, ["-i /tmp/libapt-pkg7.0.deb /tmp/apt.deb", "first-apt-operation"]), self.run_block(name, "valid"))

    def test_wrong_metadata_or_missing_installed_package_never_installs_or_calls_apt(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            for mode in ("wrong-package", "wrong-version", "wrong-arch", "missing-installed"):
                with self.subTest(builder=name, mode=mode):
                    code, calls = self.run_block(name, mode)
                    self.assertNotEqual(0, code)
                    self.assertEqual([], calls)

    def test_install_or_installed_version_failure_never_calls_apt(self):
        for name in ("Dockerfile", "Dockerfile.dapper"):
            for mode in ("install-failure", "wrong-installed"):
                with self.subTest(builder=name, mode=mode):
                    code, calls = self.run_block(name, mode)
                    self.assertNotEqual(0, code)
                    self.assertEqual(["-i /tmp/libapt-pkg7.0.deb /tmp/apt.deb"], calls)


if __name__ == "__main__":
    unittest.main()
