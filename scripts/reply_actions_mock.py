#!/usr/bin/env python3
"""Dedicated loopback fixture for --reply-actions / --reply-actions-chat.

For the long-lived Responses regression, use --response-delay 35. This sends
the fixed text immediately, keeps the upstream stream active, and completes
after the delay. Logs contain only protocol, streaming mode and event timing.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import re
import time


MARKER = "MIAIPLUS_REPLY_ACTIONS_TEST"
MODEL = "fixture-reply-actions-model"
TEXT = "miaiplus-reply-actions-ok"


def duration(value):
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise argparse.ArgumentTypeError("duration must be finite and nonnegative")
    return number


def positive_duration(value):
    number = duration(value)
    if number == 0:
        raise argparse.ArgumentTypeError("interval must be positive")
    return number


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        started = time.monotonic()
        protocol = "unknown"
        streaming = False
        headers_sent = False

        def log(event):
            print(f"protocol={protocol} stream={str(streaming).lower()} "
                  f"event={event} elapsed={time.monotonic() - started:.3f}s", flush=True)

        def event(payload):
            wire = "event: " + payload["type"] + "\ndata: " + json.dumps(payload) + "\n\n"
            self.wfile.write(wire.encode("utf-8"))
            self.wfile.flush()

        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size < 2_000_000:
                raise ValueError("invalid fixture request size")
            body = json.loads(self.rfile.read(size))
            if self.headers.get("Authorization") != "Bearer test-key":
                raise ValueError("invalid fixture authorization")
            if body.get("model") != MODEL or MARKER not in json.dumps(body):
                raise ValueError("invalid fixture model or marker")
            # A distinct generated reply proves each lifecycle stage rendered its own response.
            # The marker contains only a probe UUID; request text and headers stay unlogged.
            # Read the latest user message, not the serialized request tail: history and system
            # summaries may repeat earlier stage markers after the current message in other fields.
            entries = body.get("messages", body.get("input", []))
            users = [entry for entry in entries if isinstance(entry, dict) and entry.get("role") == "user"] if isinstance(entries, list) else []
            stages = re.findall(r"MIAIPLUS_CONVERSATION_STAGE_([a-f0-9-]{36})", json.dumps(users[-1])) if users else []
            if "MIAIPLUS_CONVERSATION_STAGE_" in json.dumps(body) and not stages:
                raise ValueError("lifecycle marker missing from latest user message")
            reply_text = "miaiplus-ok-" + stages[-1][:8] if stages else TEXT
            if not isinstance(body.get("stream", False), bool):
                raise ValueError("invalid fixture stream mode")
            streaming = body.get("stream", False)
            if self.path == "/v1/responses":
                protocol = "responses"
            elif self.path == "/v1/chat/completions":
                protocol = "chat"
            else:
                raise ValueError("invalid fixture endpoint")
            response = {
                "id": "resp_reply_actions", "object": "response", "model": MODEL, "status": "completed",
                "output": [{"type": "message", "id": "msg_reply_actions", "role": "assistant",
                            "status": "completed", "phase": "final_answer",
                            "content": [{"type": "output_text", "text": reply_text, "annotations": []}]}],
                "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15},
            }
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream" if streaming else "application/json")
            self.send_header("Connection", "close")
            self.end_headers()
            headers_sent = True
            log("accepted")
            if not streaming:
                completion = {"id": "chatcmpl_reply_actions", "object": "chat.completion", "model": MODEL,
                              "choices": [{"index": 0, "message": {"role": "assistant", "content": reply_text},
                                           "finish_reason": "stop"}]}
                self.wfile.write(json.dumps(response if protocol == "responses" else completion).encode("utf-8"))
                log("json.completed")
            elif protocol == "responses":
                event({"type": "response.created", "response": {
                    "id": response["id"], "model": MODEL, "status": "in_progress"}})
                log("created")
                event({"type": "response.output_text.delta", "delta": reply_text, "output_index": 0,
                       "content_index": 0, "item_id": "msg_reply_actions"})
                log("text")
                deadline = time.monotonic() + self.server.response_delay
                index = 0
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        break
                    time.sleep(min(self.server.heartbeat_interval, remaining))
                    if index % 2 == 0:
                        self.wfile.write(b": fixture keepalive\n\n")
                        self.wfile.flush()
                        log("heartbeat.comment")
                    else:
                        event({"type": "response.reasoning_summary_text.delta", "output_index": 1,
                               "summary_index": 0, "delta": "fixture progress"})
                        log("heartbeat.reasoning")
                    index += 1
                event({"type": "response.completed", "response": response})
                log("completed")
            else:
                for delta, reason in [({"role": "assistant"}, None), ({"content": reply_text}, None), ({}, "stop")]:
                    chunk = {"id": "chatcmpl_reply_actions", "object": "chat.completion.chunk", "model": MODEL,
                             "created": 0, "choices": [{"index": 0, "delta": delta, "finish_reason": reason}]}
                    self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode("utf-8"))
                self.wfile.write(b"data: [DONE]\n\n")
                log("done")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            log("client.disconnected")
        except Exception:
            log("request.failed")
            if not headers_sent:
                self.send_error(400, "Invalid fixture request")
        finally:
            self.close_connection = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--response-delay", type=duration, default=0,
                        help="seconds before Responses stream completion; use 35 for the timeout regression")
    parser.add_argument("--heartbeat-interval", type=positive_duration, default=5,
                        help="seconds between alternating SSE comments and reasoning progress")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.response_delay = args.response_delay
    server.heartbeat_interval = args.heartbeat_interval
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
