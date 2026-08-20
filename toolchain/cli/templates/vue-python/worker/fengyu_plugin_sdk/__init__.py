"""Vendored FengYu Python Worker SDK; replace with the published package when desired."""
from __future__ import annotations
import contextvars, json, os, sys, threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any, Callable

PROTOCOL_VERSION = 1
_current = contextvars.ContextVar("fengyu_context", default=None)

class RpcError(Exception):
    def __init__(self, message, code="INTERNAL_ERROR"):
        super().__init__(message); self.code = code

@dataclass(frozen=True)
class RpcContext:
    call_id: str | None
    plugin_id: str | None
    plugin_root: str | None
    locale: str | None
    cancelled: threading.Event
    def check_cancelled(self):
        if self.cancelled.is_set(): raise RpcError("request cancelled", "CANCELLED")

class Worker:
    def __init__(self):
        self.handlers: dict[str, Callable] = {}
        self.pending: dict[str, threading.Event] = {}
        self.lock, self.write_lock = threading.Lock(), threading.Lock()
    def on(self, method, handler):
        if not method or method in self.handlers: raise ValueError("invalid or duplicate method")
        self.handlers[method] = handler; return self
    def run(self, input_stream=sys.stdin, output_stream=sys.stdout):
        if output_stream is sys.stdout: sys.stdout = sys.stderr
        with ThreadPoolExecutor(thread_name_prefix="fengyu-worker") as pool:
            for line in input_stream:
                try:
                    req = json.loads(line); method = req["method"]; params = req.get("params") or {}; rid = req.get("id")
                    if req.get("jsonrpc") != "2.0": raise ValueError("invalid JSON-RPC version")
                    if method == "$/fengyu/initialize": self.initialize(rid, params, output_stream)
                    elif method == "$/cancelRequest": self.cancel(params.get("id"))
                    elif method == "$/fengyu/logging/setLevel":
                        if rid is not None: self.write(output_stream, {"jsonrpc":"2.0","id":rid,"result":{"level":params.get("level","INFO")}})
                    else:
                        event = threading.Event(); key = None if rid is None else str(rid)
                        if key is not None:
                            with self.lock:
                                if key in self.pending: self.pending[key].set()
                                self.pending[key] = event
                        locale = (req.get("_fengyu") or {}).get("locale") or params.get("locale")
                        pool.submit(self.dispatch, rid, method, params, locale, event, output_stream)
                except Exception as error:
                    self.write(output_stream, self.error(None, -32600, str(error), "INVALID_REQUEST"))
    def initialize(self, rid, params, output):
        if params.get("protocolVersion") != PROTOCOL_VERSION:
            self.write(output, self.error(rid, -32602, "unsupported protocol", "PROTOCOL_MISMATCH")); return
        self.write(output, {"jsonrpc":"2.0","id":rid,"result":{"protocolVersion":1,"runtime":"python","sdkVersion":"2.0.0","capabilities":["cancellation","locale","structuredLogs"]}})
    def dispatch(self, rid, method, params, locale, event, output):
        key = None if rid is None else str(rid)
        ctx = RpcContext(key, os.getenv("FENGYU_PLUGIN_ID"), os.getenv("FENGYU_PLUGIN_ROOT"), locale, event)
        token = _current.set(ctx)
        try:
            if method not in self.handlers: raise RpcError("Unknown method: " + method, "METHOD_NOT_FOUND")
            result = self.handlers[method](params, ctx); ctx.check_cancelled()
            if rid is not None: self.write(output, {"jsonrpc":"2.0","id":rid,"result":result})
        except RpcError as error:
            if rid is not None: self.write(output, self.error(rid, -32000, str(error), error.code))
        except Exception as error:
            if rid is not None: self.write(output, self.error(rid, -32603, type(error).__name__, "INTERNAL_ERROR"))
        finally:
            _current.reset(token)
            if key is not None:
                with self.lock:
                    if self.pending.get(key) is event: self.pending.pop(key, None)
    def cancel(self, rid):
        with self.lock: event = self.pending.get(str(rid))
        if event: event.set()
    def write(self, output, frame):
        with self.write_lock: output.write(json.dumps(frame, separators=(",",":"), ensure_ascii=False)+"\n"); output.flush()
    @staticmethod
    def error(rid, code, message, data): return {"jsonrpc":"2.0","id":rid,"error":{"code":code,"message":message,"data":{"code":data}}}

def current_context():
    value = _current.get()
    if value is None: raise RuntimeError("no RPC context")
    return value
