#!/usr/bin/env python3
"""Deterministic protocol fixture; binds only loopback, served to Android using adb reverse.
Never logs request content, images, or Authorization headers.
"""
import json
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.respond({"data": [{"id": "fixture-model"}]})

    def respond(self, body, status=200):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        try:
            assert self.path == "/v1/chat/completions", "wrong completion path"
            assert self.headers.get("Authorization") == "Bearer test-key", "missing expected test auth (present=%s, count=%s)" % (self.headers.get("Authorization") is not None, len(self.headers.get_all("Authorization", [])))
            forbidden = ("cookie", "x-xiaomi", "x-mi-", "x-device", "x-osbot", "service-token", "x-model-provider-id")
            assert not any(any(name.lower().startswith(prefix) for prefix in forbidden) for name in self.headers), "Xiaomi account/device headers leaked"
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            messages = request["messages"]
            task = next(m["content"] for m in messages if m["role"] == "user")
            if task in ("MIAIPLUS_GATE_TEST", "MIAIPLUS_CAPABILITY_TEST", "MIAIPLUS_ROUTER_TEST"):
                assert request["model"] == "fixture-model" and request["stream"] is False
                assert not any(k in request for k in ("rid", "intent_protocol", "routing_profile", "context"))
                assert messages[0]["role"] == "system"
                content = {"MIAIPLUS_GATE_TEST": "gate-ok", "MIAIPLUS_CAPABILITY_TEST": "capability-ok",
                           "MIAIPLUS_ROUTER_TEST": '{"status":"FALLBACK","direct_path":false,"difficulty":"easy","reason":"fixture route"}'}[task]
                self.respond({"choices": [{"message": {"role": "assistant", "content": content}}]})
                return
            if task == "MIAIPLUS_MEMORY_TEST":
                assert request["model"] == "fixture-model" and request["stream"] is False
                assert any(m["role"] == "system" and m["content"] == "MIAIPLUS_MEMORY_SYSTEM" for m in messages)
                self.respond({"choices": [{"message": {"role": "assistant", "content": "memory-ok"}}]})
                return
            if task in ("MIAIPLUS_NATIVE_STREAM_TEST", "MIAIPLUS_NATIVE_STREAM_FINAL"):
                assert request["model"] == "fixture-model", "global model override missing"
                assert request["stream"] is True, "stock native SSE transport missing"
                assert any(m["role"] == "system" and "MIAIPLUS_NATIVE_SYSTEM" in m["content"] for m in messages), "native prompt lost"
                if task.endswith("_TEST"):
                    probe = next((t["function"] for t in request["tools"] if t["function"]["name"] == "probe_tool"), None)
                    assert probe is not None and probe["parameters"]["type"] == "object", "native tools lost"
                    delta = {"tool_calls": [{"index": 0, "id": "probe-call", "type": "function", "function": {"name": "probe_tool", "arguments": "{}"}}]}
                    reason = "tool_calls"
                else:
                    delta = {"content": "native-stream-ok"}
                    reason = "stop"
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Connection", "close")
                self.end_headers()
                for change, finish in [({"role": "assistant"}, None), (delta, None), ({}, reason)]:
                    payload = {"id": "probe-response", "object": "chat.completion.chunk", "created": 1, "model": "fixture-model", "choices": [{"index": 0, "delta": change, "finish_reason": finish}]}
                    self.wfile.write(("data: " + json.dumps(payload) + "\n\n").encode())
                self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush(); self.close_connection = True
                return
            if task == "MIAIPLUS_CHAT_TEST":
                assert "tools" not in request
                assert all(isinstance(m["content"], str) for m in messages)
                message = {"role": "assistant", "content": "chat-ok"}
            elif task == "MIAIPLUS_CANCEL_TEST":
                time.sleep(8)
                message = {"role": "assistant", "content": "should-be-cancelled"}
            else:
                match = re.fullmatch(r"MIAIPLUS_SCREEN_TEST (computer|autoglm) ([0-9.]+) ([0-9.]+)", task)
                assert match, "unexpected task"
                protocol, x, y = match.groups()
                step = sum(m["role"] == "assistant" for m in messages)
                observation = messages[-1]["content"]
                assert observation[1]["image_url"]["url"].startswith("data:image/jpeg;base64,")
                assert sum(isinstance(m.get("content"), list) for m in messages) == 1
                action = [{"action": "tap", "element": [float(x), float(y)]},
                          {"action": "type", "text": "你好 MiAIPlus"},
                          {"action": "finish", "message": "fixture verified"}][step]
                if step == 2:
                    assert "你好 MiAIPlus" in observation[0]["text"], "new screenshot/tree did not confirm input"
                if protocol == "computer":
                    assert request["tools"][0]["function"]["name"] == "computer"
                    message = {"role": "assistant", "content": None, "tool_calls": [{"id": f"call_{step}", "type": "function", "function": {"name": "computer", "arguments": json.dumps(action, ensure_ascii=False)}}]}
                else:
                    assert "tools" not in request
                    if step == 0:
                        text = f'do(action="Tap", element=[{x},{y}])'
                    elif step == 1:
                        text = 'do(action="Type", text="你好 MiAIPlus")'
                    else:
                        text = 'finish(message="fixture verified")'
                    message = {"role": "assistant", "content": f"<answer>{text}</answer>"}
            self.respond({"choices": [{"message": message}]})
        except Exception as e:
            print("fixture failure:", type(e).__name__, str(e), flush=True)
            self.respond({"error": "invalid fixture request"}, 400)


if __name__ == "__main__":
    print("Mock model listening on 127.0.0.1:18765", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 18765), Handler).serve_forever()
