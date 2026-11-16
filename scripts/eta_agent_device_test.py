#!/usr/bin/env python3
"""Check the Eta relay on a real device through the debug-only probe inside XiaoAi.

Needs: the debug MiAIPlus build active for XiaoAi, Eta installed with its own model configured,
"Eta Agent" enabled on the MiAIPlus plugin page, and an unlocked screen. Nothing is installed or
configured here. Every case starts a real Eta task on Eta's own model, so it spends the user's Eta quota.

  --restart-host   also force-stop XiaoAi during a task to check that the leftover result is acknowledged
  --route          also type real requests into XiaoAi's own model and report whether it delegated to Eta
  --screen         switch the Phone Use action protocol to Eta, check that screen requests go to Eta and
                   that MiAIPlus's own screen tools are gone, then restore the previous protocol
"""
import argparse
import json
import queue
import shlex
import subprocess
import sys
import threading
import time
import uuid

ACTION = "io.github.miaiplus.PLUGIN_PROBE"
SHORT = "读取当前电量，用一句话回答。"
LONG = ("用浏览器工具依次打开 https://example.com 、https://www.iana.org/help/example-domains 和 "
        "https://www.rfc-editor.org/rfc/rfc2606 ，逐个总结要点，最后写一份对比。")
# (request given to XiaoAi's agent, whether its model should hand it to Eta, whether MiAIPlus's own rule
# sends such a typed or spoken request to the agent in the first place)
# XiaoAi keeps one conversation across these, so requests that must not delegate go first: an earlier Eta
# request left in the history would otherwise be picked up by a later, unrelated turn.
ROUTES = [
    ("一加一等于几", False, False),
    ("把这句话翻译成英文：今天天气不错", False, False),
    ("现在手机电量是多少", False, False),
    ("航班信息里的 ETA 是什么意思", False, False),
    # XiaoAi's own cli tool reads app usage and notifications in one or two rounds; that is the cheaper answer.
    ("看看我今天手机用了多久，哪些应用用得最多", False, False),
    ("帮我深度调研 2026 年国产旗舰手机的影像能力，至少对比五篇不同网站的评测，整理成一份报告", True, False),
    ("让 Eta 统计一下今天哪些应用给我发的通知最多", True, True),
]


# With the action protocol set to Eta: (request, should reach Eta, let Eta finish and expect this in its answer)
SCREEN_ROUTES = [
    ("一加一等于几", False, None),
    ("打开计算器，算一下 12 乘 34 等于多少", True, "408"),
    ("打开设置，搜索蓝牙", True, None),
]
PROTOCOL_ACTION = "io.github.miaiplus.PROTOCOL_PROBE"


class Device:
    def __init__(self, adb, serial):
        self.base = [adb] + (["-s", serial] if serial else [])
        self.lines = queue.Queue()
        self.reader = None

    def shell(self, command, check=True):
        done = subprocess.run(self.base + ["shell", command], capture_output=True, text=True, timeout=60)
        if check and done.returncode != 0:
            raise RuntimeError(f"adb shell failed: {done.stderr.strip() or done.stdout.strip()}")
        return done.stdout.strip()

    def protocol(self, value=None):
        """Sets the action protocol in the module process (debug receiver); returns the previous value."""
        extra = f" --es protocol {shlex.quote(value)}" if value else ""
        out = self.shell(f"am broadcast -n io.github.miaiplus/.ProtocolProbe -a {PROTOCOL_ACTION}{extra}")
        if "result=0" not in out:
            raise RuntimeError("protocol probe failed: " + out)
        return out.split("previous=", 1)[1].split()[0]

    def listen(self):
        self.reader = subprocess.Popen(self.base + ["logcat", "-T", "1", "-v", "raw", "-s", "MiAIPlusEtaProbe:I"],
                                       stdout=subprocess.PIPE, text=True, errors="replace")
        threading.Thread(target=lambda: [self.lines.put(line) for line in self.reader.stdout], daemon=True).start()
        time.sleep(1)

    def close(self):
        if self.reader:
            self.reader.terminate()

    def probe(self, mode, arguments, tool=None, timeout=260):
        nonce = uuid.uuid4().hex
        extras = ["--es", "mode", mode, "--es", "nonce", nonce, "--es", "arguments", json.dumps(arguments, ensure_ascii=False)]
        if tool:
            extras += ["--es", "tool", tool]
        self.shell("am broadcast -a " + ACTION + " " + " ".join(shlex.quote(part) for part in extras))
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                line = self.lines.get(timeout=1).strip()
            except queue.Empty:
                continue
            if nonce not in line:
                continue
            if line.startswith("ETA_PROBE "):
                return json.loads(line[len("ETA_PROBE "):])
            return {"error": line}
        return {"error": "no probe report; is the debug build active in XiaoAi?"}


