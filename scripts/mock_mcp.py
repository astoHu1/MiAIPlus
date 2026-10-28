#!/usr/bin/env python3
"""Loopback-only MCP fixture for Pluginify device checks, with no external services.

Only synthetic echo, addition and bounded delayed echo are exposed. Request bodies,
Authorization headers and response contents are never logged.
"""
import argparse
import json
import threading
import time
import uuid
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


PROTOCOL = "2025-11-25"
FIXTURE = "miaiplus-plugin-platform-fixture-v1"
MAX_REQUEST_BYTES = 128 * 1024
TOOLS = [
    {"name": "echo", "title": "Fixture echo", "description": "Return the supplied synthetic text unchanged.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
    {"name": "add", "title": "Fixture addition", "description": "Add two synthetic numbers.",
     "inputSchema": {"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
                     "required": ["a", "b"]}},
    {"name": "delayed", "title": "Fixture delayed echo", "description": "Return synthetic text after a bounded delay.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}, "delay_ms": {"type": "integer"}},
                     "required": ["text", "delay_ms"]}},
]


class FixtureState:
    def __init__(self, token, instance):
        self.token = token
        self.instance = instance
        self.lock = threading.Lock()
        self.sessions = {}
        self.methods = Counter()
        self.calls = Counter()
        self.active_delayed = 0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def respond(self, value=None, status=200, session=None):
        body = b"" if value is None else json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        if value is not None:
            self.send_header("Content-Type", "application/json; charset=utf-8")
        if session is not None:
            self.send_header("MCP-Session-Id", session)
        self.end_headers()
        self.close_connection = True
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, OSError):
            # Cancellation disconnects the client; it cannot undo an already accepted remote call.
            pass

    def do_GET(self):
        state = self.server.fixture
        if self.path == "/health":
            self.respond({"fixture": FIXTURE, "protocolVersion": PROTOCOL, "instance": state.instance})
        elif self.path == "/stats":
            with state.lock:
                result = {"fixture": FIXTURE, "methods": dict(state.methods), "calls": dict(state.calls),
                          "activeDelayed": state.active_delayed}
            self.respond(result)
        else:
            self.respond({"error": "fixture route not found"}, 404)

    def do_POST(self):
        state = self.server.fixture
        if self.path != "/mcp":
            self.respond({"error": "fixture route not found"}, 404)
            return
        if self.headers.get("Authorization") != "Bearer " + state.token:
            self.respond({"error": "fixture authentication rejected"}, 401)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > MAX_REQUEST_BYTES:
                self.respond({"error": "fixture request size rejected"}, 413)
                return
            request = json.loads(self.rfile.read(length))
            if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
                raise ValueError("invalid envelope")
            method = request.get("method")
            if method not in {"initialize", "notifications/initialized", "tools/list", "tools/call"}:
                self.respond({"jsonrpc": "2.0", "id": request.get("id"),
                              "error": {"code": -32601, "message": "fixture method not supported"}})
                return
            with state.lock:
                state.methods[method] += 1
            if method == "initialize":
                if request.get("params", {}).get("protocolVersion") != PROTOCOL:
                    raise ValueError("protocol mismatch")
                session = str(uuid.uuid4())
                with state.lock:
                    state.sessions[session] = False
                self.respond({"jsonrpc": "2.0", "id": request.get("id"), "result": {
                    "protocolVersion": PROTOCOL, "capabilities": {"tools": {}},
                    "serverInfo": {"name": FIXTURE, "version": "1"}}}, session=session)
                print("MOCK_MCP initialize", flush=True)
                return
            session = self.headers.get("MCP-Session-Id")
            with state.lock:
                initialized = state.sessions.get(session)
            if initialized is None or self.headers.get("MCP-Protocol-Version") != PROTOCOL:
                raise ValueError("session mismatch")
            if method == "notifications/initialized":
                if "id" in request:
                    raise ValueError("notification id")
                with state.lock:
                    state.sessions[session] = True
                self.respond(status=202)
                return
            if not initialized:
                raise ValueError("session not initialized")
            params = request.get("params", {})
            if not isinstance(params, dict):
                raise ValueError("invalid params")
            if method == "tools/list":
                cursor = params.get("cursor")
                if cursor is None:
                    result = {"tools": TOOLS[:2], "nextCursor": "fixture-page-2"}
                elif cursor == "fixture-page-2":
                    result = {"tools": TOOLS[2:]}
                else:
                    raise ValueError("invalid cursor")
            else:
                name, arguments = params.get("name"), params.get("arguments")
                if name not in {"echo", "add", "delayed"} or not isinstance(arguments, dict):
                    raise ValueError("invalid fixture tool")
                with state.lock:
                    state.calls[name] += 1
                print("MOCK_MCP tools/call " + name, flush=True)
                if name == "add":
                    a, b = arguments.get("a"), arguments.get("b")
                    if any(isinstance(value, bool) or not isinstance(value, (int, float)) or abs(value) > 1_000_000
                           for value in (a, b)):
                        raise ValueError("invalid addition")
                    result = {"content": [{"type": "text", "text": "sum=" + str(a + b)}],
                              "structuredContent": {"sum": a + b}, "isError": False}
                else:
                    text = arguments.get("text")
                    if not isinstance(text, str) or len(text) > 4096:
                        raise ValueError("invalid fixture text")
                    if name == "delayed":
                        delay = arguments.get("delay_ms")
                        if isinstance(delay, bool) or not isinstance(delay, int) or not 0 <= delay <= 15000:
                            raise ValueError("invalid fixture delay")
                        with state.lock:
                            state.active_delayed += 1
                        try:
                            time.sleep(delay / 1000)
                        finally:
                            with state.lock:
                                state.active_delayed -= 1
                    result = {"content": [{"type": "text", "text": text}], "isError": False}
            self.respond({"jsonrpc": "2.0", "id": request.get("id"), "result": result})
        except (ValueError, TypeError, KeyError, json.JSONDecodeError, OSError):
            # Do not echo received content or authentication information, even on fixture failure.
            self.respond({"jsonrpc": "2.0", "id": None,
                          "error": {"code": -32602, "message": "fixture request rejected"}})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--token", default="plugin-device-fixture-token")
    parser.add_argument("--instance", default=str(uuid.uuid4()))
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port is out of range")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    server.fixture = FixtureState(args.token, args.instance)
    print("MOCK_MCP_READY port=" + str(args.port) + " instance=" + args.instance, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
