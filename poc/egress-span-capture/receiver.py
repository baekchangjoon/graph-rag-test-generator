#!/usr/bin/env python3
"""PoC host-side receiver for the Brave/Zipkin egress-span capture spike.

Two roles on one port:
  POST /reservations    -> reservation downstream stub (returns 202). Logs the B3
                           headers Brave injected on order-web's outbound call.
  POST /api/v2/spans    -> Zipkin v2 span sink. Brave's reporter posts finished
                           spans here (gzip or plain JSON). Each span is appended
                           as one NDJSON line to the spans file for inspection.
  GET  /*               -> 200 {} (health probes).

Usage: receiver.py <port> <spans_ndjson_path>
"""
import gzip
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SPANS_PATH = "/tmp/zipkin-spans.ndjson"
RES_STATUS = int(os.environ.get("RES_STATUS", "202"))  # downstream stub status


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # silence default access log; we print our own

    def _read_body(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b""
        if self.headers.get("Content-Encoding", "").lower() == "gzip":
            raw = gzip.decompress(raw)
        return raw

    def do_POST(self):
        body = self._read_body()
        if self.path.startswith("/api/v2/spans"):
            try:
                spans = json.loads(body) if body else []
            except Exception as exc:  # noqa: BLE001 — diagnostics only
                spans = [{"_parse_error": str(exc),
                          "_raw": body.decode("utf-8", "replace")}]
            if not isinstance(spans, list):
                spans = [spans]
            with open(SPANS_PATH, "a", encoding="utf-8") as sink:
                for span in spans:
                    sink.write(json.dumps(span, ensure_ascii=False) + "\n")
            kinds = [s.get("kind", "?") for s in spans if isinstance(s, dict)]
            print(f"[zipkin] received {len(spans)} span(s) kinds={kinds}", flush=True)
            self.send_response(202)
            self.end_headers()
        elif self.path.startswith("/reservations"):
            b3 = self.headers.get("X-B3-TraceId")
            b3single = self.headers.get("b3")
            tp = self.headers.get("traceparent")
            print(f"[reservation-stub] POST {self.path} -> {RES_STATUS} "
                  f"X-B3-TraceId={b3} b3={b3single} traceparent={tp}", flush=True)
            self.send_response(RES_STATUS)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")


if __name__ == "__main__":
    port = int(sys.argv[1])
    if len(sys.argv) > 2:
        SPANS_PATH = sys.argv[2]
    open(SPANS_PATH, "w").close()  # truncate at start
    print(f"[receiver] listening on 0.0.0.0:{port}, spans -> {SPANS_PATH}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
