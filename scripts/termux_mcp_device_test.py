#!/usr/bin/env python3
"""Exercise an existing phone-local upstream MCP shell through the independent APK.

This script does not install/start a server, replace it with a mock, or change
LSPosed scopes. Start the reviewed upstream service separately. --host requires
the independent debug module to be the sole enabled module for XiaoAI.
"""

import argparse
import queue
import re
import shlex
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="io.github.miaiplus.pluginify")
    parser.add_argument("--endpoint", required=True, help="Phone-local 127.0.0.1 HTTP MCP endpoint")
    parser.add_argument("--token-file", required=True, type=Path)
    parser.add_argument("--protocol", choices=("2025-11-25", "2025-06-18"), default="2025-06-18")
    parser.add_argument("--tool", default="run_digest")
    parser.add_argument("--command-field", default="cmd")
    parser.add_argument("--uid", required=True, type=int, help="Actual non-root Termux application UID")
    parser.add_argument("--termux-home", default="/data/data/com.termux/files/home")
    parser.add_argument("--host", action="store_true", help="Also probe the actual XiaoAI tool proxy; restarts XiaoAI")
    parser.add_argument("--timeout", type=int, default=540)
    args = parser.parse_args()
    if args.package == "io.github.miaiplus" or not re.fullmatch(r"[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+", args.package):
        parser.error("an independent application ID is required")
    endpoint = urlsplit(args.endpoint)
    if (endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1" or not endpoint.port
            or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment):
        parser.error("only an explicitly selected phone-loopback HTTP endpoint is accepted")
    if args.uid < 10000 or args.timeout < 60:
        parser.error("a non-root Android app UID and at least 60 seconds are required")
    token = args.token_file.read_text().strip()
    if not token or "\n" in token or "\r" in token:
        parser.error("token file must contain one non-empty token")
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])

    def run_adb(*parts):
        result = subprocess.run([*adb, *parts], check=True, text=True, capture_output=True, timeout=30)
        return result.stdout.strip()

    if run_adb("shell", "am", "get-current-user") != "0":
        parser.error("this acceptance runner only supports Android owner user 0")
    nonce = str(uuid.uuid4())
    command = ["am", "instrument", "-w", "-r", "--user", "0"]
    parameters = {
        "termux_mcp": "true", "mcp_endpoint": args.endpoint, "mcp_token": token,
        "mcp_protocol": args.protocol, "mcp_source_tool": args.tool,
        "mcp_command_field": args.command_field, "termux_uid": str(args.uid),
        "termux_home": args.termux_home, "plugin_nonce": nonce,
        "termux_host": str(args.host).lower(),
    }
    for key, value in parameters.items():
        command.extend(("-e", key, value))
    command.append(args.package + ".test/io.github.miaiplus.DeviceTestRunner")
    process = subprocess.Popen([*adb, "shell", shlex.join(command)], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    output = queue.Queue()

    def read_lines():
        try:
            for line in process.stdout:
                output.put(line)
        finally:
            output.put(None)

    threading.Thread(target=read_lines, daemon=True).start()
    deadline = time.monotonic() + args.timeout
    passed = restored = finished = host_triggered = host_passed = host_exited = host_report_restored = False
    host_cancelled = False
    failed = False
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("device runner exceeded its deadline; inspect device cleanup before continuing")
            try:
                line = output.get(timeout=min(0.25, remaining))
            except queue.Empty:
                continue
            if line is None:
                break
            print(line.replace(token, "[redacted]"), end="", flush=True)
            failed |= any(marker in line for marker in ("FAIL:", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED"))
            passed |= "PASS: Termux MCP real upstream" in line
            restored |= "TERMUX_MCP_RESTORED package=" + args.package in line and "nonce=" + nonce in line
            finished |= "INSTRUMENTATION_CODE: -1" in line
            host_passed |= "TERMUX_MCP_STAGE native_host_proxy=true" in line and "nonce=" + nonce in line
            host_exited |= "TERMUX_MCP_HOST_EXIT_CONFIRMED" in line and "nonce=" + nonce in line
            host_report_restored |= ("TERMUX_MCP_HOST_REPORT_RESTORED" in line and "nonce=" + nonce in line
                                     and "exact_report_key=true" in line and "exact_hook_at=true" in line)
            cancel = re.search(r"TERMUX_MCP_HOST_CANCEL\s+([a-f0-9-]{36})", line)
            if cancel and args.host and not host_cancelled:
                if cancel.group(1) != nonce:
                    raise RuntimeError("host cancellation belongs to another run")
                host_cancelled = True
                run_adb("shell", "am", "broadcast", "-a", args.package + ".PLUGIN_PROBE", "-p", "com.miui.voiceassist",
                        "--es", "mode", "termux_cancel", "--es", "nonce", nonce)
            match = re.search(r"TERMUX_MCP_HOST_READY\s+(miaiplus_mcp_[a-f0-9_]+)\s+([a-f0-9-]{36})", line)
            if match and args.host and not host_triggered:
                name, report_nonce = match.groups()
                if report_nonce != nonce:
                    raise RuntimeError("host handshake belongs to another run")
                host_triggered = True
                run_adb("shell", "am", "force-stop", "--user", "0", "com.miui.voiceassist")
                run_adb("shell", "am", "start", "--user", "0", "-W", "-n",
                        "com.miui.voiceassist/com.xiaomi.voiceassistant.LaunchHomeRouterActivity")
                time.sleep(3)
                run_adb("shell", "am", "broadcast", "-a", args.package + ".PLUGIN_PROBE", "-p", "com.miui.voiceassist",
                        "--es", "mode", "termux", "--es", "tool", name, "--es", "nonce", nonce,
                        "--es", "expected_uid", str(args.uid))
        process.wait(timeout=10)
        ok = (passed and restored and finished and not failed and process.returncode == 0
              and (not args.host or (host_triggered and host_passed and host_exited and host_report_restored)))
        print(("PASS" if ok else "FAIL") + ": real Termux MCP acceptance nonce=" + nonce)
        return 0 if ok else 1
    except (OSError, RuntimeError, TimeoutError, subprocess.SubprocessError) as error:
        print("FAIL: " + str(error).replace(token, "[redacted]"), file=sys.stderr)
        return 1
    finally:
        # Only close this script's adb client. Never kill the server or another
        # app/task to hide failed cleanup; the device runner owns its finally.
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


if __name__ == "__main__":
    sys.exit(main())
