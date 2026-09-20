#!/usr/bin/env python3
"""Loopback server for the runner's CI gate. Standard library only: `python3 server.py [port]`."""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

ROUTES = {
    "/ok": {"id": 7, "name": "ping"},
    "/health": {"status": "up"},
    "/login": {"token": "tok-sample-4711"},
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?")[0]
        body = ROUTES.get(path)
        if path == "/whoami":
            # Only answers when the caller presents what /login returned, so a run passes
            # only if a value captured from one response reached the next request.
            ok = self.headers.get("Authorization") == "Bearer tok-sample-4711"
            self._send(200 if ok else 401, {"user": "sample"} if ok else {"error": "unauthorized"})
            return
        self._send(200 if body is not None else 404, body if body is not None else {"error": "not found"})

    def _send(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18099
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
