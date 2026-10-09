#!/usr/bin/env python3
"""StoreQL print bridge: lets a browser till reach a thermal receipt printer.

A browser cannot open a raw socket, so a till running in one posts its ESC/POS receipt to this small
program on the same PC (http://localhost:9109/print, the till's default), and this hands the bytes to the
printer: a network printer on port 9100, or a device file (a USB printer on Linux, e.g. /dev/usb/lp0).

    python3 print_bridge.py --printer 192.168.1.50:9100 --allow-origin https://app.example.co.uk
    python3 print_bridge.py --device /dev/usb/lp0       --allow-origin https://app.example.co.uk

It listens on 127.0.0.1 only (the till is on the same PC; a page served over https may call http://localhost
but not another address), forwards nothing but the bytes of a POST to /print, and answers a browser's
cross-origin check only for the origins it was told. There is no state and nothing is logged but the size.
GET /health says whether the printer answers. Python 3.8+, standard library only.
"""
import argparse
import json
import socket
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BYTES = 512 * 1024  # a receipt is a few kilobytes; a logo a few tens


class Bridge(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, listen, printer, device, origins, timeout=5.0):
        super().__init__(listen, Handler)
        self.printer = printer  # (host, port) or None
        self.device = device  # path or None
        self.origins = set(origins)
        self.timeout = timeout
        self.lock = threading.Lock()  # one receipt at a time, so two never interleave

    def send(self, data):
        with self.lock:
            if self.device:
                with open(self.device, "wb", buffering=0) as f:
                    f.write(data)
                return
            with socket.create_connection(self.printer, timeout=self.timeout) as s:
                s.sendall(data)

    def reachable(self):
        if self.device:
            try:
                open(self.device, "ab").close()
                return True
            except OSError:
                return False
        try:
            socket.create_connection(self.printer, timeout=self.timeout).close()
            return True
        except OSError:
            return False


class Handler(BaseHTTPRequestHandler):
    server_version = "storeql-print-bridge"

    def log_message(self, *_):
        pass

    def _cors(self):
        origin = self.headers.get("Origin")
        if origin and origin in self.server.origins:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Private-Network", "true")

    def _reply(self, status, body=b"", content_type="text/plain"):
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        origin = self.headers.get("Origin")
        if self.path != "/print" or origin not in self.server.origins:
            return self._reply(403, b"origin not allowed")
        self.send_response(204)
        self._cors()
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "600")
        self.end_headers()

    def do_GET(self):
        if self.path != "/health":
            return self._reply(404, b"not found")
        up = self.server.reachable()
        body = json.dumps({"printer": "reachable" if up else "unreachable"}).encode()
        self._reply(200 if up else 503, body, "application/json")

    def do_POST(self):
        if self.path != "/print":
            return self._reply(404, b"not found")
        origin = self.headers.get("Origin")
        if origin and origin not in self.server.origins:
            return self._reply(403, b"origin not allowed")
        if (self.headers.get("Content-Type") or "").split(";")[0].strip() != "application/octet-stream":
            return self._reply(415, b"send the receipt as application/octet-stream")
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self._reply(411, b"Content-Length is required")
        if length <= 0:
            return self._reply(400, b"nothing to print")
        if length > MAX_BYTES:
            return self._reply(413, b"too large for a receipt")
        data = self.rfile.read(length)
        try:
            self.server.send(data)
        except OSError as e:
            return self._reply(502, f"printer not reached: {e.__class__.__name__}".encode())
        self._reply(204)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--listen", default="127.0.0.1:9109")
    where = p.add_mutually_exclusive_group(required=True)
    where.add_argument("--printer", help="host:port of a network printer (port 9100)")
    where.add_argument("--device", help="a device file, e.g. /dev/usb/lp0")
    p.add_argument("--allow-origin", action="append", default=[], help="a page origin allowed to print (repeatable)")
    a = p.parse_args(argv)
    host, _, port = a.listen.rpartition(":")
    printer = None
    if a.printer:
        h, _, pt = a.printer.rpartition(":")
        printer = (h, int(pt or 9100))
    server = Bridge((host or "127.0.0.1", int(port)), printer, a.device, a.allow_origin)
    print(f"print bridge on {a.listen} -> {a.printer or a.device}; origins: {', '.join(a.allow_origin) or 'none (same-machine tools only)'}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
