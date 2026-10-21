#!/usr/bin/env python3
"""Offline POSIX-sh parser/transport tests; no APT, network or root is used."""
import hashlib
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
SOURCE = (ROOT / 'scripts/install-snapshot-packages').read_text(encoding='utf-8')
# Linux CI uses the platform shell, not a caller-supplied executable path.
# Windows resolves the fixed command name from its normal tool installation.
SH = shutil.which('sh') if os.name == 'nt' else '/bin/sh'
GOOD = b'good package\n'
BAD = b'evil package\n'
HASH = hashlib.sha256(GOOD).hexdigest()


def locked_origin(setting):
    lock = (ROOT / 'ubuntu-apt.lock').read_text(encoding='utf-8')
    values = re.findall(r"^" + re.escape(setting) + r"='([0-9]{8}T[0-9]{6}Z)'$", lock, re.MULTILINE)
    if len(values) != 1:
        raise ValueError('Expected one fixed snapshot: ' + setting)
    return 'https://snapshot.ubuntu.com/ubuntu/' + values[0] + '/'


SNAPSHOT = locked_origin('UBUNTU_APT_SNAPSHOT')
SECURITY = locked_origin('UBUNTU_APT_SECURITY_SNAPSHOT')
POOL = 'pool/main/u/unzip/unzip_6.0-29ubuntu1_amd64.deb'


