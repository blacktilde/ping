#!/usr/bin/env python3
"""A forwarding HTTP proxy for the runner's CI gate: `python3 proxy.py <port> <log file>`.

Standard library only. It relays each request to the address in the request line and appends
"<METHOD> <absolute URL> <Proxy-Authorization or ->" to the log, so a test can prove a request
went through it. Plain HTTP only; the gate's server is plain HTTP.
"""
import http.client
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

HOP_BY_HOP = {"connection", "keep-alive", "proxy-authorization", "proxy-connection", "te",
              "trailer", "transfer-encoding", "upgrade", "http2-settings", "host"}
LOG = sys.argv[2]


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _forward(self):
        with open(LOG, "a") as log:
            log.write(f"{self.command} {self.path} {self.headers.get('Proxy-Authorization', '-')}\n")
        target = urlsplit(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
        conn = http.client.HTTPConnection(target.hostname, target.port or 80, timeout=30)
        conn.request(self.command, (target.path or "/") + ("?" + target.query if target.query else ""),
                     body=body, headers=headers)
        response = conn.getresponse()
        payload = response.read()
        self.send_response(response.status)
        for key, value in response.getheaders():
            if key.lower() not in HOP_BY_HOP and key.lower() != "content-length":
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        conn.close()

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = do_OPTIONS = _forward

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Proxy).serve_forever()
