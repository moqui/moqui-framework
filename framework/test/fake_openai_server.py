#!/usr/bin/env python3
"""Minimal OpenAI-compatible server for browser tests. Answers every chat completion with fixed text.

  python3 framework/test/fake_openai_server.py 18234
Start Moqui with -Dllm_openai_url=http://127.0.0.1:18234 -Dllm_openai_model=fake-model -Dllm_log_content=true
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

REPLY = "Fake reply from the test server."


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        usage = {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            chunks = [
                {"choices": [{"index": 0, "delta": {"content": REPLY}}]},
                {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
                {"choices": [], "usage": usage},
            ]
            for c in chunks:
                self.wfile.write(("data: " + json.dumps(c) + "\n\n").encode())
            self.wfile.write(b"data: [DONE]\n\n")
            return
        payload = {"id": "fake", "model": "fake-model", "usage": usage,
                   "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": REPLY}}]}
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18234
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
