#!/usr/bin/env python3
"""Deterministic protocol fixture; binds only loopback, served to Android using adb reverse.
Never logs request content, images, or Authorization headers.
"""
import json
import re
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    native_sessions = {}
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.respond({"data": [{"id": "fixture-model"}]})

    def respond(self, body, status=200):
        if getattr(self, "use_responses", False) and "choices" in body:
            body = self.response_body(body["choices"][0]["message"])
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def response_body(self, message):
        output = [{"type": "reasoning", "id": "rs_fixture", "summary": [], "encrypted_content": "fixture-opaque"}]
        if message.get("content"):
            output.append({"type": "message", "id": "msg_fixture", "role": "assistant", "status": "completed", "phase": "final_answer",
                           "content": [{"type": "output_text", "text": message["content"], "annotations": []}]})
        for call in message.get("tool_calls", []):
            output.append({"type": "function_call", "id": "fc_" + call["id"], "call_id": call["id"], "status": "completed", **call["function"]})
        return {"id": "resp_fixture", "object": "response", "model": "fixture-model", "status": "completed", "output": output,
                "usage": {"input_tokens": 100, "output_tokens": 20, "total_tokens": 120, "output_tokens_details": {"reasoning_tokens": 10}}}

    def responses_request(self, request):
        assert "messages" not in request and "temperature" not in request and "max_tokens" not in request
        assert request["store"] is False
        messages = []
        saw_reasoning = False
        for item in request["input"]:
            kind = item.get("type", "message")
            if kind == "reasoning":
                assert item["encrypted_content"] == "fixture-opaque"
                saw_reasoning = True
            elif kind == "function_call":
                assert saw_reasoning, "reasoning output not replayed with tool call"
                if not messages or messages[-1]["role"] != "assistant":
                    messages.append({"role": "assistant", "content": None, "tool_calls": []})
                messages[-1].setdefault("tool_calls", []).append({"id": item["call_id"], "type": "function",
                    "function": {"name": item["name"], "arguments": item["arguments"]}})
            elif kind == "function_call_output":
                messages.append({"role": "tool", "tool_call_id": item["call_id"], "content": item["output"]})
            elif kind == "message":
                content = item["content"]
                if isinstance(content, list):
                    if all(p["type"] in ("input_text", "output_text") for p in content):
                        content = "".join(p["text"] for p in content)
                    else:
                        content = [{"type": "text", "text": p["text"]} if p["type"] in ("input_text", "output_text")
                                   else {"type": "image_url", "image_url": {"url": p["image_url"]}} for p in content]
                messages.append({"role": item["role"], "content": content})
            else:
                raise AssertionError("unexpected Responses item")
        converted = {**request, "messages": messages}
        if "tools" in request:
            assert request["parallel_tool_calls"] is False
            assert all(t["type"] == "function" and t["strict"] is False for t in request["tools"])
            converted["tools"] = [{"type": "function", "function": {k: v for k, v in tool.items() if k != "type"}} for tool in request["tools"]]
        return converted

    def do_POST(self):
        try:
            assert self.path in ("/v1/chat/completions", "/v1/responses"), "wrong completion path"
            self.use_responses = self.path == "/v1/responses"
            assert self.headers.get("Authorization") == "Bearer test-key", "missing expected test auth (present=%s, count=%s)" % (self.headers.get("Authorization") is not None, len(self.headers.get_all("Authorization", [])))
            session = self.headers.get("x-opencode-session", "")
            assert re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", session), "missing or invalid session header"
            assert len(self.headers.get_all("x-opencode-session", [])) == 1, "duplicate session headers"
            assert self.headers.get("User-Agent", "").startswith("MiAIPlus/"), "client user agent missing"
            forbidden = ("cookie", "x-xiaomi", "x-mi-", "x-device", "x-osbot", "service-token", "x-model-provider-id")
            assert not any(any(name.lower().startswith(prefix) for prefix in forbidden) for name in self.headers), "Xiaomi account/device headers leaked"
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.use_responses:
                request = self.responses_request(request)
            expected_model = "fixture-responses-model" if self.use_responses else "fixture-model"
            if request["model"] in ("gpt-6.1-sol", "qwen3.8-max"):
                expected_model = "gpt-6.1-sol" if self.use_responses else "qwen3.8-max"
                if self.use_responses:
                    assert request.get("reasoning", {}).get("effort") == "high", "selected native effort missing"
                    assert "enable_thinking" not in request and "reasoning_effort" not in request
                else:
                    assert request.get("enable_thinking") is True, "selected native thinking switch missing"
                    assert "reasoning_effort" not in request and "thinking" not in request
            assert request["model"] == expected_model, "wrong API path for selected fixture model"
            messages = request["messages"]
            task = next(m["content"] for m in messages if m["role"] == "user")
            if task in ("MIAIPLUS_GATE_TEST", "MIAIPLUS_CAPABILITY_TEST", "MIAIPLUS_CAPABILITY_V2_TEST", "MIAIPLUS_ROUTER_TEST"):
                assert request["model"] == expected_model and request["stream"] is False
                assert not any(k in request for k in ("rid", "intent_protocol", "routing_profile", "context", "prompt_source"))
                assert messages[0]["role"] == "system", "system role missing for " + task
                if task == "MIAIPLUS_CAPABILITY_V2_TEST":
                    assert "required_capabilities" in messages[0]["content"], "capability response schema missing"
                content = {"MIAIPLUS_GATE_TEST": "gate-ok", "MIAIPLUS_CAPABILITY_TEST": "capability-ok",
                           "MIAIPLUS_CAPABILITY_V2_TEST": '{"user_intent":"fixture intent","required_capabilities":["fixture capability"]}',
                           "MIAIPLUS_ROUTER_TEST": '{"status":"FALLBACK","direct_path":false,"difficulty":"easy","reason":"fixture route"}'}[task]
                self.respond({"choices": [{"message": {"role": "assistant", "content": content}}]})
                return
            if task == "MIAIPLUS_MEMORY_TEST":
                assert request["model"] == expected_model and request["stream"] is False
                assert any(m["role"] == "system" and m["content"] == "MIAIPLUS_MEMORY_SYSTEM" for m in messages)
                self.respond({"choices": [{"message": {"role": "assistant", "content": "memory-ok"}}]})
                return
            if task in ("MIAIPLUS_NATIVE_STREAM_TEST", "MIAIPLUS_NATIVE_STREAM_FINAL"):
                assert request["model"] == expected_model, "global model override missing"
                assert request["stream"] is True, "stock native SSE transport missing"
                assert any(m["role"] == "system" and "MIAIPLUS_NATIVE_SYSTEM" in m["content"] for m in messages), "native prompt lost"
                results = [m for m in messages if m["role"] == "tool"]
                if results:
                    assert self.native_sessions.get(self.path) == session, "native session changed across tool roundtrip"
                    assert results[-1]["tool_call_id"] == "probe-call", "native tool call id lost"
                    assert "probe-result" in results[-1]["content"], "native tool result lost"
                    assert any(isinstance(m.get("content"), list) and any(p.get("type") == "image_url" for p in m["content"]) for m in messages), "native screenshot lost"
                    delta = {"content": "native-roundtrip-ok"}
                    reason = "stop"
                elif task.endswith("_TEST"):
                    assert session != self.native_sessions.get(self.path), "distinct native conversations share a session"
                    self.native_sessions[self.path] = session
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
                if self.use_responses:
                    message = {"role": "assistant", "content": delta.get("content"), "tool_calls": delta.get("tool_calls", [])}
                    response = self.response_body(message)
                    events = [{"type": "response.created", "response": {"id": "resp_fixture", "model": "fixture-model", "status": "in_progress"}}]
                    if delta.get("content"):
                        events.append({"type": "response.output_text.delta", "delta": delta["content"]})
                    events.append({"type": "response.completed", "response": response})
                    for event in events:
                        self.wfile.write(("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n").encode())
                    self.wfile.flush(); self.close_connection = True
                    return
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
                match = re.fullmatch(r"MIAIPLUS_SCREEN_TEST (phone_use|autoglm) ([0-9.]+) ([0-9.]+)", task)
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
                if protocol == "phone_use":
                    assert request["tools"][0]["function"]["name"] == "phone_use"
                    message = {"role": "assistant", "content": None, "tool_calls": [{"id": f"call_{step}", "type": "function", "function": {"name": "phone_use", "arguments": json.dumps(action, ensure_ascii=False)}}]}
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
            line = traceback.extract_tb(e.__traceback__)[-1].lineno
            print("fixture failure:", type(e).__name__, f"line={line}", str(e), flush=True)
            self.respond({"error": "invalid fixture request"}, 400)


if __name__ == "__main__":
    print("Mock model listening on 127.0.0.1:18765", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 18765), Handler).serve_forever()
