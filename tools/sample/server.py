#!/usr/bin/env python3
"""Loopback server for the runner's CI gate. Standard library only: `python3 server.py [port]`."""
import hashlib
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
        if path == "/session":
            # Starts a session the way a login page does: the cookie is the only credential.
            self._send(200, {"started": True}, {"Set-Cookie": "sid=sample-cookie-8842; Path=/; HttpOnly"})
            return
        if path == "/me":
            ok = "sid=sample-cookie-8842" in (self.headers.get("Cookie") or "")
            self._send(200 if ok else 401, {"user": "sample"} if ok else {"error": "no session"})
            return
        if path == "/whoami":
            # Only answers when the caller presents what /login returned, so a run passes
            # only if a value captured from one response reached the next request.
            ok = self.headers.get("Authorization") == "Bearer tok-sample-4711"
            self._send(200 if ok else 401, {"user": "sample"} if ok else {"error": "unauthorized"})
            return
        self._send(200 if body is not None else 404, body if body is not None else {"error": "not found"})

    def _body(self):
        """The request body, or None when it has no Content-Length (chunked): an upload's length
        must be fixed up front, so the server refuses anything else."""
        length = self.headers.get("Content-Length")
        if length is None:
            self._send(411, {"error": "length required"})
            return None
        return self.rfile.read(int(length))

    def do_POST(self):
        path = self.path.split("?")[0]
        body = self._body()
        if body is None:
            return
        if path == "/upload":
            boundary = self.headers.get("Content-Type", "").split("boundary=")[-1].encode()
            for part in body.split(b"--" + boundary):
                head, _, rest = part.partition(b"\r\n\r\n")
                if b'filename="' in head:
                    name = head.split(b'filename="')[1].split(b'"')[0].decode()
                    data = rest[:-2] if rest.endswith(b"\r\n") else rest
                    self._send(200, {"filename": name, "bytes": len(data),
                                     "sha256": hashlib.sha256(data).hexdigest()})
                    return
            self._send(400, {"error": "no file part"})
            return
        self._send(404, {"error": "not found"})

    def do_PUT(self):
        path = self.path.split("?")[0]
        body = self._body()
        if body is None:
            return
        if path == "/bin":
            self._send(200, {"bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()})
            return
        self._send(404, {"error": "not found"})

    def _send(self, status, body, headers=None):
        payload = json.dumps(body).encode()
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18099
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
