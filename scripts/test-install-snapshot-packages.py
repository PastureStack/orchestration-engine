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
        self.local_root = self.home / 'local-inputs'
        self.local_root.mkdir()
        self.security_lock = self.home / 'security.lock'
        self.security_lock.write_bytes((ROOT / 'ubuntu-security-packages.lock').read_bytes())
        self.config_log = self.home / 'config.log'
        self.cache = self.home / 'archives'
        self.plan = self.home / 'plan'
        self.write_plan(self.uri_line())
        self.good = self.home / 'good.deb'
        self.bad = self.home / 'bad.deb'
        self.good.write_bytes(GOOD)
        self.bad.write_bytes(BAD)
        self.apt_log = self.home / 'apt.log'
        self.helper_log = self.home / 'helper.log'
        self.retained_log = self.home / 'retained.log'
        self.sleep_log = self.home / 'sleep.log'
        self.snapshot_count = self.home / 'snapshot-count'
        self.installed = self.home / 'installed'
        self.chown_log = self.home / 'chown.log'
        self.args = ['install', '-y', '--no-install-recommends', 'unzip=6.0-29ubuntu1']
        self.make_script('apt-get', r'''#!/bin/sh
set -eu
printf '%s\n' "${APT_CONFIG-}" >> "$TEST_CONFIG_LOG"
printf 'BEGIN\n' >> "$TEST_APT_LOG"
printf '%s\n' "$@" >> "$TEST_APT_LOG"
case " $* " in
    *' --print-uris '*)
        cat "$TEST_PLAN"
        if [ -n "$TEST_CORRUPT_LOCAL" ]; then printf 'changed input\n' > "$TEST_CORRUPT_LOCAL"; fi
        exit "$TEST_PLAN_STATUS" ;;
    *' --no-download '*) : > "$TEST_INSTALLED" ;;
    *) exit 91 ;;
esac
''')
        self.make_script('dpkg-deb', r'''#!/bin/sh
set -eu
[ "$#" = 3 ] && [ "$1" = -f ] || exit 96
name=${2##*/}; name=${name%.deb}
case "$3" in
    Package) printf '%s\n' "$name" ;;
    Version) printf '1.0\n' ;;
    Architecture) if [ "$name" = ca-certificates ]; then printf 'all\n'; else printf 'amd64\n'; fi ;;
    *) exit 97 ;;
esac
''')
        self.make_script('chown', r'''#!/bin/sh
set -eu
[ "$#" = 2 ] && [ "$1" = _apt ] && [ "${2##*/}" = payload ] || exit 95
printf '%s\n' "$2" > "$TEST_CHOWN_LOG"
''')
        self.make_script('sleep', r'''#!/bin/sh
set -eu
[ "$#" = 1 ] || exit 90
case "$1" in 1|2|3) ;; *) exit 90 ;; esac
printf '%s\n' "$1" >> "$TEST_SLEEP_LOG"
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
direct=false
debug=false
while [ "$1" = -o ]; do
    [ "$2" != Acquire::https::AllowRedirect=false ] || direct=true
    [ "$2" != Debug::Acquire::https=true ] || debug=true
    shift 2
done
[ "$1" = download-file ] && [ "$#" = 4 ] || exit 92
[ "${3%/*}" = "$(cat "$TEST_CHOWN_LOG")" ] || exit 96
printf '%s|%s\n' "$2" "$4" >> "$TEST_HELPER_LOG"
case "$2" in
    https://archive.ubuntu.com/ubuntu/*) mode=$TEST_ARCHIVE_MODE ;;
    https://snapshot.ubuntu.com/ubuntu/*) mode=$TEST_SNAPSHOT_MODE ;;
    https://launchpad.net/ubuntu/+archive/primary/+files/*)
        [ "$direct" = true ] && [ "$debug" = true ] || exit 98
        printf '%s\n' "$2" >> "$TEST_RETAINED_LOG"
        printf 'Answer for: %s\nHTTP/1.1 %s Fixed\r\nLocation: %s\r\n' "$2" "$TEST_RETAINED_STATUS" "$TEST_RETAINED_LOCATION" >&2
        case "$TEST_RETAINED_HEADER" in
            duplicate) printf 'Location: %s\r\n' "$TEST_RETAINED_LOCATION" >&2 ;;
            folded) printf ' continuation\r\n' >&2 ;;
            oversized) printf '%09000d' 0 >&2 ;;
        esac
        printf '\r\n' >&2
        exit 100 ;;
    https://launchpadlibrarian.net/*) [ "$direct" = true ] && [ "$debug" = false ] || exit 98; mode=$TEST_RETAINED_MODE ;;
    *) exit 93 ;;
esac
case "$mode" in
    fail) exit 22 ;;
    bad) cp "$TEST_BAD_PAYLOAD" "$3" ;;
    failed) cp "$TEST_BAD_PAYLOAD" "$3.FAILED"; exit 100 ;;
    failed-ok) cp "$TEST_BAD_PAYLOAD" "$3.FAILED"; cp "$TEST_GOOD_PAYLOAD" "$3" ;;
    transient)
        count=0
        [ ! -f "$TEST_SNAPSHOT_COUNT" ] || count=$(cat "$TEST_SNAPSHOT_COUNT")
        count=$((count + 1))
        printf '%s\n' "$count" > "$TEST_SNAPSHOT_COUNT"
        [ "$count" -gt 2 ] || exit 22
        cp "$TEST_GOOD_PAYLOAD" "$3" ;;
    ok) cp "$TEST_GOOD_PAYLOAD" "$3" ;;
    *) exit 94 ;;
esac
''')
        # Only fixture paths are substituted in an isolated copy. Production
        # has no configurable apt-helper, cache or lock-file escape hatch.
        script = SOURCE
        for old, new in (
                ('local_root=/tmp', 'local_root=' + shlex.quote(self.shell_path(self.local_root))),
                ('. /licenses/ubuntu-apt.lock', '. ' + shlex.quote((ROOT / 'ubuntu-apt.lock').as_posix())),
                ('security_lock=/licenses/ubuntu-security-packages.lock',
                 'security_lock=' + shlex.quote(self.shell_path(self.security_lock))),
                ('archives=/var/cache/apt/archives', 'archives=' + shlex.quote(self.cache.as_posix())),
                ('/usr/lib/apt/apt-helper', shlex.quote(helper.as_posix()))):
            self.assertEqual(1, script.count(old))
            script = script.replace(old, new)
        if os.name == 'nt':
            # Windows sh has no RLIMIT_FSIZE. Production Linux keeps this cap;
            # the fixture still exercises the post-read bound and closed parser.
            self.assertEqual(1, script.count('ulimit -f 32 || exit 1'))
            script = script.replace('ulimit -f 32 || exit 1', ':')
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

    def run_helper(self, *, apt_config=None, archive='ok', snapshot='ok', plan_status=0, args=None, corrupt_local=None,
                   retained='fail', retained_status=503, retained_location=None, retained_header='normal'):
        for path in (self.apt_log, self.helper_log, self.sleep_log,
                     self.snapshot_count, self.installed, self.chown_log, self.config_log, self.retained_log):
            path.unlink(missing_ok=True)
        paths = [self.shell_path(self.bin), self.shell_path(Path(SH).resolve().parent)]
        paths.extend(['/usr/bin', '/bin'] if os.name == 'nt' else [os.environ['PATH']])
        env = dict(os.environ, PATH=':'.join(paths),
            TEST_APT_LOG=self.apt_log.as_posix(), TEST_HELPER_LOG=self.helper_log.as_posix(),
            TEST_PLAN=self.plan.as_posix(), TEST_INSTALLED=self.installed.as_posix(),
            TEST_CONFIG_LOG=self.config_log.as_posix(),
            TEST_CHOWN_LOG=self.chown_log.as_posix(),
            TEST_SLEEP_LOG=self.sleep_log.as_posix(), TEST_SNAPSHOT_COUNT=self.snapshot_count.as_posix(),
            TEST_GOOD_PAYLOAD=self.good.as_posix(), TEST_BAD_PAYLOAD=self.bad.as_posix(),
            TEST_ARCHIVE_MODE=archive, TEST_SNAPSHOT_MODE=snapshot, TEST_PLAN_STATUS=str(plan_status),
            TEST_RETAINED_MODE=retained, TEST_RETAINED_STATUS=str(retained_status),
            TEST_RETAINED_LOCATION=retained_location or 'https://launchpadlibrarian.net/123/' + POOL.rsplit('/', 1)[1],
            TEST_RETAINED_HEADER=retained_header, TEST_RETAINED_LOG=self.retained_log.as_posix(),
            TEST_CORRUPT_LOCAL='' if corrupt_local is None else self.shell_path(corrupt_local))
        if apt_config is not None:
            env['APT_CONFIG'] = self.shell_path(apt_config)
        return subprocess.run([SH, self.script.as_posix(), *(args or self.args)], env=env,
                              capture_output=True, text=True, timeout=15)

    def apt_calls(self):
        return [part.splitlines() for part in self.apt_log.read_text().split('BEGIN\n')[1:]]

    def downloads(self):
        return self.helper_log.read_text().splitlines() if self.helper_log.exists() else []

    def sleeps(self):
        return self.sleep_log.read_text().splitlines() if self.sleep_log.exists() else []

    def assert_rejected(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(self.installed.exists())

    def test_posix_syntax_and_fixed_production_transport(self):
        result = subprocess.run([SH, '-n', str(ROOT / 'scripts/install-snapshot-packages')],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('/usr/lib/apt/apt-helper -o Acquire::Retries=0', SOURCE)
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
        self.assertEqual(4, docker.count('    install-snapshot-packages '))
        self.assertEqual(3, docker.count('    apt-get update --error-on=any;'))
        # The first/final security indexes bracket the original base-only plan.
        updates = [m.start() for m in re.finditer('    apt-get update --error-on=any;', docker)]
        security = '"${UBUNTU_APT_SECURITY_SNAPSHOT}" > /etc/apt/sources.list.d/pasturestack-security.sources;'
        self.assertEqual(2, docker.count(security))
        phases = [docker.index(security), updates[0],
                  docker.index('    rm -f /etc/apt/sources.list.d/pasturestack-security.sources;'),
                  updates[1], docker.index('    install-snapshot-packages upgrade -y;'),
                  docker.rindex(security), updates[2]]
        self.assertEqual(sorted(phases), phases)
        self.assertIn('Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg', docker)
        self.assertIn('Acquire::https::Verify-Peer "true"', docker)
        self.assertIn('Acquire::https::Verify-Host "true"', docker)

    def test_explicit_local_inputs_keep_the_signed_remote_closure_and_original_no_download_install(self):
        # The utility owns transport, while the recipe owns its exact twelve-package list.
        names = ['bootstrap-a', 'bootstrap-b']
        inputs = []
        plan = ''
        for i, name in enumerate(names):
            file = self.local_root / (name + '.deb'); file.write_bytes(GOOD)
            path = self.shell_path(file); inputs.append(path)
            arch = 'all' if name == 'ca-certificates' else 'amd64'
            selection = name if i % 2 == 0 else name + ':' + arch
            plan += f"Note, selecting '{selection}' instead of '{path}'\n"
            uri = 'file:' + ('//' if i % 2 else '') + path
            filename = name + '.deb' if i % 2 else f'{name}_1.0_{arch}.deb'
            # APT permits local volatile-source records without any hash.
            plan += f"'{uri}' {filename} {len(GOOD)}" + (f' SHA256:{HASH}' if i % 2 else '') + '\n'
        pools = (
            'pool/main/k/krb5/libgssapi-krb5-2_1.22.1-2ubuntu4.1_amd64.deb',
            'pool/main/r/readline/readline-common_8.3-4_all.deb',
            'pool/universe/p/python-pip/python3-pip-whl_25.1.1%2bdfsg-1ubuntu2_all.deb',
            'pool/universe/s/setuptools/python3-setuptools-whl_78.1.1-0.1build1_all.deb',
        )
        for pool in pools:
            plan += self.uri_line(uri=SNAPSHOT + pool, filename=pool.rsplit('/', 1)[1])
        self.write_plan(plan)
        args = ['install', '-y', '--no-install-recommends', *inputs]
        result = self.run_helper(args=args)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{p}|SHA256:{HASH}' for p in pools], self.downloads())
        self.assertEqual([], self.sleeps())
        self.assertEqual(args, self.apt_calls()[0][-len(args):])
        self.assertEqual(args, self.apt_calls()[1][-len(args):])
        self.assertIn('--no-download', self.apt_calls()[1])
        for name in names:
            self.assertEqual(GOOD, (self.local_root / (name + '.deb')).read_bytes())

    def test_complete_remote_diagnostics_precede_transport_without_raw_or_local_data(self):
        file = self.local_root / 'never-publish-local.deb'
        file.write_bytes(GOOD)
        path = self.shell_path(file)
        pool2 = 'pool/main/r/remaining/remaining_2.0_amd64.deb'
        remote = self.uri_line() + self.uri_line(uri=SNAPSHOT + pool2, filename=pool2.rsplit('/', 1)[1])
        plan = f"'file:{path}' never-publish-local.deb {len(GOOD)}\n" + remote
        self.write_plan(plan + 'private-note-must-not-appear\n')
        helper = self.bin / 'apt-helper-mock'
        text = helper.read_text()
        needle = "printf '%s|%s\\n'"
        self.assertEqual(1, text.count(needle))
        helper.write_text(text.replace(needle, "printf 'TRANSPORT_STARTED\\n'\n" + needle), encoding='utf-8', newline='\n')
        result = self.run_helper(args=['install', '-y', path], archive='fail', snapshot='fail')
        self.assert_rejected(result)
        expected = [f'SNAPSHOT_PACKAGE_PLAN pool={pool} size={len(GOOD)} sha256={HASH}'
                    for pool in (POOL, pool2)]
        self.assertEqual(expected, [line for line in result.stdout.splitlines()
                                    if line.startswith('SNAPSHOT_PACKAGE_PLAN pool=')])
        self.assertLess(result.stdout.index('SNAPSHOT_PACKAGE_PLAN_END'), result.stdout.index('TRANSPORT_STARTED'))
        self.assertIn('package_download_failed', result.stderr)
        for private in (path, 'file:', 'private-note-must-not-appear', 'Note, selecting'):
            self.assertNotIn(private, result.stdout)
        # A bad later row must prevent even the earlier valid row being logged.
        for bad in (self.uri_line(digest='bad'), self.uri_line(uri='https://invalid.example/private')):
            self.write_plan(self.uri_line() + bad)
            result = self.run_helper(archive='fail', snapshot='fail')
            self.assert_rejected(result)
            self.assertEqual([], self.downloads())
            self.assertNotIn('SNAPSHOT_PACKAGE_PLAN', result.stdout)
            self.assertNotIn('private', result.stdout)

    def model_native_local_cache_hit(self, filename):
        # APT 3.2.1 omits an already cached URI after checking size, not SHA.
        target = self.bin / 'apt-get'
        text = target.read_text()
        needle = '        cat "$TEST_PLAN"'
        self.assertEqual(1, text.count(needle))
        replacement = '''        planning_cache=
        for option do
            case "$option" in Dir::Cache::archives=*) planning_cache=${option#*=} ;; esac
        done
        [ -n "$planning_cache" ] || exit 98
        if [ -f "$planning_cache/''' + filename + '''" ] &&
           [ "$(wc -c < "$planning_cache/''' + filename + '''" | tr -d '[:space:]')" = ''' + str(len(GOOD)) + ''' ]; then
            exit "$TEST_PLAN_STATUS"
        fi
''' + needle
        target.write_text(text.replace(needle, replacement), encoding='utf-8', newline='\n')

    def test_local_inputs_use_empty_planning_cache_and_seed_exact_native_cache_names(self):
        file = self.local_root / 'approved.deb'
        file.write_bytes(GOOD)
        path = self.shell_path(file)
        filename = 'approved_1.0_amd64.deb'
        self.model_native_local_cache_hit(filename)
        self.write_plan(f"'file:{path}' {filename} {len(GOOD)}\n")
        args = ['install', '-y', path]
        # Empty real cache seeds bytes; a second call must still bind the URI
        # through the separate empty planner, despite a native size-only hit.
        for reused in (False, True):
            result = self.run_helper(args=args)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(GOOD, (self.cache / filename).read_bytes())
            self.assertEqual(GOOD, file.read_bytes())
            self.assertEqual([], self.downloads())
            planning = next(v for v in self.apt_calls()[0] if v.startswith('Dir::Cache::archives='))
            self.assertTrue(planning.endswith('/plan-archives'), (reused, planning))
            self.assertNotEqual('Dir::Cache::archives=' + self.cache.as_posix(), planning)
            self.assertEqual(args, self.apt_calls()[1][-len(args):])
            self.assertIn('--no-download', self.apt_calls()[1])

    def test_local_cache_wrong_hash_size_or_directory_cannot_reach_native_install(self):
        file = self.local_root / 'approved.deb'
        file.write_bytes(GOOD)
        path = self.shell_path(file)
        filename = 'approved_1.0_amd64.deb'
        self.model_native_local_cache_hit(filename)
        self.write_plan(f"'file:{path}' {filename} {len(GOOD)}\n")
        self.cache.mkdir()
        target = self.cache / filename
        self.assertEqual(len(GOOD), len(BAD))
        for mode, value in (('same-size-wrong-sha', BAD), ('wrong-size', GOOD + b'x'), ('directory', None)):
            with self.subTest(mode=mode):
                if value is None:
                    target.mkdir()
                else:
                    target.write_bytes(value)
                result = self.run_helper(args=['install', '-y', path])
                self.assert_rejected(result)
                expected = 'invalid_local_cache_destination' if value is None else 'local_cache_verification_failed'
                self.assertIn(expected, result.stderr)
                self.assertEqual([], self.downloads())
                self.assertEqual(GOOD, file.read_bytes())
                if value is None:
                    target.rmdir()
                else:
                    self.assertEqual(value, target.read_bytes())
                    target.unlink()

    def test_local_and_remote_canonical_cache_collisions_reject_before_transport(self):
        file = self.local_root / 'approved.deb'
        file.write_bytes(GOOD)
        path = self.shell_path(file)
        filename = 'approved_1.0_amd64.deb'
        local = f"'file:{path}' {filename} {len(GOOD)}\n"
        remote = self.uri_line(uri=SNAPSHOT + 'pool/main/a/approved/' + filename,
                               filename=filename, digest=hashlib.sha256(BAD).hexdigest())
        for plan in (local + local, local + remote, remote + local):
            with self.subTest(order=plan.startswith("'file:")):
                self.write_plan(plan)
                result = self.run_helper(args=['install', '-y', path])
                self.assert_rejected(result)
                self.assertIn('duplicate_filename', result.stderr)
                self.assertEqual([], self.downloads())
                self.assertNotIn('SNAPSHOT_PACKAGE_PLAN', result.stdout)

    def test_local_cache_last_sha_recheck_detects_tampering_after_atomic_seed(self):
        file = self.local_root / 'approved.deb'
        file.write_bytes(GOOD)
        path = self.shell_path(file)
        filename = 'approved_1.0_amd64.deb'
        self.write_plan(f"'file:{path}' {filename} {len(GOOD)}\n")
        real_mv = (Path(SH).resolve().parent / 'mv.exe').as_posix() if os.name == 'nt' else shutil.which('mv')
        self.assertTrue(real_mv)
        target = (self.cache / filename).as_posix()
        self.make_script('mv', '#!/bin/sh\nset -eu\n' + shlex.quote(real_mv) + ' "$@"\n'
                         'last=\nfor last do :; done\n[ "$last" = ' + shlex.quote(target) + ' ] || exit 98\n'
                         "printf 'evil package\\n' > \"$last\"\n")
        result = self.run_helper(args=['install', '-y', path])
        self.assert_rejected(result)
        self.assertIn('local_cache_verification_failed', result.stderr)
        self.assertEqual(GOOD, file.read_bytes())
        self.assertEqual(BAD, (self.cache / filename).read_bytes())

    def test_local_cache_symlink_is_rejected_even_when_target_bytes_match(self):
        file = self.local_root / 'approved.deb'
        file.write_bytes(GOOD)
        path = self.shell_path(file)
        filename = 'approved_1.0_amd64.deb'
        self.write_plan(f"'file:{path}' {filename} {len(GOOD)}\n")
        self.cache.mkdir()
        try:
            (self.cache / filename).symlink_to(file)
        except OSError as error:
            if os.name == 'nt' and getattr(error, 'winerror', None) == 1314:
                self.skipTest('Windows symlink privilege unavailable; Linux CI must execute this control')
            raise
        result = self.run_helper(args=['install', '-y', path])
        self.assert_rejected(result)
        self.assertIn('invalid_local_cache_destination', result.stderr)
        self.assertEqual(GOOD, file.read_bytes())

    def test_local_plan_rows_are_bound_to_explicit_argument_bytes_size_and_filename(self):
        file = self.local_root / 'approved.deb'; file.write_bytes(GOOD)
        other = self.local_root / 'unbound.deb'; other.write_bytes(GOOD)
        path = self.shell_path(file)
        args = ['install', '-y', path]
        valid = f"'file:{path}' approved.deb {len(GOOD)} SHA256:{HASH}\n"
        invalid = (
            valid.replace(path, self.shell_path(other)),
            valid.replace(path, '/etc/passwd'),
            valid.replace('file:', 'file://foreign.invalid'),
            valid.replace('approved.deb ' + str(len(GOOD)), 'other.deb ' + str(len(GOOD))),
            valid.replace(str(len(GOOD)) + ' SHA256:', str(len(GOOD) + 1) + ' SHA256:'),
            valid.replace(HASH, 'f' * 64), valid.replace('SHA256:', 'MD5Sum:'),
            valid.replace(HASH, ''),
            f"Note, selecting 'unbound' instead of '{path}'\n",
            self.uri_line().replace('SHA256:', 'MD5Sum:'),
        )
        for row in invalid:
            with self.subTest(plan=row):
                self.write_plan(self.uri_line() + row)
                self.assert_rejected(self.run_helper(args=args))
                self.assertEqual([], self.downloads())
        self.write_plan(valid)
        self.assert_rejected(self.run_helper())  # A file row never grants a new local argument.

    def test_local_regular_paths_and_final_snapshot_are_required_before_install(self):
        file = self.local_root / 'approved.deb'; file.write_bytes(GOOD)
        outside = self.home / 'outside.deb'; outside.write_bytes(GOOD)
        directory = self.local_root / 'directory.deb'; directory.mkdir()
        for path in (self.shell_path(outside), self.shell_path(directory),
                     self.shell_path(self.local_root / 'missing.deb'),
                     self.shell_path(file.parent) + '/../approved.deb', self.shell_path(file) + '?query'):
            with self.subTest(path=path):
                self.assert_rejected(self.run_helper(args=['install', '-y', path]))
                self.assertEqual([], self.downloads())
        # No file rows are required if APT omits them; final input identity still is.
        result = self.run_helper(args=['install', '-y', self.shell_path(file)], corrupt_local=file)
        self.assert_rejected(result)
        self.assertIn('local_input_changed', result.stderr)
        self.assertEqual(1, len(self.apt_calls()))

    def test_local_symlink_never_reaches_the_native_planner(self):
        file = self.local_root / 'approved.deb'; file.write_bytes(GOOD)
        link = self.local_root / 'link.deb'
        try:
            link.symlink_to(file)
        except OSError as error:
            if os.name == 'nt': self.skipTest('Native symlink permission is unavailable on this Windows fixture host')
            raise error
        self.assert_rejected(self.run_helper(args=['install', '-y', self.shell_path(link)]))
        self.assertEqual([], self.downloads())
        self.assertFalse(self.apt_log.exists())

    def test_official_archive_first_and_exact_no_download_args(self):
        self.write_plan('Reading package lists...\n  unzip\n' + self.uri_line())
        result = self.run_helper()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{POOL}|SHA256:{HASH}'], self.downloads())
        self.assertEqual([], self.sleeps())
        self.assertEqual(GOOD, (self.cache / POOL.rsplit('/', 1)[1]).read_bytes())
        calls = self.apt_calls()
        self.assertEqual(2, len(calls))
        self.assertIn('Acquire::ForceHash=SHA256', calls[0])
        self.assertIn('--print-uris', calls[0])
        self.assertIn('--download-only', calls[0])
        self.assertIn('--no-download', calls[1])
        self.assertEqual(self.args, calls[0][-len(self.args):])
        self.assertEqual(self.args, calls[1][-len(self.args):])

    def test_retained_payload_keeps_archive_snapshot_order_and_exact_official_303_without_following(self):
        leaf = POOL.rsplit('/', 1)[1]
        result = self.run_helper(archive='fail', snapshot='fail', retained='ok', retained_status=303)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{POOL}|SHA256:{HASH}'] +
                         [f'{SNAPSHOT}{POOL}|SHA256:{HASH}'] * 4 +
                         [f'https://launchpad.net/ubuntu/+archive/primary/+files/{leaf}|SHA256:{HASH}',
                          f'https://launchpadlibrarian.net/123/{leaf}|SHA256:{HASH}'], self.downloads())
        self.assertEqual(['1', '2', '3'], self.sleeps())
        self.assertEqual(['https://launchpad.net/ubuntu/+archive/primary/+files/' + leaf],
                         self.retained_log.read_text().splitlines())
        self.assertEqual(GOOD, (self.cache / leaf).read_bytes())
        self.assertIn('--no-download', self.apt_calls()[1])
        self.assertEqual(self.args, self.apt_calls()[1][-len(self.args):])

    def test_retained_redirect_rejects_other_hosts_paths_protocols_statuses_and_unbounded_headers(self):
        leaf = POOL.rsplit('/', 1)[1]
        locations = [f'http://launchpadlibrarian.net/123/{leaf}', f'https://other.invalid/123/{leaf}',
                     f'https://launchpadlibrarian.net.other.invalid/123/{leaf}',
                     f'https://user@launchpadlibrarian.net/123/{leaf}', f'https://launchpadlibrarian.net:443/123/{leaf}',
                     f'https://launchpadlibrarian.net/0/{leaf}', f'https://launchpadlibrarian.net/abc/{leaf}',
                     f'https://launchpadlibrarian.net/123/extra/{leaf}',
                     f'https://launchpadlibrarian.net/123/{leaf}?x=1', f'https://launchpadlibrarian.net/123/{leaf}#x',
                     'https://launchpadlibrarian.net/123/other_1_amd64.deb']
        cases = [dict(retained_location=value) for value in locations]
        cases += [dict(retained_status=value) for value in (200, 301, 302, 307, 308)]
        cases += [dict(retained_header=value) for value in ('duplicate', 'folded', 'oversized')]
        for options in cases:
            with self.subTest(options=options):
                settings = dict(archive='fail', snapshot='fail', retained='ok', retained_status=303)
                settings.update(options)
                result = self.run_helper(**settings)
                self.assert_rejected(result)
                self.assertEqual(6, len(self.downloads()))
                self.assertEqual(1, len(self.apt_calls()))
                self.assertFalse(list(self.cache.glob('*.deb')))

    def test_retained_payload_keeps_signed_size_sha_failed_marker_and_no_redirect_gate(self):
        for mode in ('bad', 'failed', 'failed-ok', 'fail'):
            with self.subTest(mode=mode):
                self.assert_rejected(self.run_helper(archive='fail', snapshot='fail', retained=mode, retained_status=303))
                self.assertEqual(7, len(self.downloads()))
                self.assertEqual(1, len(self.apt_calls()))
        self.write_plan(self.uri_line(size=len(GOOD) + 1))
        self.assert_rejected(self.run_helper(archive='fail', snapshot='fail', retained='ok', retained_status=303))
        self.assertFalse(list(self.cache.glob('*.deb')))

    def test_retained_metadata_is_never_requested_before_original_success_or_after_snapshot_integrity_failure(self):
        for archive, snapshot, success in [('ok', 'fail', True), ('fail', 'ok', True), ('fail', 'bad', False)]:
            with self.subTest(archive=archive, snapshot=snapshot):
                (self.cache / POOL.rsplit('/', 1)[1]).unlink(missing_ok=True)
                result = self.run_helper(archive=archive, snapshot=snapshot, retained='ok', retained_status=303)
                if success: self.assertEqual(0, result.returncode, result.stderr)
                else: self.assert_rejected(result)
                self.assertFalse(self.retained_log.exists())

    def test_official_transport_failure_falls_back_only_to_original_snapshot(self):
        result = self.run_helper(archive='fail')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{POOL}|SHA256:{HASH}',
                          f'{SNAPSHOT}{POOL}|SHA256:{HASH}'], self.downloads())

    def test_bad_archive_hash_falls_back_and_never_uses_corrupt_payload(self):
        result = self.run_helper(archive='bad')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(self.downloads()))
        self.assertEqual([], self.sleeps())
        self.assertEqual(GOOD, (self.cache / POOL.rsplit('/', 1)[1]).read_bytes())

    def test_bad_hash_on_both_origins_cannot_install(self):
        result = self.run_helper(archive='bad', snapshot='bad')
        self.assert_rejected(result)
        self.assertEqual(2, len(self.downloads()))
        self.assertEqual([], self.sleeps())
        self.assertEqual(1, len(self.apt_calls()))
        self.assertFalse(list(self.cache.glob('*.deb')))

    def test_correct_hash_but_wrong_declared_size_cannot_install(self):
        self.write_plan(self.uri_line(size=len(GOOD) + 1))
        result = self.run_helper()
        self.assert_rejected(result)
        self.assertEqual(2, len(self.downloads()))
        self.assertEqual([], self.sleeps())

    def test_both_transport_failures_cannot_install(self):
        result = self.run_helper(archive='fail', snapshot='fail')
        self.assert_rejected(result)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{POOL}|SHA256:{HASH}'] +
                         [f'{SNAPSHOT}{POOL}|SHA256:{HASH}'] * 4 +
                         [f'https://launchpad.net/ubuntu/+archive/primary/+files/{POOL.rsplit("/", 1)[1]}|SHA256:{HASH}'], self.downloads())
        self.assertEqual(['1', '2', '3'], self.sleeps())
        self.assertEqual(1, len(self.apt_calls()))
        self.assertFalse(list(self.cache.glob('*.deb')))

    def test_snapshot_transient_transport_retries_original_url_then_succeeds(self):
        self.write_plan(self.uri_line(uri=SECURITY + POOL))
        result = self.run_helper(archive='fail', snapshot='transient')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{POOL}|SHA256:{HASH}'] +
                         [f'{SECURITY}{POOL}|SHA256:{HASH}'] * 3, self.downloads())
        self.assertEqual(['1', '2'], self.sleeps())
        self.assertEqual(GOOD, (self.cache / POOL.rsplit('/', 1)[1]).read_bytes())
        self.assertIn('--no-download', self.apt_calls()[1])

    def test_apt_failed_integrity_marker_stops_origin_even_with_nonzero_or_success_exit(self):
        for mode in ('failed', 'failed-ok'):
            with self.subTest(mode=mode):
                result = self.run_helper(archive='fail', snapshot=mode)
                self.assert_rejected(result)
                self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{POOL}|SHA256:{HASH}',
                                  f'{SNAPSHOT}{POOL}|SHA256:{HASH}'], self.downloads())
                self.assertEqual([], self.sleeps())
                self.assertEqual(1, len(self.apt_calls()))
                self.assertFalse(list(self.cache.glob('*.deb')))

    def test_archive_apt_failed_integrity_marker_can_recover_from_original_snapshot(self):
        result = self.run_helper(archive='failed')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([f'https://archive.ubuntu.com/ubuntu/{POOL}|SHA256:{HASH}',
                          f'{SNAPSHOT}{POOL}|SHA256:{HASH}'], self.downloads())
        self.assertEqual([], self.sleeps())
        self.assertEqual(GOOD, (self.cache / POOL.rsplit('/', 1)[1]).read_bytes())

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
                self.assertEqual([], self.sleeps())

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



    def test_security_overlay_uses_canonical_snapshot_flags_and_same_private_config(self):
        header = self.security_lock.read_text().splitlines()[0]
        date = re.fullmatch(r'# Official Ubuntu 26.04 security/updates snapshot: (\d{8}T\d{6}Z)', header).group(1)
        self.write_plan(self.uri_line('https://snapshot.ubuntu.com/ubuntu/' + date + '/' + POOL))
        config = self.home / 'private-apt.conf'
        config.write_text('Acquire::https::Verify-Peer "true";\n')
        args = ['install', '-y', '--only-upgrade', '--no-install-recommends', '--no-remove', 'unzip=6.0-29ubuntu1']
        result = self.run_helper(args=args, apt_config=config)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.installed.exists())
        self.assertEqual([self.shell_path(config)] * 2, self.config_log.read_text().splitlines())
        calls = self.apt_log.read_text().split('BEGIN\n')[1:]
        self.assertEqual(2, len(calls))
        self.assertEqual(args, calls[-1].splitlines()[3:])
        self.assertEqual('--no-download', calls[-1].splitlines()[2])
        self.assertEqual('https://archive.ubuntu.com/ubuntu/' + POOL,
                         self.helper_log.read_text().split('|')[0])
        for option in ('--allow-downgrades', '--allow-unauthenticated', '--force-yes'):
            with self.subTest(option=option):
                result = self.run_helper(args=args + [option], apt_config=config)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.installed.exists())
                self.assertFalse(self.apt_log.exists())

    def test_security_snapshot_header_is_unique_closed_and_cannot_escape_the_canonical_lock(self):
        original = self.security_lock.read_bytes()
        for value in (b'', b'# Official Ubuntu 26.04 security/updates snapshot: nope\n',
                      original.splitlines()[0] + b'\n' + original,
                      b'# Official Ubuntu 26.04 security/updates snapshot: 20261023T000000Z;evil\n'):
            with self.subTest(header=value[:80]):
                self.security_lock.write_bytes(value)
                result = self.run_helper()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.apt_log.exists())
                self.assertFalse(self.installed.exists())
        self.security_lock.write_bytes(original)
        self.write_plan(self.uri_line('https://snapshot.ubuntu.com/ubuntu/20261024T000000Z/' + POOL))
        result = self.run_helper()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.installed.exists())
        self.assertFalse(self.helper_log.exists())
        if os.name != 'nt':
            target = self.home / 'other.lock'
            target.write_bytes(original)
            self.security_lock.unlink()
            self.security_lock.symlink_to(target)
            result = self.run_helper()
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(self.apt_log.exists())
            self.assertFalse(self.installed.exists())

if __name__ == '__main__':
    unittest.main(verbosity=2)
