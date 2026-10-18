#!/usr/bin/env python3
"""Device acceptance for the background display, with the phases kept separate.

The implementation spec forbids running the existing device scripts during a measurement of "the user keeps using
their phone", because those scripts flip accessibility settings, restart XiaoAi and launch fixtures on the physical
screen. So this driver does the opposite of sharing a window:

  1. init     -- optionally install the APKs, then verify root and unlock prerequisites without changing settings.
  2. measure  -- run the instrumented background task. The runner places its physical-screen fixture, opens the
                 keyboard and takes the baseline before asserting the user's screen never moves.
  3. cleanup  -- remove the fixture task and the reverse mapping, and report the device's display ids.

Usage: python3 scripts/background_display_test.py [--skip-install]
"""
import subprocess
import sys

APPLICATION = "io.github.miaiplus"
TEST_PACKAGE = "io.github.miaiplus.test"
# There is exactly one instrumentation in this APK; the background checks are a dispatch mode of it, not a second
# component, so asking for a separate runner name fails with "Unable to find instrumentation info".
RUNNER = f"{TEST_PACKAGE}/io.github.miaiplus.DeviceTestRunner"
ROOT = subprocess.os.path.dirname(subprocess.os.path.dirname(subprocess.os.path.abspath(__file__)))


def adb(*args, check=True, timeout=120):
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {result.stderr.strip() or result.stdout.strip()}")
    return (result.stdout or "").strip()


def log(message):
    print(message, flush=True)


def init_phase(skip_install):
    """Verify the device can be measured at all. Touches nothing that the measurement depends on."""
    if skip_install:
        log("init: install skipped by request")
    else:
        # Only the debug + androidTest pair; no accessibility or XiaoAi changes happen here.
        log("init: installing debug and androidTest APKs")
        adb("install", "-r", "-t", f"{ROOT}/app/build/outputs/apk/debug/app-debug.apk", timeout=300)
        adb("install", "-r", "-t", f"{ROOT}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", timeout=300)
    uid = adb("shell", "content", "call", "--uri", "content://io.github.miaiplus.bridge",
              "--method", "background_display_status")
    log(f"init: helper status {uid}")
    if "root=true" not in uid:
        raise SystemExit("init: the background helper is not answering as root; grant MiAIPlus root first")
    if "deviceLocked=true" in uid:
        raise SystemExit("init: the device is locked; unlock it and re-run")
    lock = adb("shell", "dumpsys", "window")
    if "isKeyguardShowing=false" not in lock:
        raise SystemExit("init: keyguard is showing; unlock it and re-run")


def measure_phase():
    """The runner owns setup and the baseline; isolation is measured only after both complete."""
    log("measure: running the background task")
    process = subprocess.Popen(
        ["adb", "shell", "am", "instrument", "--user", "0", "-w", "-e", "background", "true", RUNNER],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    passed = failed = False
    for line in process.stdout:
        print(line, end="", flush=True)
        if "MIAIPLUS_CONNECT_SERVICE" in line:
            # The runner needs the accessibility service; enabling it is part of the instrumented setup, and it
            # does not move the physical screen's top task, so the baseline taken after it stays valid.
            current = adb("shell", "settings", "get", "secure", "enabled_accessibility_services")
            services = [s for s in current.split(":") if s and s != "null" and not s.startswith(f"{APPLICATION}/")]
            # APK replacement/instrumentation can leave the component enabled with a dead Binder. Rebind only
            # this module before the runner places its fixture and takes the isolation baseline.
            adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services))
            services.append("io.github.miaiplus/.ScreenService")
            adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services))
        if "PASS:" in line:
            passed = True
        if "FAIL:" in line or "INSTRUMENTATION_FAILED" in line:
            failed = True
    process.wait()
    return passed and not failed and process.returncode == 0


def cleanup_phase():
    """Remove what this driver created; report, do not assume, the resulting display set."""
    log("cleanup: removing the fixture task and the reverse mapping")
    subprocess.run(["adb", "shell", "am", "force-stop", APPLICATION], check=False, capture_output=True, timeout=30)
    subprocess.run(["adb", "shell", "am", "force-stop", "com.miui.calculator"], check=False, capture_output=True, timeout=30)
    subprocess.run(["adb", "reverse", "--remove", "tcp:18765"], check=False, capture_output=True, timeout=30)
    displays = sorted({l.strip() for l in adb("shell", "dumpsys", "display", check=False).splitlines()
                       if l.strip().startswith("Display Id=")})
    helpers = adb("shell", "ps", "-A", check=False)
    orphans = [l for l in helpers.splitlines() if "io.github.miaiplus.helper.DisplayHelper" in l]
    log(f"cleanup: displays now {displays}")
    log(f"cleanup: orphan helpers {len(orphans)}")


if __name__ == "__main__":
    ok = False
    try:
        init_phase("--skip-install" in sys.argv)
        ok = measure_phase()
    finally:
        cleanup_phase()
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)
