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
    fixture_thinking = {"type": "thinking", "thinking": "fixture hidden reasoning", "signature": "fixture-signature"}
    def log_message(self, *_):
        pass

    def do_GET(self):
        model = "fixture-model"
        if self.headers.get("anthropic-version"):
            if (self.headers.get_all("anthropic-version") != ["2023-06-01"]
                    or self.headers.get_all("x-api-key") != ["test-key"]
                    or self.headers.get("Authorization") is not None):
                self.respond({"error": "invalid fixture authentication"}, 400)
                return
            model = "fixture-anthropic-model"
        self.respond({"data": [{"id": model}]})

    def respond(self, body, status=200):
        if getattr(self, "use_responses", False) and "choices" in body:
            body = self.response_body(body["choices"][0]["message"])
        if getattr(self, "use_anthropic", False) and "choices" in body:
            body = self.anthropic_body(body["choices"][0]["message"])
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

    @staticmethod
    def anthropic_body(message):
        content = []
        if message.get("content"):
            content.append({"type": "text", "text": message["content"]})
        for call in message.get("tool_calls", []):
            content.append({"type": "tool_use", "id": call["id"], "name": call["function"]["name"],
                            "input": json.loads(call["function"]["arguments"])})
        return {"id": "msg_fixture", "type": "message", "role": "assistant", "model": "fixture-anthropic-model",
                "content": content, "stop_reason": "tool_use" if message.get("tool_calls") else "end_turn",
                "stop_sequence": None, "usage": {"input_tokens": 100, "output_tokens": 20}}

    @staticmethod
    def anthropic_parts(content):
        if isinstance(content, str):
            return content
        parts = []
        for block in content:
            if block["type"] == "text":
                parts.append(block)
            elif block["type"] == "image":
                source = block["source"]
                assert source["type"] == "base64" and source["media_type"] == "image/jpeg" and source["data"], "fixture JPEG missing"
                parts.append({"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + source["data"]}})
            else:
                raise AssertionError("unexpected Anthropic content block")
        return parts if any(p["type"] == "image_url" for p in parts) else "".join(p["text"] for p in parts)

    def anthropic_request(self, request):
        assert type(request["max_tokens"]) is int and request["max_tokens"] > 0, "Anthropic max_tokens missing"
        assert not any(k in request for k in ("input", "store", "parallel_tool_calls", "stream_options", "reasoning_effort",
                                             "enable_thinking", "thinking")), "unexpected protocol fields"
        messages = []
        if "system" in request:
            messages.append({"role": "system", "content": self.anthropic_parts(request["system"])})
        for message in request["messages"]:
            role, content = message["role"], message["content"]
            assert role in ("user", "assistant"), "unsupported Anthropic role"
            if isinstance(content, str):
                messages.append({"role": role, "content": content})
                continue
            parts, calls = [], []
            previous_thinking = None
            for block in content:
                kind = block["type"]
                if kind == "tool_result":
                    assert role == "user" and not parts, "tool_result must precede other user blocks"
                    result = self.anthropic_parts(block["content"])
                    if isinstance(result, list):
                        for part in result:
                            if part["type"] == "image_url":
                                messages.append({"role": "user", "content": [part]})
                        result = "".join(p["text"] for p in result if p["type"] == "text")
                    messages.append({"role": "tool", "tool_call_id": block["tool_use_id"], "content": result})
                elif kind == "tool_use":
                    assert role == "assistant" and isinstance(block["input"], dict), "invalid tool_use input"
                    if block["name"] == "probe_tool":
                        assert previous_thinking == self.fixture_thinking, "signed thinking missing from native tool replay"
                        assert block["input"] == {"marker": "分段参数"}, "fragmented tool input changed during replay"
                    calls.append({"id": block["id"], "type": "function", "function": {
                        "name": block["name"], "arguments": json.dumps(block["input"])}})
                elif kind == "thinking":
                    assert role == "assistant", "thinking must be an assistant block"
                    previous_thinking = block
                else:
                    parts.append(block)
            if parts or calls:
                normalized = {"role": role, "content": self.anthropic_parts(parts)}
                if calls:
                    normalized["tool_calls"] = calls
                messages.append(normalized)
        converted = {**request, "messages": messages}
        if "tools" in request:
            assert request["tool_choice"]["disable_parallel_tool_use"] is True
            converted["tools"] = [{"type": "function", "function": {"name": tool["name"], "parameters": tool["input_schema"]}}
                                  for tool in request["tools"]]
        return converted

    @staticmethod
    def anthropic_events(body):
        start = {**body, "content": [], "stop_reason": None, "usage": {"input_tokens": 100, "output_tokens": 1}}
        yield {"type": "message_start", "message": start}
        for index, block in enumerate(body["content"]):
            if block["type"] == "thinking":
                yield {"type": "content_block_start", "index": index, "content_block": {"type": "thinking", "thinking": ""}}
                yield {"type": "content_block_delta", "index": index, "delta": {"type": "thinking_delta", "thinking": block["thinking"]}}
                yield {"type": "content_block_delta", "index": index, "delta": {"type": "signature_delta", "signature": block["signature"]}}
                yield {"type": "content_block_stop", "index": index}
                continue
            if block["type"] == "tool_use":
                head = {**block, "input": {}}
                value = json.dumps(block["input"], ensure_ascii=False)
                delta_type, field = "input_json_delta", "partial_json"
            else:
                head = {**block, "text": ""}
                value = block["text"]
                delta_type, field = "text_delta", "text"
            yield {"type": "content_block_start", "index": index, "content_block": head}
            midpoint = len(value) // 2
            for fragment in (value[:midpoint], value[midpoint:]):
                if fragment:
                    yield {"type": "content_block_delta", "index": index, "delta": {"type": delta_type, field: fragment}}
            yield {"type": "content_block_stop", "index": index}
        yield {"type": "ping"}
        yield {"type": "message_delta", "delta": {"stop_reason": body["stop_reason"], "stop_sequence": None},
               "usage": {"output_tokens": body["usage"]["output_tokens"]}}
        yield {"type": "message_stop"}

    def do_POST(self):
        try:
            assert self.path in ("/v1/chat/completions", "/v1/responses", "/v1/messages"), "wrong completion path"
            self.use_responses = self.path == "/v1/responses"
            self.use_anthropic = self.path == "/v1/messages"
            if self.use_anthropic:
                assert self.headers.get_all("x-api-key") == ["test-key"], "missing expected Anthropic API key"
                assert self.headers.get_all("anthropic-version") == ["2023-06-01"], "missing Anthropic API version"
                assert self.headers.get("Authorization") is None, "Bearer auth on Anthropic request"
            else:
                assert self.headers.get("Authorization") == "Bearer test-key", "missing expected test auth (present=%s, count=%s)" % (self.headers.get("Authorization") is not None, len(self.headers.get_all("Authorization", [])))
            session = self.headers.get("x-opencode-session", "")
            assert re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", session), "missing or invalid session header"
            assert len(self.headers.get_all("x-opencode-session", [])) == 1, "duplicate session headers"
            assert self.headers.get("User-Agent", "").startswith("MiAIPlus/"), "client user agent missing"
            forbidden = ("cookie", "x-xiaomi", "x-mi-", "x-device", "x-osbot", "service-token", "x-model-provider-id")
            assert not any(any(name.lower().startswith(prefix) for prefix in forbidden) for name in self.headers), "Xiaomi account/device headers leaked"
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            expected_model = "fixture-model"
            task = None
            if self.use_responses:
                request = self.responses_request(request)
                expected_model = "fixture-responses-model"
            elif self.use_anthropic:
                # The first user turn can also carry observation blocks, including a prior screenshot's text.
                content = next(m["content"] for m in request["messages"] if m["role"] == "user")
                task = content if isinstance(content, str) else content[0]["text"]
                request = self.anthropic_request(request)
                expected_model = "fixture-anthropic-model"
            if request["model"] in ("gpt-6.1-sol", "qwen3.8-max"):
                expected_model = "gpt-6.1-sol" if self.use_responses else "qwen3.8-max"
                if self.use_responses:
                    assert request.get("reasoning", {}).get("effort") == "high", "selected native effort missing"
                    assert "enable_thinking" not in request and "reasoning_effort" not in request
                else:
                    assert request.get("enable_thinking") is True, "selected native thinking switch missing"
                    assert "reasoning_effort" not in request and "thinking" not in request
            if request["model"] == "future-effort-fixture":
                expected_model = "future-effort-fixture"
                if self.use_responses:
                    assert request.get("reasoning", {}).get("effort") == "high", "remote Responses effort missing"
                    assert "reasoning_effort" not in request
                else:
                    assert request.get("reasoning_effort") == "high", "remote Chat effort missing"
                    assert "reasoning" not in request
                assert not any(k in request for k in ("enable_thinking", "thinking", "temperature", "top_p")), "remote policy fields not applied"
            assert request["model"] == expected_model, "wrong API path for selected fixture model"
            messages = request["messages"]
            if task is None:
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
                    arguments = json.dumps({"marker": "分段参数"}, ensure_ascii=False) if self.use_anthropic else "{}"
                    delta = {"tool_calls": [{"index": 0, "id": "probe-call", "type": "function", "function": {"name": "probe_tool", "arguments": arguments}}]}
                    reason = "tool_calls"
                else:
                    delta = {"content": "native-stream-ok"}
                    reason = "stop"
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Connection", "close")
                self.end_headers()
                if self.use_anthropic:
                    message = {"content": delta.get("content"), "tool_calls": delta.get("tool_calls", [])}
                    body = self.anthropic_body(message)
                    if message["tool_calls"]:
                        body["content"].insert(0, self.fixture_thinking)
                    for event in self.anthropic_events(body):
                        self.wfile.write(("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n").encode())
                        self.wfile.flush()
                    self.close_connection = True
                    return
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
                if self.use_anthropic:
                    # Consecutive user messages become ordered blocks in one Anthropic turn.
                    observation = observation[-2:]
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
