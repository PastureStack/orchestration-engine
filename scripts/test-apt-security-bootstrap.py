#!/usr/bin/env python3
"""Exercise the exact APT bootstrap security boundary without network or installation."""
import os
from pathlib import Path
import re
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
    def test_every_index_update_fails_closed_before_install_on_warning_only_failure(self):
        commands = set()
        for name, expected in (("Dockerfile", 3), ("Dockerfile.dapper", 3),
                               ("scripts/install-ubuntu-security-updates.sh", 1)):
            text = (REPO / name).read_text(encoding="utf-8")
            calls = re.findall(r"^\s+((?:apt-get|apt_security) update[^;\n]*)(?:;|\n)", text, re.M)
            self.assertEqual(expected, len(calls), name)
            for call in calls:
                owner = call.split()[0]
                self.assertEqual(owner + " update --error-on=any", call)
                commands.add(call)
        self.assertEqual(2, len(commands))
        # Model APT's documented warning-only result independently from the
        # production call sites. Every real call must convert it into a fatal
        # status before any following installation can execute.
        stub = r'''#!/bin/sh
set -eu
printf '%s\n' "$*" >> calls
if test "$1" = update; then
    test "$APT_UPDATE_FIXTURE_INDEX_OK" = 0 || exit 0
    printf 'W: simulated unavailable signed index\n' >&2
    for argument do
        test "$argument" != --error-on=any || exit 100
    done
    exit 0
fi
test "$1" = install
printf 'INSTALL_REACHED\n' >> calls
'''
        for command in sorted(commands):
            for mode in ("strict-failure", "original-warning-only", "strict-success"):
                with self.subTest(command=command, mode=mode), tempfile.TemporaryDirectory() as directory:
                    executable = Path(directory) / "bin" / "apt-get"
                    executable.parent.mkdir()
                    executable.write_text(stub, encoding="utf-8", newline="\n")
                    executable.chmod(0o755)
                    actual = command.replace(" --error-on=any", "") if mode == "original-warning-only" else command
                    script = ('set -eu\nPATH="$PWD/bin:$PATH"\nexport PATH\n'
                              'test "$(command -v apt-get)" = "$PWD/bin/apt-get"\n'
                              'apt_security() { apt-get "$@"; }\n' + actual +
                              "\napt-get install fixture-only\n")
                    environment = dict(os.environ,
                                       APT_UPDATE_FIXTURE_INDEX_OK="1" if mode == "strict-success" else "0")
                    result = subprocess.run([shell(), "-c", script], cwd=directory,
                                            env=environment, capture_output=True, text=True, timeout=10)
                    calls = (Path(directory) / "calls").read_text().splitlines()
                    self.assertEqual("" if mode == "strict-success" else
                                     "W: simulated unavailable signed index\n", result.stderr)
                    self.assertEqual(100 if mode == "strict-failure" else 0, result.returncode)
                    self.assertEqual(mode != "strict-failure", "INSTALL_REACHED" in calls)

    def test_existing_openssl_security_overlay_precedes_base_upgrade_without_changing_its_scope(self):
        lock = (REPO / "ubuntu-apt.lock").read_text(encoding="utf-8")
        version = re.search(r"^UBUNTU_APT_OPENSSL_VERSION='([^']+)'$", lock, re.M).group(1)
        security = (REPO / "ubuntu-security-packages.lock").read_text(encoding="utf-8")
        for package in ("libssl3t64", "openssl", "openssl-provider-legacy"):
            self.assertIn(package + "=" + version + "\n", security)
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                source = (REPO / name).read_text(encoding="utf-8")
                tool = "install-snapshot-packages"
                install = tool + " install -y --no-install-recommends \\\n        libssl3t64=\"${UBUNTU_APT_OPENSSL_VERSION}\""
                upgrade = "install-snapshot-packages" + " upgrade -y;"
                def verify(text):
                    at = text.index(install)
                    self.assertLess(at, text.index(upgrade))
                    # Upgrade sees only the original base indexes. The same
                    # existing security source is restored afterwards.
                    remove = text.index("    rm -f /etc/apt/sources.list.d/pasturestack-security.sources;", at)
                    self.assertLess(remove, text.index(upgrade))
                    self.assertIn("    apt-get update --error-on=any;", text[remove:text.index(upgrade)])
                    self.assertEqual(2, text.count('"${UBUNTU_APT_SECURITY_SNAPSHOT}" > /etc/apt/sources.list.d/pasturestack-security.sources;'))
                    for package in ("libssl3t64", "openssl", "openssl-provider-legacy"):
                        self.assertIn(package + '=\"${UBUNTU_APT_OPENSSL_VERSION}\"', text[at:remove])
                    self.assertIn("dpkg-query -W", text[at:remove])
                    self.assertIn("dpkg-query -W", text[text.index(upgrade):])
                    self.assertNotIn("--allow-downgrades", text)
                    self.assertNotIn("--force", text[at:remove])
                verify(source)
                # The original failure order must remain a failing contract.
                before = source[:source.index(upgrade)]
                after = source[source.index(upgrade):]
                with self.assertRaises(AssertionError):
                    verify(before.replace(install, "old-late-install", 1) + after + install)

    def test_existing_final_package_family_bootstraps_the_original_base_with_native_apt(self):
        packages = ("libpython3.14-minimal libpython3.14-stdlib python3.14-minimal "
                    "python3.14 python3.14-venv curl libcurl3t64-gnutls libcurl4t64 "
                    "ca-certificates libbrotli1 libpcre2-8-0 libpcre2-posix3")
        lock = (REPO / "ubuntu-apt.lock").read_text(encoding="utf-8")
        for field, version in {"PYTHON3_14": "3.14.4-1ubuntu0.2",
                               "CURL_BOOTSTRAP": "8.18.0-1ubuntu2.8",
                               "CURL": "8.18.0-1ubuntu2.8",
                               "CA_CERTIFICATES": "20260816ubuntu0.26.04.1"}.items():
            self.assertIn("UBUNTU_APT_" + field + "_VERSION='" + version + "'", lock)
        gate = (REPO / "scripts/check-pasturestack-source").read_text(encoding="utf-8")
        for field, version in {"PYTHON3_14": "3.14.4-1ubuntu0.2",
                               "CURL_BOOTSTRAP": "8.18.0-1ubuntu2.8"}.items():
            self.assertIn('require_line ubuntu-apt.lock "UBUNTU_APT_' + field + "_VERSION='" + version + "'\"", gate)
        for name in ("Dockerfile", "Dockerfile.dapper"):
            with self.subTest(builder=name):
                source = (REPO / name).read_text(encoding="utf-8")
                upgrade = "install-snapshot-packages" + " upgrade -y;"
                local_start = "    set --; \\\n    for package in " + packages + "; do"
                start = source.index(local_start)
                stop = source.index(upgrade)
                def verify(text):
                    self.assertIn(local_start, text)
                    begin = text.index(local_start)
                    end = text.index(upgrade)
                    self.assertLess(begin, end)
                    phase = text[begin:end]
                    self.assertEqual(2, phase.count("for package in " + packages + "; do"))
                    for package in packages.split():
                        if package == "ca-certificates":
                            self.assertIn("ADD --checksum=sha256:5b6326f1932cfd7be57130561dc78192f09ed9f58bfbe6606ed0c50510c3b6cd "
                                          "https://launchpadlibrarian.net/881832632/"
                                          "ca-certificates_20260816ubuntu0.26.04.1_all.deb /tmp/ca-certificates.deb\n", text)
                        else:
                            self.assertRegex(text, r"ADD --checksum=sha256:[0-9a-f]{64} https://(?:security|archive)\.ubuntu\.com/[^\n]+ /tmp/" + re.escape(package) + r"\.deb\n")
                    install = phase.index('install-snapshot-packages install -y --no-install-recommends "$@";')
                    self.assertIn('COPY --chmod=0755 scripts/install-snapshot-packages /usr/local/bin/install-snapshot-packages', text)
                    helper = (REPO / "scripts/install-snapshot-packages").read_text(encoding="utf-8")
                    self.assertIn('apt-get -o "Dir::Cache::archives=$archives" --no-download "$@"', helper)
                    for field in ("Package", "Version", "Architecture"):
                        self.assertLess(phase.index('.deb" ' + field), install)
                    self.assertIn("dpkg-query -W", phase[install:])
                    self.assertIn("architecture=all", phase)
                    self.assertIn("version=1.2.0-3ubuntu0.1", phase)
                    self.assertIn("version=10.46-1ubuntu0.1", phase)
                    reset = text.rindex("rm -f /etc/apt/sources.list.d/pasturestack-security.sources;", 0, begin)
                    self.assertIn("apt-get update --error-on=any;", text[reset:begin])
                    self.assertNotIn("--force", phase)
                    self.assertNotIn("--allow-downgrades", text)
                verify(source)
                for old, changed in (("https://launchpadlibrarian.net/", "https://untrusted.invalid/"),
                                     ("881832632/ca-certificates_", "881832633/ca-certificates_"),
                                     ("5b6326f1932cfd7be57130561dc78192f09ed9f58bfbe6606ed0c50510c3b6cd", "0" * 64)):
                    self.assertIn(old, source)
                    with self.assertRaises(AssertionError):
                        verify(source.replace(old, changed, 1))
                with self.assertRaises(AssertionError):
                    verify(source[:start] + source[stop:] + source[start:stop])
                with self.assertRaises(AssertionError):
                    verify(source.replace("python3.14 python3.14-venv curl", "python3.14 curl", 1))

    def test_only_three_missing_final_security_dependencies_join_the_original_early_phase(self):
        packages = "libexpat1 libsqlite3-0 libssh2-1t64"
        marker = "    set --; \\\n    for package in " + packages + "; do"
        lock = (REPO / "ubuntu-security-packages.lock").read_text(encoding="utf-8")
        versions = dict(line.split("=", 1) for line in lock.splitlines()
                        if line and not line.startswith("#"))
        for name in ("Dockerfile", "Dockerfile.dapper"):
            source = (REPO / name).read_text(encoding="utf-8")
            tool = "install-snapshot-packages"

            def verify(text):
                self.assertIn(marker, text)
                start = text.index(marker)
                end = text.index("    rm -f /etc/apt/sources.list.d/pasturestack-security.sources;", start)
                phase = text[start:end]
                self.assertIn('test "$(grep -c "^${package}=" /licenses/ubuntu-security-packages.lock)" -eq 1;', phase)
                self.assertIn('entry=$(grep "^${package}=" /licenses/ubuntu-security-packages.lock);', phase)
                self.assertIn('set -- "$@" "$entry";', phase)
                self.assertEqual(1, phase.count(tool + " install -y --no-install-recommends"))
                self.assertIn('openssl-provider-legacy="${UBUNTU_APT_OPENSSL_VERSION}" "$@";', phase)
                self.assertIn('for entry in "$@"; do', phase)
                self.assertIn('"${entry%%=*}")" = "${entry#*=}";', phase)
                self.assertLess(end, text.index("install-snapshot-packages" + " upgrade -y;"))
                return phase

            phase = verify(source)
            # Neither all-overlay promotion nor a later install is equivalent.
            with self.assertRaises(AssertionError):
                verify(source.replace("for package in " + packages + "; do",
                                      "for package in " + packages + " libc6; do", 1))
            with self.assertRaises(AssertionError):
                verify(source.replace(marker, "    set --; \\\n    for package in $(cat /licenses/ubuntu-security-packages.lock); do", 1))
            for mode in ("ok", "missing", "duplicate", "wrong-readback"):
                with self.subTest(builder=name, mode=mode), tempfile.TemporaryDirectory(prefix="engine-final-deps-") as directory:
                    root = Path(directory)
                    calls = root / "calls"
                    fixture_lock = root / "security.lock"
                    value = lock
                    if mode == "missing":
                        value = value.replace("libsqlite3-0=" + versions["libsqlite3-0"] + "\n", "")
                    if mode == "duplicate":
                        value += "libsqlite3-0=" + versions["libsqlite3-0"] + "\n"
                    fixture_lock.write_text(value, encoding="utf-8", newline="\n")
                    for command in ("apt-get", "install-snapshot-packages"):
                        path = root / command
                        path.write_text('#!/bin/sh\nset -eu\nprintf "%s\\n" "$@" > "$PST_FINAL_CALLS"\n', encoding="utf-8", newline="\n")
                        path.chmod(0o755)
                    path = root / "dpkg-query"
                    path.write_text('''#!/bin/sh
set -eu
[ "$#" = 3 ] && [ "$1" = -W ] || exit 91
if [ "$PST_FINAL_MODE" = wrong-readback ] && [ "$3" = libsqlite3-0 ]; then
    printf '3.46.1-9ubuntu0.2\n'
else
    entry=$(grep "^$3=" "$PST_FINAL_LOCK")
    printf '%s\n' "${entry#*=}"
fi
''', encoding="utf-8", newline="\n")
                    path.chmod(0o755)
                    shell_path = root.as_posix()
                    system_bin = Path(shell()).resolve().parent.as_posix()
                    if os.name == "nt":
                        shell_path = "/" + root.drive[0].lower() + shell_path[2:]
                        system_bin = "/" + system_bin[0].lower() + system_bin[2:]
                    env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                               PST_FINAL_MODE=mode, PST_FINAL_CALLS=calls.as_posix(),
                               PST_FINAL_LOCK=fixture_lock.as_posix(), PST_FINAL_BIN=shell_path,
                               PST_FINAL_SYSTEM_BIN=system_bin)
                    script = 'set -eu\nPATH="$PST_FINAL_BIN:$PST_FINAL_SYSTEM_BIN:$PATH"\nexport PATH\nUBUNTU_APT_OPENSSL_VERSION=' + versions["openssl"] + "\n"
                    script += phase.replace("/licenses/ubuntu-security-packages.lock", '"' + fixture_lock.as_posix() + '"')
                    result = subprocess.run([shell(), "-c", script], env=env, capture_output=True, timeout=15)
                    self.assertEqual(b"", result.stderr)
                    if mode == "ok":
                        self.assertEqual(0, result.returncode)
                        arguments = calls.read_text().splitlines()
                        expected = {p + "=" + versions[p] for p in packages.split()}
                        expected.update(p + "=" + versions[p] for p in ("libssl3t64", "openssl", "openssl-provider-legacy"))
                        self.assertEqual(expected, set(arguments[3:]))
                        self.assertEqual(6, len(arguments[3:]))
                    else:
                        self.assertNotEqual(0, result.returncode)
                        self.assertEqual(mode == "wrong-readback", calls.exists())

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
                self.assertLess(source.index(production), source.index("    apt-get update --error-on=any;"))
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



    def test_final_security_overlay_shares_private_native_config_and_rejects_package_set_changes(self):
        source = (REPO / 'scripts/install-ubuntu-security-updates.sh').read_text()
        def posix(path):
            value = Path(path).as_posix()
            return '/' + value[0].lower() + value[2:] if os.name == 'nt' and value[1:3] == ':/' else value
        def execute(mode, unsafe_ca=False):
            with tempfile.TemporaryDirectory(prefix='final-security-transport-') as directory:
                home = Path(directory).resolve()
                binary = home / 'bin'
                binary.mkdir()
                work = home / 'work'
                work.mkdir()
                release = home / 'os-release'
                release.write_text('ID=ubuntu\nVERSION_ID=26.04\n', newline='\n')
                lock = home / 'security.lock'
                lock.write_text('existing-package=2.0\nabsent-package=2.0\n', newline='\n')
                ca = home / ('unsafe ca.crt' if unsafe_ca else 'ca.crt')
                ca.write_text('fixture CA bytes\n', newline='\n')
                def executable(name, text):
                    path = binary / name
                    path.write_text(text, encoding='utf-8', newline='\n')
                    path.chmod(0o755)
                if os.name == 'nt':
                    # Model Linux mktemp's absolute POSIX path; Git's native
                    # Windows utility otherwise returns a drive-letter path.
                    executable('mktemp', '''#!/bin/sh
set -eu
[ "$#" = 1 ] && [ "$1" = -d ] && [ "$TMPDIR" = "$TEST_HOME/work" ] || exit 93
mkdir "$TMPDIR/private"
printf '%s\\n' "$TMPDIR/private"
''')
                executable('dpkg-query', '''#!/bin/sh
set -eu
if [ "$#" = 2 ]; then
    status=installed
    if [ -f "$TEST_HOME/after" ] && [ "$TEST_MODE" = removed ]; then status=removed; fi
    printf 'existing-package\\t%s\\n' "$status"
    if [ -f "$TEST_HOME/after" ] && [ "$TEST_MODE" = added ]; then printf 'unexpected-package\\tinstalled\\n'; fi
elif [ "$3" = absent-package ]; then
    exit 1
else
    case "$2" in
        *db:Status-Status*) printf 'installed\\n' ;;
        *) if [ -f "$TEST_HOME/after" ]; then printf '2.0\\n'; else printf '1.0\\n'; fi ;;
    esac
fi
''')
                executable('dpkg', '''#!/bin/sh
set -eu
[ "$#" = 4 ] && [ "$1" = --compare-versions ] && [ "$3" = ge ] && [ "$4" = 2.0 ] || exit 90
[ "$2" = 2.0 ]
''')
                executable('apt-get', '''#!/bin/sh
set -eu
[ "$#" = 2 ] && [ "$1" = update ] && [ "$2" = --error-on=any ] || exit 91
[ -s "$APT_CONFIG" ]
printf '%s\\n' update > "$TEST_HOME/update"
cp "$APT_CONFIG" "$TEST_HOME/observed-config"
cp "${APT_CONFIG%/*}/security.sources" "$TEST_HOME/observed-sources"
''')
                executable('install-snapshot-packages', '''#!/bin/sh
set -eu
[ "$DEBIAN_FRONTEND" = noninteractive ]
cmp "$APT_CONFIG" "$TEST_HOME/observed-config"
printf '%s\\n' "$@" > "$TEST_HOME/argv"
[ "$#" = 6 ] && [ "$1" = install ] && [ "$2" = -y ] && [ "$3" = --only-upgrade ] && [ "$4" = --no-install-recommends ] && [ "$5" = --no-remove ] && [ "$6" = existing-package=2.0 ] || exit 92
[ "$TEST_MODE" != transport-failure ] || exit 47
: > "$TEST_HOME/after"
''')
                script = home / 'security-updates.sh'
                self.assertEqual(1, source.count('. /etc/os-release'))
                script.write_text(source.replace('. /etc/os-release', '. ' + posix(release)), encoding='utf-8', newline='\n')
                env = dict(os.environ, PATH=':'.join([posix(binary), posix(Path(shell()).resolve().parent), '/usr/bin', '/bin']),
                           TMPDIR=posix(work), TEST_HOME=posix(home), TEST_MODE=mode)
                result = subprocess.run([shell(), str(script), posix(lock), posix(ca)], env=env,
                                        capture_output=True, text=True, timeout=15)
                self.assertEqual([], list(work.iterdir()), 'Every outcome cleans its own private APT directory')
                if unsafe_ca:
                    self.assertFalse((home / 'update').exists())
                    self.assertFalse((home / 'argv').exists())
                else:
                    self.assertTrue((home / 'argv').exists(), result.stdout + result.stderr)
                    self.assertEqual(['install', '-y', '--only-upgrade', '--no-install-recommends', '--no-remove',
                                      'existing-package=2.0'], (home / 'argv').read_text().splitlines())
                    config = (home / 'observed-config').read_text()
                    self.assertIn('Dir::Etc::sourceparts "-";', config)
                    self.assertIn('Acquire::https::CaInfo "' + posix(ca) + '";', config)
                    for key in ('Acquire::https::Verify-Peer', 'Acquire::https::Verify-Host'):
                        self.assertIn(key + ' "true";', config)
                    for key in ('Acquire::AllowInsecureRepositories', 'APT::Get::AllowUnauthenticated'):
                        self.assertIn(key + ' "false";', config)
                    self.assertEqual(11, len(config.splitlines()))
                    self.assertIn('https://snapshot.ubuntu.com/ubuntu/20261023T000000Z', (home / 'observed-sources').read_text())
                    self.assertIn('Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg', (home / 'observed-sources').read_text())
                return result
        self.assertEqual(0, execute('ok').returncode)
        for mode in ('transport-failure', 'added', 'removed'):
            with self.subTest(mode=mode):
                self.assertNotEqual(0, execute(mode).returncode)
        self.assertNotEqual(0, execute('ok', unsafe_ca=True).returncode)

if __name__ == "__main__":
    unittest.main()
