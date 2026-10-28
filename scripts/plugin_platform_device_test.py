#!/usr/bin/env python3
"""Run isolated Pluginify HTTP/Provider checks, optionally exercising native XiaoAi proxies.

The mainline package is refused. Only this script's mock process, reverse mapping
and optional accessibility-setting changes are cleaned up. LSPosed scope is never
changed here: enable only the Pluginify module for XiaoAi before using --host.
"""
import argparse
import json
import queue
import re
import shlex
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
FIXTURE = "miaiplus-plugin-platform-fixture-v1"
PROTOCOL = "2025-11-25"


def default_package():
    metadata = ROOT / "app/build/outputs/apk/debug/output-metadata.json"
    if metadata.exists():
        try:
            package = json.loads(metadata.read_text())["applicationId"]
            if package != "io.github.miaiplus":
                return package
        except (OSError, KeyError, ValueError, TypeError):
            pass
    match = re.search(r"\bapplicationId\s+['\"]([^'\"]+)['\"]", (ROOT / "app/build.gradle").read_text())
    return match.group(1) if match else None


def read_lines(stream, output, kind):
    try:
        for line in iter(stream.readline, ""):
            output.put((kind, line))
    finally:
        output.put((kind + "_closed", ""))


def json_status(port, path):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=3) as response:
        body = response.read(16385)
        if len(body) > 16384:
            raise RuntimeError("fixture status is too large")
        return json.loads(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", default=default_package(), help="target app id; defaults to debug metadata or Gradle applicationId")
    parser.add_argument("--runner", default="io.github.miaiplus.DeviceTestRunner")
    parser.add_argument("--serial", help="ADB device serial")
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--token", default="plugin-device-fixture-token", help="synthetic fixture token, never a production credential")
    parser.add_argument("--no-mock", action="store_true", help="reuse an explicitly started mock_mcp.py on this port")
    parser.add_argument("--host", action="store_true", help="restart XiaoAi and invoke the Pluginify-only native probe")
    parser.add_argument("--no-restart", action="store_true", help="reuse an already initialized XiaoAi process for --host")
    parser.add_argument("--phone", choices=("none", "foreground", "background"), default="none",
                        help="optional owned-fixture/empty-virtual-display observation only; requires --host")
    parser.add_argument("--timeout", type=int, default=360)
    args = parser.parse_args()
    if not args.package or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        parser.error("a valid isolated --package is required")
    if args.package == "io.github.miaiplus":
        parser.error("refusing to run fixture preference changes against the mainline package")
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", args.runner):
        parser.error("runner must be a Java class name")
    if not 1 <= args.port <= 65535 or args.timeout < 60:
        parser.error("port or timeout is out of range")
    if args.phone != "none" and not args.host:
        parser.error("--phone requires --host")
    if not args.token or any(ord(char) <= 32 or ord(char) >= 127 for char in args.token):
        parser.error("fixture token must contain printable non-space ASCII")

    adb = ["adb"] + (["-s", args.serial] if args.serial else [])

    def run_adb(*command, timeout=30):
        invocation = [*adb, "shell", shlex.join(command[1:])] if command[0] == "shell" else [*adb, *command]
        result = subprocess.run(invocation, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError("ADB " + command[0] + " failed: " + result.stdout.replace(args.token, "[redacted]")[-1000:])
        return result.stdout.strip()

    output = queue.Queue()
    mock = instrument = None
    reverse_created = False
    accessibility_saved = None
    accessibility_flag_saved = None
    service_rebound = False
    host_triggered = False
    passed = failed = restored = False
    result_code = 1
    nonce = str(uuid.uuid4())
    instance = str(uuid.uuid4())
    reverse_port = f"tcp:{args.port}"
    try:
        run_adb("get-state")
        if run_adb("shell", "am", "get-current-user") != "0":
            raise RuntimeError("this isolated device harness requires the current Android user to be owner 0")
        if not args.no_mock:
            mock = subprocess.Popen([sys.executable, "-u", str(ROOT / "scripts/mock_mcp.py"), "--port", str(args.port),
                                     "--token", args.token, "--instance", instance], stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, bufsize=1)
            threading.Thread(target=read_lines, args=(mock.stdout, output, "mock"), daemon=True).start()
        ready_deadline = time.monotonic() + 10
        while True:
            if mock is not None and mock.poll() is not None:
                raise RuntimeError("owned mock server failed to start; an occupied port is not replaced")
            try:
                health = json_status(args.port, "/health")
                if health.get("fixture") != FIXTURE or health.get("protocolVersion") != PROTOCOL:
                    raise RuntimeError("the selected port is not the expected owned MCP fixture")
                if mock is not None and health.get("instance") != instance:
                    raise RuntimeError("the selected port belongs to another fixture process")
                break
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
                if time.monotonic() >= ready_deadline:
                    raise RuntimeError("owned MCP fixture did not become ready")
                time.sleep(0.1)

        mappings = run_adb("reverse", "--list")
        mapping_exists = False
        for line in mappings.splitlines():
            fields = line.split()
            if len(fields) >= 2 and fields[-2] == reverse_port:
                if fields[-1] != reverse_port:
                    raise RuntimeError("the requested reverse port already has a different mapping")
                mapping_exists = True
        if not mapping_exists:
            run_adb("reverse", reverse_port, reverse_port)
            reverse_created = True
        if args.phone != "none":
            saved_services = run_adb("shell", "settings", "--user", "0", "get", "secure", "enabled_accessibility_services")
            saved_flag = run_adb("shell", "settings", "--user", "0", "get", "secure", "accessibility_enabled")
            accessibility_saved, accessibility_flag_saved = saved_services, saved_flag

        print("PLUGIN_PLATFORM_TEST package=" + args.package + " host=" + str(args.host).lower()
              + " phone=" + args.phone, flush=True)
        instrument_args = ["am", "instrument", "--user", "0", "-w", "-e", "plugin_platform", "true",
                           "-e", "plugin_host", str(args.host).lower(), "-e", "plugin_phone", args.phone,
                           "-e", "plugin_nonce", nonce, "-e", "mcp_endpoint", f"http://127.0.0.1:{args.port}/mcp",
                           "-e", "mcp_token", args.token, args.package + ".test/" + args.runner]
        command = [*adb, "shell", shlex.join(instrument_args)]
        instrument = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=read_lines, args=(instrument.stdout, output, "instrument"), daemon=True).start()
        deadline = time.monotonic() + args.timeout
        instrument_closed = False
        while not instrument_closed:
            if time.monotonic() >= deadline:
                raise RuntimeError("instrumentation exceeded its bounded test deadline")
            try:
                kind, line = output.get(timeout=0.25)
            except queue.Empty:
                continue
            if kind == "instrument_closed":
                instrument_closed = True
                continue
            if kind.endswith("_closed"):
                continue
            print(line.replace(args.token, "[redacted]"), end="", flush=True)
            if kind != "instrument":
                continue
            if "FAIL:" in line or "INSTRUMENTATION_FAILED" in line or "INSTRUMENTATION_ABORTED" in line:
                failed = True
            if "PASS: Plugin platform" in line:
                passed = True
            if "PLUGIN_PLATFORM_RESTORED package=" + args.package in line:
                restored = True
            connect = re.search(r"PLUGIN_PLATFORM_CONNECT_SERVICE\s+([a-f0-9-]{36})", line)
            if connect and args.phone != "none" and not service_rebound:
                if connect.group(1) != nonce:
                    raise RuntimeError("accessibility handshake belongs to another run")
                current = run_adb("shell", "settings", "--user", "0", "get", "secure", "enabled_accessibility_services")
                own_service = args.package + "/io.github.miaiplus.ScreenService"
                own_components = {own_service, args.package + "/.ScreenService"}
                services = [service for service in current.split(":")
                            if service and service != "null" and service not in own_components]
                run_adb("shell", "settings", "--user", "0", "put", "secure", "enabled_accessibility_services", ":".join(services))
                services.append(own_service)
                run_adb("shell", "settings", "--user", "0", "put", "secure", "enabled_accessibility_services", ":".join(services))
                run_adb("shell", "settings", "--user", "0", "put", "secure", "accessibility_enabled", "1")
                service_rebound = True
            match = re.search(r"PLUGIN_PLATFORM_HOST_READY\s+(miaiplus_mcp_[a-f0-9_]+)\s+([a-f0-9-]{36})", line)
            if match and args.host and not host_triggered:
                tool_name, report_nonce = match.groups()
                if report_nonce != nonce:
                    raise RuntimeError("native probe handshake belongs to another run")
                if args.phone != "none" and not service_rebound:
                    raise RuntimeError("native phone probe did not reconnect its owned accessibility service")
                host_triggered = True
                if not args.no_restart:
                    run_adb("shell", "am", "force-stop", "--user", "0", "com.miui.voiceassist")
                if args.phone != "none":
                    run_adb("shell", "am", "start", "--user", "0", "-W", "-n", args.package + "/io.github.miaiplus.FixtureActivity")
                run_adb("shell", "am", "start", "--user", "0", "-W", "-n",
                        "com.miui.voiceassist/com.xiaomi.voiceassistant.LaunchHomeRouterActivity")
                time.sleep(3)
                run_adb("shell", "am", "broadcast", "-a", args.package + ".PLUGIN_PROBE", "-p", "com.miui.voiceassist",
                        "--es", "tool", tool_name, "--es", "nonce", nonce, "--es", "phone", args.phone)
                print("PLUGIN_PLATFORM_HOST_TRIGGERED nonce=" + nonce, flush=True)
        instrument.wait(timeout=10)
        if args.host and not host_triggered:
            failed = True
        print("PLUGIN_PLATFORM_FIXTURE_STATS " + json.dumps(json_status(args.port, "/stats"), sort_keys=True), flush=True)
        result_code = 0 if passed and restored and not failed and instrument.returncode == 0 else 1
    except (RuntimeError, subprocess.SubprocessError, OSError, ValueError) as error:
        print("FAIL: " + str(error).replace(args.token, "[redacted]"), file=sys.stderr, flush=True)
        result_code = 1
    finally:
        if instrument is not None and instrument.poll() is None:
            # Stop only our ADB client. The runner has its own bounded waits and restores target prefs.
            instrument.terminate()
            try:
                instrument.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        if accessibility_saved is not None:
            for key, saved in (("enabled_accessibility_services", accessibility_saved),
                               ("accessibility_enabled", accessibility_flag_saved)):
                try:
                    if saved == "null":
                        run_adb("shell", "settings", "--user", "0", "delete", "secure", key)
                    else:
                        run_adb("shell", "settings", "--user", "0", "put", "secure", key, saved)
                except (RuntimeError, subprocess.SubprocessError, OSError):
                    result_code = 1
                    print("FAIL: restore of the owned accessibility test setting needs attention: " + key,
                          file=sys.stderr, flush=True)
        if reverse_created:
            try:
                run_adb("reverse", "--remove", reverse_port)
            except (RuntimeError, subprocess.SubprocessError, OSError):
                result_code = 1
                print("FAIL: owned reverse mapping cleanup needs attention: " + reverse_port, file=sys.stderr, flush=True)
        if mock is not None and mock.poll() is None:
            mock.terminate()
            try:
                mock.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
    return result_code


if __name__ == "__main__":
    sys.exit(main())
