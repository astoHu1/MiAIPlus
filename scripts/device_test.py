#!/usr/bin/env python3
"""Run deterministic device tests without UiAutomation suppressing/intercepting services."""
import subprocess
import sys


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True).strip()


process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-e', 'hook', str('--hook' in sys.argv).lower(),
    '-e', 'api', 'responses' if '--responses' in sys.argv else 'chat',
    '-e', 'reasoning', str('--reasoning' in sys.argv).lower(),
    '-e', 'focus', str('--focus' in sys.argv).lower(),
    'io.github.miaiplus.test/io.github.miaiplus.DeviceTestRunner'], stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT, text=True, bufsize=1)
passed = False
failed = False
for line in process.stdout:
    print(line, end='', flush=True)
    if 'MIAIPLUS_CONNECT_SERVICE' in line:
        current = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
        services = [s for s in current.split(':') if s and s != 'null' and not s.startswith('io.github.miaiplus/')]
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
        services.append('io.github.miaiplus/.ScreenService')
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
    if 'MIAIPLUS_RESTART_XIAOAI' in line:
        current = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
        services = [s for s in current.split(':') if s and s != 'null' and not s.startswith('io.github.miaiplus/')]
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
        services.append('io.github.miaiplus/.ScreenService')
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
        if '--no-restart' not in sys.argv:
            adb('shell', 'am', 'force-stop', 'com.miui.voiceassist')
        adb('shell', 'am', 'start', '-W', '-n', 'io.github.miaiplus/.FixtureActivity')
        adb('shell', 'am', 'start', '-W', '-n', 'com.miui.voiceassist/com.xiaomi.voiceassistant.LaunchHomeRouterActivity')
        if '--popup' in sys.argv:
            adb('shell', 'am', 'start', '-W', '-n', 'io.github.miaiplus/.FixtureActivity')
            adb('shell', 'am', 'startservice', '-n', 'com.miui.voiceassist/com.xiaomi.voiceassistant.VoiceService',
                '-a', 'com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST')
        # Wait for the scoped main process and debug receiver to initialize.
        import time
        time.sleep(3)
        adb('shell', 'am', 'broadcast', '-a', 'io.github.miaiplus.DEBUG_PROBE', '-p', 'com.miui.voiceassist', '--es', 'frame', line.strip().split()[-1])
    if 'MIAIPLUS_LAUNCH_FIXTURE' in line:
        adb('shell', 'am', 'start', '-W', '-n', 'io.github.miaiplus/.FixtureActivity')
    if 'PASS:' in line or 'PROBE PASS' in line:
        passed = True
    if 'FAIL:' in line or 'INSTRUMENTATION_FAILED' in line:
        failed = True
process.wait()
sys.exit(0 if passed and not failed and process.returncode == 0 else 1)
