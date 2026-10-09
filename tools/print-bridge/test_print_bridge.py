"""Self-test for print_bridge.py: a fake printer on a socket, the bridge on a free port, real HTTP."""
import http.client
import os
import socket
import tempfile
import threading
import time
import unittest

import print_bridge

ORIGIN = "https://app.example.co.uk"


class FakePrinter:
    def __init__(self):
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(5)
        self.port = self.server.getsockname()[1]
        self.received = []
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            data = b""
            conn.settimeout(1)
            try:
                while True:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
                    data += chunk
            except socket.timeout:
                pass
            conn.close()
            if data:
                self.received.append(data)

    def wait_for(self, count, seconds=3.0):
        """The printer reads on its own thread: wait until it has `count` receipts."""
        end = time.time() + seconds
        while len(self.received) < count and time.time() < end:
            time.sleep(0.02)
        return self.received

    def close(self):
        """Switch the printer off: nothing listens any more (shutdown wakes the blocked accept)."""
        try:
            self.server.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.server.close()
        self.thread.join(timeout=2)


def start(printer=None, device=None, origins=(ORIGIN,)):
    b = print_bridge.Bridge(("127.0.0.1", 0), printer, device, origins, timeout=1.0)
    threading.Thread(target=b.serve_forever, daemon=True).start()
    return b


def request(bridge, method, path, body=None, headers=None):
    c = http.client.HTTPConnection("127.0.0.1", bridge.server_address[1], timeout=5)
    c.request(method, path, body=body, headers=headers or {})
    r = c.getresponse()
    data = r.read()
    out = (r.status, dict(r.getheaders()), data)
    c.close()
    return out


OCTET = {"Content-Type": "application/octet-stream"}


class PrintBridgeTest(unittest.TestCase):
    def setUp(self):
        self.printer = FakePrinter()
        self.bridge = start(("127.0.0.1", self.printer.port))

    def tearDown(self):
        self.bridge.shutdown()
        self.bridge.server_close()
        self.printer.close()

    def test_the_bytes_reach_the_printer_unchanged(self):
        receipt = b"\x1b@\xa3 1.29\n\x1dV\x42\x00" + bytes(range(256))
        status, _, _ = request(self.bridge, "POST", "/print", receipt, {**OCTET, "Origin": ORIGIN})
        self.assertEqual(status, 204)
        self.assertEqual(self.printer.wait_for(1), [receipt])

    def test_a_page_of_a_listed_origin_may_ask_and_print(self):
        status, h, _ = request(self.bridge, "OPTIONS", "/print", None, {"Origin": ORIGIN, "Access-Control-Request-Method": "POST"})
        self.assertEqual(status, 204)
        self.assertEqual(h["Access-Control-Allow-Origin"], ORIGIN)
        self.assertIn("POST", h["Access-Control-Allow-Methods"])
        status, h, _ = request(self.bridge, "POST", "/print", b"x", {**OCTET, "Origin": ORIGIN})
        self.assertEqual(h["Access-Control-Allow-Origin"], ORIGIN)

    def test_a_page_of_any_other_origin_cannot(self):
        status, h, _ = request(self.bridge, "OPTIONS", "/print", None, {"Origin": "https://evil.example"})
        self.assertEqual(status, 403)
        self.assertNotIn("Access-Control-Allow-Origin", h)
        status, _, _ = request(self.bridge, "POST", "/print", b"x", {**OCTET, "Origin": "https://evil.example"})
        self.assertEqual(status, 403)
        self.assertEqual(self.printer.received, [])

    def test_only_a_post_of_octets_to_print_is_forwarded(self):
        self.assertEqual(request(self.bridge, "POST", "/other", b"x", OCTET)[0], 404)
        self.assertEqual(request(self.bridge, "GET", "/print")[0], 404)
        self.assertEqual(request(self.bridge, "POST", "/print", b"x", {"Content-Type": "text/html"})[0], 415)
        self.assertEqual(request(self.bridge, "POST", "/print", b"", OCTET)[0], 400)
        self.assertEqual(self.printer.received, [])

    def test_a_body_too_large_for_a_receipt_is_refused_unread(self):
        c = http.client.HTTPConnection("127.0.0.1", self.bridge.server_address[1], timeout=5)
        c.putrequest("POST", "/print")
        c.putheader("Content-Type", "application/octet-stream")
        c.putheader("Content-Length", str(print_bridge.MAX_BYTES + 1))
        c.endheaders()
        self.assertEqual(c.getresponse().status, 413)
        self.assertEqual(self.printer.received, [])

    def test_two_receipts_never_interleave(self):
        a, b = b"A" * 20000, b"B" * 20000
        ts = [threading.Thread(target=request, args=(self.bridge, "POST", "/print", x, OCTET)) for x in (a, b)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertCountEqual(self.printer.wait_for(2), [a, b])

    def test_health_says_whether_the_printer_answers(self):
        status, _, body = request(self.bridge, "GET", "/health")
        self.assertEqual((status, b"reachable" in body), (200, True))
        self.printer.close()
        status, _, body = request(self.bridge, "GET", "/health")
        self.assertEqual((status, b"unreachable" in body), (503, True))

    def test_a_printer_that_is_off_is_a_502_the_till_can_report(self):
        self.printer.close()
        status, _, body = request(self.bridge, "POST", "/print", b"x", OCTET)
        self.assertEqual(status, 502)
        self.assertIn(b"printer not reached", body)


class DeviceFileTest(unittest.TestCase):
    def test_a_usb_printer_is_a_device_file(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "lp0")
            open(path, "wb").close()
            bridge = start(device=path)
            try:
                self.assertEqual(request(bridge, "POST", "/print", b"\x1b@hello", OCTET)[0], 204)
                with open(path, "rb") as written:
                    self.assertEqual(written.read(), b"\x1b@hello")
                self.assertEqual(request(bridge, "GET", "/health")[0], 200)
            finally:
                bridge.shutdown()
                bridge.server_close()


if __name__ == "__main__":
    unittest.main()