def run(device, task, wait):
    return device.probe("eta", {"task": task, "wait_seconds": wait}, "miaiplus_eta_run")


def status(device, **arguments):
    return device.probe("eta", arguments, "miaiplus_eta_status")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--restart-host", action="store_true")
    parser.add_argument("--route", action="store_true")
    parser.add_argument("--only-route", action="store_true", help="skip the relay cases")
    parser.add_argument("--screen", action="store_true", help="only the Eta action-protocol cases")
    options = parser.parse_args()
    device = Device(options.adb, options.serial)
    failures = []

    def check(name, report, ok, detail=""):
        text = report.get("text", report.get("error", ""))
        print(("PASS " if ok else "FAIL ") + name + (f" [{report.get('ms')}ms]" if "ms" in report else "")
              + (" " + detail if detail else "") + ("" if ok else " :: " + text[:300].replace("\n", " ")), flush=True)
        if not ok:
            failures.append(name)

    def ending(report):
        return report.get("text", "").split("：", 1)[0]

    device.listen()
    try:
        if not options.only_route and not options.screen:
            done = run(device, SHORT, 90)
            check("delegation returns Eta's answer", done, ending(done) == "DONE" and not done.get("failure"),
                  "prompt policy injected=" + str(done.get("policy")))
            if "error" in done:
                return 1
            # A second caller is used for the concurrent request, so the first one is still waiting.
            first = {}
            waiter = threading.Thread(target=lambda: first.update(run(device, LONG, 12)))
            waiter.start()
            time.sleep(4)
            busy = Device(options.adb, options.serial)
            busy.listen()
            second = run(busy, SHORT, 10)
            busy.close()
            waiter.join()
            check("second task while one runs is refused", second, ending(second) == "BUSY")
            check("long task reports RUNNING with progress", first, ending(first) == "RUNNING" and "run_id=" in first.get("text", ""))
            finished = status(device, wait_seconds=200)
            check("status waits for the running task", finished, ending(finished) == "DONE")

            waiter = threading.Thread(target=lambda: first.update(run(device, LONG, 10)))
            waiter.start()
            waiter.join()
            stopped = status(device, cancel=True)
            check("cancel stops the task in Eta", stopped, ending(stopped) == "STOP" and stopped.get("failure") is True)
            after = run(device, SHORT, 90)
            check("a new task runs after a cancel", after, ending(after) == "DONE")

            if options.restart_host:
                before = after.get("leftovers", 0)
                threading.Thread(target=lambda: run(device, LONG, 10), daemon=True).start()
                time.sleep(6)
                device.shell("am force-stop com.miui.voiceassist")
                print("XiaoAi force-stopped during an Eta task; waiting for Eta to finish on its own", flush=True)
                time.sleep(75)
                for _ in range(30):
                    if device.shell("pidof com.miui.voiceassist", check=False):
                        break
                    device.shell("monkey -p com.miui.voiceassist -c android.intent.category.LAUNCHER 1", check=False)
                    time.sleep(2)
                time.sleep(6)
                run(device, SHORT, 90)  # The first connection of the new process sweeps; its count shows next time.
                swept = run(device, SHORT, 90)
                check("leftover result of the killed host is acknowledged", swept,
                      ending(swept) == "DONE" and swept.get("leftovers", 0) >= 1, f"leftovers={swept.get('leftovers')} (was {before})")

        if options.screen:
            previous = device.protocol("eta")
            print(f"protocol set to eta (was {previous})", flush=True)
            time.sleep(3)
            try:
                for query, expected, answer in SCREEN_ROUTES:
                    finish = 240 if answer else 5
                    arguments = {"query": query, "decide_seconds": 120 if expected else 60, "finish_seconds": finish}
                    report = device.probe("eta_route", arguments, timeout=arguments["decide_seconds"] + finish + 90)
                    if "sender unavailable" in report.get("error", ""):
                        width, height = map(int, device.shell("wm size").split()[-1].split("x"))
                        device.shell(f"input keyevent 3; sleep 1; input tap {width // 2} {int(height * 0.814)}; sleep 3; "
                                     "input keyevent 4; sleep 1; input keyevent 4", check=False)
                        report = device.probe("eta_route", arguments, timeout=arguments["decide_seconds"] + finish + 90)
                    phone = [name for name in report.get("tools") or [] if "phone_use" in name]
                    ok = (report.get("delegated") is expected and not phone and report.get("etaScreen") is True
                          and report.get("phoneTools") is False)
                    check(("eta screen: " if expected else "eta screen keeps: ") + query, report, ok,
                          f"delegated={report.get('delegated')} decided={report.get('decidedMs')}ms rule={report.get('rule')} "
                          f"rounds={report.get('modelRounds')} tools={report.get('tools')} etaScreen={report.get('etaScreen')} "
                          f"phoneTools={report.get('phoneTools')} task={report.get('task')!r}")
                    if report.get("etaStillRunning"):
                        status(device, cancel=True, wait_seconds=30)
                    elif answer and report.get("delegated"):
                        done = status(device, wait_seconds=10)
                        check("eta screen answer mentions " + answer, done, answer in done.get("text", ""),
                              done.get("text", "")[:200].replace("\n", " "))
                    device.shell("input keyevent 3", check=False)
                    time.sleep(8)
            finally:
                device.protocol(previous)
                print(f"protocol restored to {previous}", flush=True)

        if options.route or options.only_route:
            for query, expected, forced in ROUTES:
                # Only the decision matters here; a delegated task is stopped at once below.
                arguments = {"query": query, "decide_seconds": 150 if expected else 60, "finish_seconds": 5}
                report = device.probe("eta_route", arguments, timeout=400)
                if "sender unavailable" in report.get("error", ""):
                    # XiaoAi builds its input view on first use; show the assistant once, then leave it.
                    width, height = map(int, device.shell("wm size").split()[-1].split("x"))
                    device.shell(f"input keyevent 3; sleep 1; input tap {width // 2} {int(height * 0.814)}; sleep 3; "
                                 "input keyevent 4; sleep 1; input keyevent 4", check=False)
                    report = device.probe("eta_route", arguments, timeout=400)
                if report.get("modelRounds") == 0:
                    # The request never reached a tool-capable model in the window; XiaoAi kept it on its
                    # ordinary path or was still busy. That says nothing about the model's choice.
                    print(f"SKIP {query}: no model round in the window (rule={report.get('rule')})", flush=True)
                    time.sleep(8)
                    continue
                got = report.get("delegated")
                check(("delegates: " if expected else "keeps: ") + query, report,
                      got is expected and report.get("forced") is forced,
                      f"delegated={got} decided={report.get('decidedMs')}ms rule={report.get('rule')} "
                      f"forcedToAgent={report.get('forced')} rounds={report.get('modelRounds')} tools={report.get('tools')} "
                      f"task={report.get('task')!r}")
                if any("phone_use" in name for name in report.get("tools", [])):
                    # XiaoAi began a screen task of its own instead; end it so it cannot keep operating the
                    # device or run into the next case.
                    device.shell("am force-stop com.miui.voiceassist; sleep 3; input keyevent 3", check=False)
                if report.get("etaStillRunning"):
                    # Stop the delegated task so it neither spends Eta's model quota nor blocks the next case.
                    status(device, cancel=True, wait_seconds=30)
                time.sleep(8)
    finally:
        device.close()
    print(("FAILED: " + "; ".join(failures)) if failures else "ALL PASSED")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
