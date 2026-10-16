#!/usr/bin/env python3
"""Test the bounded task-start helper with adb root, without app-UID su authorization."""
from pathlib import Path
import re
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REMOTE = '/data/local/tmp/miaiplus-accessibility-preflight-test.sh'


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True).strip()


def root(command, check=True):
    result = subprocess.run(['adb', 'shell', 'su -c ' + shlex.quote(command)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
    if check:
        if result.returncode != 0:
            print(result.stdout, flush=True)
        result.check_returncode()
    return result


def settings(user):
    return root(f'settings --user {user} get secure enabled_accessibility_services').stdout.strip()


def others(value):
    return {s for s in value.split(':') if s and s != 'null' and not s.startswith('io.github.miaiplus/')}


def main():
    user = root('am get-current-user').stdout.strip()
    assert user.isdecimal(), 'invalid Android user'
    before = settings(user)
    assert 'io.github.miaiplus/' in before, 'enable MiAIPlus accessibility before this probe'
    adb('push', str(ROOT / 'app/src/main/assets/accessibility-preflight.sh'), REMOTE)
    process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', user, '-w', '-e',
                                'guard_preflight', 'true',
                                'io.github.miaiplus.test/io.github.miaiplus.DeviceTestRunner'],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    passed = False
    recovered = False
    rejected = False
    try:
        for line in process.stdout:
            print(line, end='', flush=True)
            if 'MIAIPLUS_PREFLIGHT_CONNECT' in line:
                # Instrumentation restarts the app; reconnect only our test service.
                current = settings(user)
                remaining = ':'.join(s for s in current.split(':') if s and s != 'null' and not s.startswith('io.github.miaiplus/'))
                root(f'settings --user {user} put secure enabled_accessibility_services ' + shlex.quote(remaining))
                root(f'settings --user {user} put secure enabled_accessibility_services ' + shlex.quote(current))
            match = re.search(r'MIAIPLUS_PREFLIGHT_(STALE|EXECUTING) ([a-f0-9-]+)', line)
            if match:
                phase, run_id = match.groups()
                command = f'/system/bin/sh {REMOTE} {user} {run_id}'
                if phase == 'STALE':
                    health = root(f'content call --user {user} --uri content://io.github.miaiplus.bridge --method accessibility_health').stdout
                    assert 'connected=false' in health, 'false-on connection was not injected'
                    assert settings(user) == before, 'settings changed before preflight'
                    root(command)
                    recovered = True
                    assert others(before) <= others(settings(user)), 'another service was removed'
                else:
                    current = settings(user)
                    assert root(command, check=False).returncode == 5, 'helper accepted an executing task'
                    assert settings(user) == current, 'helper modified an executing task'
                    rejected = True
            if 'PASS:' in line:
                passed = True
            if 'FAIL:' in line or 'INSTRUMENTATION_FAILED' in line:
                passed = False
        process.wait(timeout=10)
    finally:
        adb('shell', 'rm -f ' + REMOTE)
        if process.poll() is None:
            process.terminate()
    assert passed and recovered and rejected and process.returncode == 0, 'preflight device test failed'
    print('PASS: false-on recovered before actions; executing task refused; other services and prior preference preserved.')


if __name__ == '__main__':
    main()
