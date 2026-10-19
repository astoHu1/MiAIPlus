#!/usr/bin/env python3
"""Run deterministic device tests without UiAutomation suppressing/intercepting services."""
import subprocess
import sys
import re
import tempfile
import time
from pathlib import Path


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True).strip()


process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-e', 'hook', str('--hook' in sys.argv).lower(),
    *(['-e', 'detailed_log', str('--detailed-log' in sys.argv).lower()]
      if '--detailed-log' in sys.argv or '--no-detailed-log' in sys.argv else []),
    '-e', 'background', str('--background' in sys.argv).lower(),
    '-e', 'profiles', str('--profiles' in sys.argv).lower(),
    '-e', 'logs', str('--logs' in sys.argv).lower(),
    '-e', 'api', 'responses' if '--responses' in sys.argv else 'chat',
    '-e', 'reply_actions', str('--reply-actions' in sys.argv or '--reply-actions-chat' in sys.argv or '--reply-actions-current-config' in sys.argv).lower(),
    '-e', 'reply_actions_api', 'chat' if '--reply-actions-chat' in sys.argv else 'responses',
    '-e', 'reply_actions_current_config', str('--reply-actions-current-config' in sys.argv).lower(),
    '-e', 'reasoning', str('--reasoning' in sys.argv).lower(),
    '-e', 'focus', str('--focus' in sys.argv).lower(),
    '-e', 'launch', str('--launch' in sys.argv).lower(),
    '-e', 'scroll', str('--scroll' in sys.argv).lower(),
    '-e', 'robust_screen', str('--robust-screen' in sys.argv).lower(),
    '-e', 'real_page_guard', str('--real-page-guard' in sys.argv).lower(),
    '-e', 'phone_use_terminal', str('--phone-use-terminal' in sys.argv).lower(),
    '-e', 'action_confirmation', str('--action-confirmation' in sys.argv).lower(),
    '-e', 'conversation_return', str('--conversation-return' in sys.argv).lower(),
    '-e', 'conversation_continuity', str('--conversation-continuity' in sys.argv).lower(),
    '-e', 'conversation_replies', str('--conversation-replies' in sys.argv).lower(),
    '-e', 'dual_tools', str('--dual-tools' in sys.argv).lower(),
    '-e', 'foreground_consent', str('--foreground-consent' in sys.argv).lower(),
    'io.github.miaiplus.test/io.github.miaiplus.DeviceTestRunner'], stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT, text=True, bufsize=1)
passed = False
failed = False
try:
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
            fixture = 'RobustFixtureActivity' if '--robust-screen' in sys.argv or '--action-confirmation' in sys.argv else 'FixtureActivity'
            adb('shell', 'am', 'start', '-W', '-n', 'io.github.miaiplus/.' + fixture)
            adb('shell', 'am', 'start', '-W', '-n', 'com.miui.voiceassist/com.xiaomi.voiceassistant.LaunchHomeRouterActivity')
            # Wait for the scoped main process and debug receiver to initialize.
            time.sleep(3)
            if '--launch' in sys.argv:
                # This ROM disables Security's launcher alias; use its exported MAIN/DEFAULT entry.
                adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN',
                    '-c', 'android.intent.category.DEFAULT', '-p', 'com.miui.securitycenter')
            probe_token = line.strip().split()[-1]
            if '--popup' in sys.argv and probe_token == 'MIAIPLUS_FOCUS_UI':
                probe_token = 'MIAIPLUS_FOCUS_POPUP_UI'
            adb('shell', 'am', 'broadcast', '-a', 'io.github.miaiplus.DEBUG_PROBE', '-p', 'com.miui.voiceassist', '--es', 'frame', probe_token)
        if 'MIAIPLUS_LAUNCH_FIXTURE' in line:
            adb('shell', 'am', 'start', '-W', '-n', 'io.github.miaiplus/.FixtureActivity')
        if '--conversation-replies' in sys.argv and line.startswith('CONVERSATION_REPLY_SCREENSHOT '):
            fields = line.strip().split()
            if len(fields) != 3 or not re.fullmatch(r'[a-f0-9-]{36}', fields[2]):
                raise ValueError('invalid owned reply screenshot handshake')
            stage, nonce = fields[1:]
            expected = 'miaiplus-ok-' + nonce[:8]
            # Some host cards draw text without exposing it to accessibility. Check actual pixels;
            # never acknowledge a reply merely because native streaming ended.
            with tempfile.TemporaryDirectory(prefix='miaiplus-owned-reply-') as directory:
                screenshot = Path(directory) / 'reply.png'
                for attempt in range(8):
                    screenshot.write_bytes(subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'], timeout=10))
                    proof = subprocess.run(['swift', str(Path(__file__).with_name('verify_reply_screenshot.swift')),
                                            str(screenshot), expected], capture_output=True, timeout=20)
                    if proof.returncode == 0:
                        adb('shell', 'am', 'broadcast', '-a', 'io.github.miaiplus.CONVERSATION_SCREENSHOT_VERIFIED',
                            '-p', 'io.github.miaiplus', '--es', 'nonce', nonce)
                        print('CONVERSATION_REPLY_PIXELS ' + stage + ': unique reply verified', flush=True)
                        break
                    if proof.returncode != 1:
                        raise RuntimeError('owned reply screenshot verifier unavailable')
                    time.sleep(0.3)
                else:
                    print('FAIL: owned reply was not recognized in screen pixels: ' + stage, flush=True)
                    failed = True
        if '--action-confirmation' in sys.argv and 'MIAIPLUS_CONFIRMATION_SCREENSHOT' in line:
            # Issued only while the owned fixture and fixed harmless confirmation are visible.
            image_path = Path('work/action-confirmation-island.png')
            image_path.parent.mkdir(parents=True, exist_ok=True)
            image_path.write_bytes(subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'], timeout=10))
        if 'PASS:' in line or 'PROBE PASS' in line:
            passed = True
        if 'FAIL:' in line or 'INSTRUMENTATION_FAILED' in line:
            failed = True
    process.wait()
finally:
    # The launch fixture may still be foreground when the compact completion island appears.
    # Close its task even on test failure so the next user task cannot reveal it.
    subprocess.run(['adb', 'shell', 'am', 'broadcast', '-a', 'io.github.miaiplus.CLOSE_LAUNCH_FIXTURE',
                    '-p', 'io.github.miaiplus.test'], check=False, capture_output=True, timeout=10)
    if process.poll() is None:
        process.terminate()
sys.exit(0 if passed and not failed and process.returncode == 0 else 1)
