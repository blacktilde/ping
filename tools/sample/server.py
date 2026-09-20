#!/usr/bin/env python3
"""Loopback server for the runner's CI gate. Standard library only: `python3 server.py [port]`."""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

ROUTES = {
    "/ok": {"id": 7, "name": "ping"},
    "/health": {"status": "up"},
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = ROUTES.get(self.path.split("?")[0])
        payload = json.dumps(body if body is not None else {"error": "not found"}).encode()
        self.send_response(200 if body is not None else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18099
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