class SnapshotPackagesTest(unittest.TestCase):
    def setUp(self):
        if not SH:
            self.fail('POSIX sh required on PATH')
        self.tmp = tempfile.TemporaryDirectory(prefix='snapshot-packages-test-')
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.bin = self.home / 'bin'
        self.bin.mkdir()
        self.cache = self.home / 'archives'
        self.plan = self.home / 'plan'
        self.write_plan(self.uri_line())
        self.good = self.home / 'good.deb'
        self.bad = self.home / 'bad.deb'
        self.good.write_bytes(GOOD)
        self.bad.write_bytes(BAD)
        self.apt_log = self.home / 'apt.log'
        self.helper_log = self.home / 'helper.log'
        self.installed = self.home / 'installed'
        self.chown_log = self.home / 'chown.log'
        self.args = ['install', '-y', '--no-install-recommends', 'unzip=6.0-29ubuntu1']
        self.make_script('apt-get', r'''#!/bin/sh
set -eu
printf 'BEGIN\n' >> "$TEST_APT_LOG"
printf '%s\n' "$@" >> "$TEST_APT_LOG"
case " $* " in
    *' --print-uris '*) cat "$TEST_PLAN"; exit "$TEST_PLAN_STATUS" ;;
    *' --no-download '*) : > "$TEST_INSTALLED" ;;
    *) exit 91 ;;
esac
''')
        self.make_script('chown', r'''#!/bin/sh
set -eu
[ "$#" = 2 ] && [ "$1" = _apt ] && [ "${2##*/}" = payload ] || exit 95
printf '%s\n' "$2" > "$TEST_CHOWN_LOG"
''')
        if os.name == 'nt':
            # The bundled Windows sh omits sha256sum. Keep this test-only
            # primitive real (hashlib), while Linux/CI exercises coreutils.
            program = ('import hashlib, pathlib, sys; assert len(sys.argv) == 2; '
                       'print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()'
                       ' + "  " + sys.argv[1])')
            self.make_script('sha256sum', '#!/bin/sh\nexec ' +
                shlex.quote(Path(sys.executable).as_posix()) + ' -c ' + shlex.quote(program) + ' "$@"\n')
            # Windows cannot model the production POSIX owner/mode boundary.
            # Check the exact permission calls here; Linux uses real coreutils.
            self.make_script('chmod', '#!/bin/sh\n[ "$#" = 2 ] && [ -e "$2" ] || exit 97\n'
                'case "$1" in 0711|0644) ;; *) exit 98 ;; esac\n')
            real_mkdir = Path(SH).resolve().parent / 'mkdir.exe'
            self.make_script('mkdir', '#!/bin/sh\nif [ "$1" = -m ]; then\n'
                '    [ "$2" = 0700 ] || exit 99; shift 2\nfi\nexec ' +
                shlex.quote(real_mkdir.as_posix()) + ' "$@"\n')
        helper = self.make_script('apt-helper-mock', r'''#!/bin/sh
set -eu
while [ "$1" = -o ]; do shift 2; done
[ "$1" = download-file ] && [ "$#" = 4 ] || exit 92
[ "${3%/*}" = "$(cat "$TEST_CHOWN_LOG")" ] || exit 96
printf '%s|%s\n' "$2" "$4" >> "$TEST_HELPER_LOG"
case "$2" in
    https://archive.ubuntu.com/ubuntu/*) mode=$TEST_ARCHIVE_MODE ;;
    https://snapshot.ubuntu.com/ubuntu/*) mode=$TEST_SNAPSHOT_MODE ;;
    *) exit 93 ;;
esac
case "$mode" in
    fail) exit 22 ;;
    bad) cp "$TEST_BAD_PAYLOAD" "$3" ;;
    ok) cp "$TEST_GOOD_PAYLOAD" "$3" ;;
    *) exit 94 ;;
esac
''')
        # Only fixture paths are substituted in an isolated copy. Production
        # has no configurable apt-helper, cache or lock-file escape hatch.
        script = SOURCE
        for old, new in (
                ('. /licenses/ubuntu-apt.lock', '. ' + shlex.quote((ROOT / 'ubuntu-apt.lock').as_posix())),
                ('archives=/var/cache/apt/archives', 'archives=' + shlex.quote(self.cache.as_posix())),
                ('/usr/lib/apt/apt-helper', shlex.quote(helper.as_posix()))):
            self.assertEqual(1, script.count(old))
            script = script.replace(old, new)
        self.script = self.home / 'install-snapshot-packages'
        self.script.write_text(script, encoding='utf-8', newline='\n')

    @staticmethod
    def uri_line(uri=SNAPSHOT + POOL, filename=POOL.rsplit('/', 1)[1], size=len(GOOD), digest=HASH):
        return f"'{uri}' {filename} {size} SHA256:{digest}\n"

    def make_script(self, name, value):
        path = self.bin / name
        path.write_text(value, encoding='utf-8', newline='\n')
        path.chmod(0o755)
        return path

    def write_plan(self, value):
        self.plan.write_text(value, encoding='utf-8', newline='\n')

    @staticmethod
    def shell_path(path):
        value = Path(path).as_posix()
        if os.name == 'nt' and value[1:3] == ':/':
            return '/' + value[0].lower() + value[2:]
        return value

    def run_helper(self, *, archive='ok', snapshot='ok', plan_status=0, args=None):
        for path in (self.apt_log, self.helper_log, self.installed, self.chown_log):
            path.unlink(missing_ok=True)
        paths = [self.shell_path(self.bin), self.shell_path(Path(SH).resolve().parent)]
        paths.extend(['/usr/bin', '/bin'] if os.name == 'nt' else [os.environ['PATH']])
        env = dict(os.environ, PATH=':'.join(paths),
            TEST_APT_LOG=self.apt_log.as_posix(), TEST_HELPER_LOG=self.helper_log.as_posix(),
            TEST_PLAN=self.plan.as_posix(), TEST_INSTALLED=self.installed.as_posix(),
            TEST_CHOWN_LOG=self.chown_log.as_posix(),
            TEST_GOOD_PAYLOAD=self.good.as_posix(), TEST_BAD_PAYLOAD=self.bad.as_posix(),
            TEST_ARCHIVE_MODE=archive, TEST_SNAPSHOT_MODE=snapshot, TEST_PLAN_STATUS=str(plan_status))
        return subprocess.run([SH, self.script.as_posix(), *(args or self.args)], env=env,
                              capture_output=True, text=True, timeout=15)

    def apt_calls(self):
        return [part.splitlines() for part in self.apt_log.read_text().split('BEGIN\n')[1:]]

    def downloads(self):
        return self.helper_log.read_text().splitlines() if self.helper_log.exists() else []

    def assert_rejected(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(self.installed.exists())

    def test_posix_syntax_and_fixed_production_transport(self):
        result = subprocess.run([SH, '-n', str(ROOT / 'scripts/install-snapshot-packages')],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('/usr/lib/apt/apt-helper -o Acquire::Retries=1', SOURCE)
        self.assertIn('Acquire::https::Timeout=15', SOURCE)
        self.assertNotIn('APT_HELPER', SOURCE)
        self.assertNotIn('python', SOURCE)
        self.assertIn('chmod 0711 "$work"', SOURCE)
        self.assertIn('mkdir -m 0700 "$work/payload"', SOURCE)
        self.assertIn('chown _apt "$work/payload"', SOURCE)
        self.assertIn('cache_copy=$(mktemp "$archives/.snapshot-package.XXXXXX")', SOURCE)
        self.assertIn('cp -- "$work/payload/package.deb" "$cache_copy"', SOURCE)
        self.assertIn('verified "$destination" || fail cache_verification_failed', SOURCE)
        docker = (ROOT / 'Dockerfile.dapper').read_text()
        self.assertIn('ENV LANG=C.UTF-8 LC_ALL=C.UTF-8', docker)
        self.assertIn('COPY --chmod=0755 scripts/install-snapshot-packages /usr/local/bin/install-snapshot-packages', docker)
        self.assertEqual(3, docker.count('    install-snapshot-packages '))
        self.assertEqual(2, docker.count('    apt-get update;'))
        self.assertIn('Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg', docker)
        self.assertIn('Acquire::https::Verify-Peer "true"', docker)
        self.assertIn('Acquire::https::Verify-Host "true"', docker)

    def test_official_archive_first_and_exact_no_download_args(self):
        self.write_plan('Reading package lists...\n  unzip\n' + self.uri_line())
        result = self.run_helper()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{POOL}|SHA256:{HASH}'], self.downloads())
        self.assertEqual(GOOD, (self.cache / POOL.rsplit('/', 1)[1]).read_bytes())
        calls = self.apt_calls()
        self.assertEqual(2, len(calls))
        self.assertIn('Acquire::ForceHash=SHA256', calls[0])
        self.assertIn('--print-uris', calls[0])
        self.assertIn('--download-only', calls[0])
        self.assertIn('--no-download', calls[1])
        self.assertEqual(self.args, calls[0][-len(self.args):])
        self.assertEqual(self.args, calls[1][-len(self.args):])

    def test_official_transport_failure_falls_back_only_to_original_snapshot(self):
        result = self.run_helper(archive='fail')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{POOL}|SHA256:{HASH}',
                          f'{SNAPSHOT}{POOL}|SHA256:{HASH}'], self.downloads())

    def test_bad_archive_hash_falls_back_and_never_uses_corrupt_payload(self):
        result = self.run_helper(archive='bad')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(self.downloads()))
        self.assertEqual(GOOD, (self.cache / POOL.rsplit('/', 1)[1]).read_bytes())

    def test_bad_hash_on_both_origins_cannot_install(self):
        result = self.run_helper(archive='bad', snapshot='bad')
        self.assert_rejected(result)
        self.assertEqual(2, len(self.downloads()))
        self.assertEqual(1, len(self.apt_calls()))
        self.assertFalse(list(self.cache.glob('*.deb')))

    def test_correct_hash_but_wrong_declared_size_cannot_install(self):
        self.write_plan(self.uri_line(size=len(GOOD) + 1))
        result = self.run_helper()
        self.assert_rejected(result)
        self.assertEqual(2, len(self.downloads()))

    def test_both_transport_failures_cannot_install(self):
        result = self.run_helper(archive='fail', snapshot='fail')
        self.assert_rejected(result)
        self.assertEqual(2, len(self.downloads()))

    def test_illegal_sources_and_paths_reject_before_any_download(self):
        invalid = (
            SNAPSHOT.replace('https:', 'http:') + POOL,
            SNAPSHOT.replace('snapshot.ubuntu.com', 'example.com') + POOL,
            'https://snapshot.ubuntu.com/ubuntu/19000101T000000Z/' + POOL,
            'https://archive.ubuntu.com/ubuntu/' + POOL,
            SNAPSHOT + POOL.replace('/main/', '/restricted/'),
            SNAPSHOT + POOL.replace('/main/', '/multiverse/'),
            SNAPSHOT + POOL + '?token=forbidden',
            SNAPSHOT + POOL + '#fragment',
            SNAPSHOT + POOL.replace('/u/unzip/', '/u/../'),
            SNAPSHOT + POOL.replace('unzip_6', 'unzip%2f6'),
            SNAPSHOT + POOL.replace('unzip_6', '%2e%2e_6'),
        )
        for uri in invalid:
            with self.subTest(uri=uri):
                self.write_plan(self.uri_line() + self.uri_line(uri=uri))
                result = self.run_helper()
                self.assert_rejected(result)
                self.assertEqual([], self.downloads())

    def test_malformed_plan_hash_filename_or_size_rejects_before_download(self):
        invalid = (
            self.uri_line().replace('SHA256:', 'MD5Sum:'),
            self.uri_line(digest='f' * 63), self.uri_line(digest='g' * 64),
            self.uri_line(size=0), self.uri_line(size='-1'), self.uri_line(size='1.0'),
            self.uri_line(filename='../unzip.deb'), self.uri_line(filename='other.deb'),
            self.uri_line().rstrip() + ' extra\n', self.uri_line().replace("'", ''),
            self.uri_line() + self.uri_line(),
        )
        for plan in invalid:
            with self.subTest(plan=plan):
                self.write_plan(plan)
                result = self.run_helper()
                self.assert_rejected(result)
                self.assertEqual([], self.downloads())

    def test_security_snapshot_universe_and_escaped_leaf_are_supported(self):
        pool = 'pool/universe/p/package/package_1%3a1.0%2bbuild%7e1_all.deb'
        filename = 'package_1%3a1.0+build~1_all.deb'
        self.write_plan(self.uri_line(uri=SECURITY + pool, filename=filename))
        result = self.run_helper()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f'https://archive.ubuntu.com/ubuntu/{pool}|SHA256:{HASH}', self.downloads()[0])

    def test_apt_cache_epoch_is_not_part_of_the_official_pool_filename(self):
        # Actual apt --print-uris output keeps an escaped epoch in its cache
        # filename, while the pool URI has none. Keep the exact cache filename.
        pool = 'pool/main/g/git/git_2.53.0-1ubuntu1_amd64.deb'
        filename = 'git_1%3a2.53.0-1ubuntu1_amd64.deb'
        self.write_plan(self.uri_line(uri=SNAPSHOT + pool, filename=filename))
        result = self.run_helper()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{pool}|SHA256:{HASH}'], self.downloads())
        self.assertEqual(GOOD, (self.cache / filename).read_bytes())

    def test_epoch_mapping_cannot_change_the_package_version_or_architecture(self):
        pool = 'pool/main/g/git/git_2.53.0-1ubuntu1_amd64.deb'
        for filename in ('git_1%3a2.53.0-1ubuntu2_amd64.deb',
                         'git_1%3a2.53.0-1ubuntu1_arm64.deb',
                         'other_1%3a2.53.0-1ubuntu1_amd64.deb',
                         'git_bad%3a2.53.0-1ubuntu1_amd64.deb'):
            with self.subTest(filename=filename):
                self.write_plan(self.uri_line(uri=SNAPSHOT + pool, filename=filename))
                self.assert_rejected(self.run_helper())
                self.assertEqual([], self.downloads())

    def test_valid_cached_payload_is_checked_and_does_not_redownload(self):
        self.cache.mkdir()
        (self.cache / POOL.rsplit('/', 1)[1]).write_bytes(GOOD)
        result = self.run_helper(archive='fail', snapshot='fail')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.downloads())
        self.assertTrue(self.installed.exists())

    def test_failed_plan_or_unsupported_options_cannot_install(self):
        self.assert_rejected(self.run_helper(plan_status=42))
        self.assertEqual([], self.downloads())
        self.assert_rejected(self.run_helper(args=['install', '--allow-unauthenticated', 'unzip']))
        self.assertEqual([], self.downloads())

    def test_upgrade_and_empty_plan_keep_original_operation(self):
        self.write_plan('Reading package lists...\n0 upgraded, 0 newly installed.\n')
        result = self.run_helper(args=['upgrade', '-y'])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.downloads())
        self.assertEqual(['upgrade', '-y'], self.apt_calls()[1][-2:])


if __name__ == '__main__':
    unittest.main(verbosity=2)
