#!/usr/bin/env python3
"""Minimal Infinia Store stub for scripts/e2e-smoke.sh.

Serves the fixture JSON files from this directory over loopback HTTP so the
smoke run can exercise the host's store integration (anonymous catalog browse,
channel status, and — by killing this server mid-run — the store-offline
degradation path) without any real store deployment. The canonical wire
shapes live in FengYu/src/test/resources/store-fixtures/infinia-store/ and
are pinned by the Java contract tests (HttpStoreAccountGatewayTest,
StoreApiFixtureContractTest); the copies here follow those shapes.

Usage: store_stub.py <port> <fixtures-dir>
"""
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROUTES = {
    "/api/v1/catalog": "catalog.json",
    # The Windows-portable compat mirror the Electron updater consumes; the
    # backend itself never calls this path (portable-web stays on GitHub).
    "/api/v1/compat/fengyu/fengyu-releases/api/releases/latest":
        "windows-portable-latest.json",
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        fixture = ROUTES.get(path)
        if fixture is None:
            self.send_response(404)
            self.end_headers()
            return
        body = (Path(sys.argv[2]) / fixture).read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
