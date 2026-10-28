#!/usr/bin/env python3
"""Exercise the bounded preflight with fake Android commands; never alter host settings."""
from pathlib import Path
import json
import os
import shlex
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/assets/accessibility-preflight.sh'
COMPONENT = 'io.github.miaiplus/io.github.miaiplus.ScreenService'
APPLICATION_ID = 'io.github.miaiplus'
OTHERS = 'other.one/.Service:other.two/other.two.Service'
RUN_ID = '01234567-89ab-cdef-0123-456789abcdef'


class AccessibilityGuardTest(unittest.TestCase):
    def run_preflight(self, services, connected=False, busy=False, enabled=True,
                      preparing=True, stale_id=False, stop_during_unbind=False, never_connect=False,
                      application_id=APPLICATION_ID):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            (base / 'settings.json').write_text(json.dumps({'services': services, 'global': '1', 'rebound': False}))
            binary = base / 'bin'
            binary.mkdir()
            fake = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
base = Path(os.environ['GUARD_TEST_BASE'])
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with (base / 'calls').open('a') as output: output.write(name + ' ' + ' '.join(args) + '\\n')
data = json.loads((base / 'settings.json').read_text())
if name == 'id': print('0')
elif name == 'am': print('7' if args[0] == 'get-current-user' else 'RUNNING_UNLOCKED')
elif name == 'timeout': os.execvp(args[1], args[1:])
elif name == 'content':
    assert args[args.index('--uri') + 1] == 'content://' + os.environ['GUARD_TEST_PACKAGE'] + '.bridge', args
    enabled = os.environ['GUARD_TEST_ENABLED'] == 'true' and not (base / 'cancelled').exists()
    connected = os.environ['GUARD_TEST_CONNECTED'] == 'true' or (data['rebound'] and os.environ['GUARD_TEST_NEVER_CONNECT'] != 'true')
    print('Bundle[{preflight_enabled=' + str(enabled).lower() + ', preparing=' + os.environ['GUARD_TEST_PREPARING']
          + ', connected=' + str(connected).lower() + ', busy=' + os.environ['GUARD_TEST_BUSY']
          + ', preflight_id=' + os.environ['GUARD_TEST_ID'] + '}]')
elif name == 'settings':
    assert args[:2] == ['--user', '7'], args
    key = 'services' if args[4] == 'enabled_accessibility_services' else 'global'
    if args[2] == 'get': print(data[key])
    else:
        data[key] = args[5]
        component = os.environ['GUARD_TEST_PACKAGE'] + '/io.github.miaiplus.ScreenService'
        if key == 'services' and component in args[5].split(':'): data['rebound'] = True
        (base / 'settings.json').write_text(json.dumps(data))
elif name == 'sleep' and os.environ['GUARD_TEST_STOP_UNBIND'] == 'true':
    (base / 'cancelled').touch()
'''
            for name in ['id', 'am', 'timeout', 'content', 'settings', 'sleep']:
                path = binary / name
                path.write_text(fake)
                path.chmod(0o755)
            path = base / 'preflight.sh'
            path.write_text(SOURCE.read_text().replace('PATH=/system/bin:/system/xbin',
                                                      f'PATH={shlex.quote(str(binary))}:$PATH'))
            environment = dict(os.environ, GUARD_TEST_BASE=folder,
                               GUARD_TEST_CONNECTED=str(connected).lower(), GUARD_TEST_BUSY=str(busy).lower(),
                               GUARD_TEST_ENABLED=str(enabled).lower(), GUARD_TEST_PREPARING=str(preparing).lower(),
                               GUARD_TEST_STOP_UNBIND=str(stop_during_unbind).lower(),
                               GUARD_TEST_NEVER_CONNECT=str(never_connect).lower(),
                               GUARD_TEST_PACKAGE=application_id,
                               GUARD_TEST_ID='stale' if stale_id else RUN_ID)
            result = subprocess.run(['/bin/sh', str(path), '7', RUN_ID, application_id], env=environment,
                                    timeout=10, capture_output=True, text=True)
            calls = (base / 'calls').read_text() if (base / 'calls').exists() else ''
            return result.returncode, json.loads((base / 'settings.json').read_text()), calls

    def test_false_on_normalizes_services_and_rebinds_only_module(self):
        for name, services, remaining in [
                ('full component', OTHERS + ':' + COMPONENT, OTHERS),
                ('short component', OTHERS + ':io.github.miaiplus/.ScreenService', OTHERS),
                ('empty setting', 'null', '')]:
            with self.subTest(services=name):
                code, data, calls = self.run_preflight(services)
                self.assertEqual(0, code)
                self.assertEqual(':'.join(filter(None, [remaining, COMPONENT])), data['services'])
                self.assertIn('put secure enabled_accessibility_services ' + remaining + '\n', calls)
                self.assertNotIn('put secure accessibility_enabled 0', calls)
                self.assertNotIn('accessibility_recover', calls)
                self.assertEqual(5, sum(line.startswith('content ') for line in calls.splitlines()))

    def test_healthy_binder_exits_without_settings_changes_or_waiting(self):
        code, _, calls = self.run_preflight(OTHERS + ':' + COMPONENT, connected=True)
        self.assertEqual(0, code)
        self.assertNotIn('put secure', calls)
        self.assertNotIn('sleep ', calls)
        self.assertEqual(1, sum(line.startswith('content ') for line in calls.splitlines()))

    def test_invalid_preflight_cannot_repair_or_cancel_tasks(self):
        for name, options in [
                ('executing task', dict(busy=True)),
                ('disabled option', dict(enabled=False)),
                ('cancelled preparation', dict(preparing=False)),
                ('stale ticket', dict(stale_id=True))]:
            with self.subTest(state=name):
                code, _, calls = self.run_preflight(OTHERS + ':' + COMPONENT, **options)
                self.assertEqual(5, code)
                self.assertNotIn('put secure', calls)
                self.assertNotIn('accessibility_recover', calls)

    def test_cancel_during_unbind_does_not_reenable_module(self):
        code, data, _ = self.run_preflight(OTHERS + ':' + COMPONENT, stop_during_unbind=True)
        self.assertEqual(5, code)
        self.assertEqual(OTHERS, data['services'])

    def test_failed_rebind_stops_after_bounded_attempts(self):
        code, _, calls = self.run_preflight(OTHERS + ':' + COMPONENT, never_connect=True)
        self.assertEqual(7, code)
        self.assertEqual(10, calls.count('sleep 1\n') - 1)

    def test_pluginify_rebind_preserves_mainline_service_and_uses_its_own_provider(self):
        package = 'io.github.miaiplus.pluginify'
        component = package + '/io.github.miaiplus.ScreenService'
        remaining = OTHERS + ':' + COMPONENT
        code, data, calls = self.run_preflight(remaining + ':' + component, application_id=package)
        self.assertEqual(0, code)
        self.assertEqual(remaining + ':' + component, data['services'])
        self.assertIn('put secure enabled_accessibility_services ' + remaining + '\n', calls)
        self.assertIn('--uri content://' + package + '.bridge ', calls)
        self.assertNotIn('--uri content://io.github.miaiplus.bridge ', calls)

    def test_missing_or_invalid_install_identity_never_runs_android_commands(self):
        for package in ['', 'io..miaiplus', 'io.github.miaiplus;id']:
            with self.subTest(package=package):
                code, data, calls = self.run_preflight(OTHERS + ':' + COMPONENT, application_id=package)
                self.assertEqual(2, code)
                self.assertEqual(OTHERS + ':' + COMPONENT, data['services'])
                self.assertEqual('', calls)


if __name__ == '__main__':
    unittest.main()
