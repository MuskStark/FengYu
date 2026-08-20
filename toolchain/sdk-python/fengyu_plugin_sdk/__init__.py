"""Dependency-free FengYu JSON-RPC 2.0 worker runtime."""

from __future__ import annotations

import contextvars
import json
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any, Callable, TextIO

PROTOCOL_VERSION = 1
INITIALIZE_METHOD = "$/fengyu/initialize"
CANCEL_METHOD = "$/cancelRequest"
SET_LOG_LEVEL_METHOD = "$/fengyu/logging/setLevel"


class RpcError(Exception):
    def __init__(self, message: str, code: str = "INTERNAL_ERROR") -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class RpcContext:
    call_id: str | None
    plugin_id: str | None
    plugin_root: str | None
    locale: str | None
    cancelled: threading.Event

    def check_cancelled(self) -> None:
        if self.cancelled.is_set():
            raise RpcError("request cancelled", "CANCELLED")


_context: contextvars.ContextVar[RpcContext | None] = contextvars.ContextVar("fengyu_rpc_context", default=None)


def current_context() -> RpcContext:
    value = _context.get()
    if value is None:
        raise RuntimeError("no FengYu RPC context is bound")
    return value


Handler = Callable[[dict[str, Any], RpcContext], Any]


class Worker:
    def __init__(self) -> None:
        self._handlers: dict[str, Handler] = {}
        self._pending: dict[str, threading.Event] = {}
        self._pending_lock = threading.Lock()
        self._write_lock = threading.Lock()

    def on(self, method: str, handler: Handler) -> "Worker":
        if not method or method.startswith("$/fengyu/"):
            raise ValueError("method is required and must not use the reserved $/fengyu/ namespace")
        if method in self._handlers:
            raise ValueError(f"duplicate method: {method}")
        self._handlers[method] = handler
        return self

    def run(self, input_stream: TextIO = sys.stdin, output_stream: TextIO = sys.stdout) -> None:
        # Handlers may print freely; keep stdout protocol-only once the worker starts.
        if output_stream is sys.stdout:
            sys.stdout = sys.stderr
        with ThreadPoolExecutor(thread_name_prefix="fengyu-worker") as pool:
            for raw in input_stream:
                try:
                    request = json.loads(raw)
                    self._validate_request(request)
                    method = request["method"]
                    params = request.get("params") or {}
                    request_id = request.get("id")
                    if method == INITIALIZE_METHOD:
                        self._initialize(request_id, params, output_stream)
                    elif method == CANCEL_METHOD:
                        self._cancel(params.get("id"))
                    elif method == SET_LOG_LEVEL_METHOD:
                        if request_id is not None:
                            self._write(output_stream, self._result(request_id, {"level": params.get("level", "INFO")}))
                    else:
                        cancelled = threading.Event()
                        key = str(request_id) if request_id is not None else None
                        if key is not None:
                            with self._pending_lock:
                                previous = self._pending.get(key)
                                if previous is not None:
                                    previous.set()
                                self._pending[key] = cancelled
                        locale = ((request.get("_fengyu") or {}).get("locale")
                                  or params.get("locale"))
                        pool.submit(self._dispatch, request_id, method, params, locale,
                                    cancelled, output_stream)
                except Exception as error:
                    request_id = request.get("id") if isinstance(locals().get("request"), dict) else None
                    self._write(output_stream, self._error(request_id, -32600, str(error), "INVALID_REQUEST"))

    def _initialize(self, request_id: Any, params: dict[str, Any], output: TextIO) -> None:
        requested = params.get("protocolVersion")
        if requested != PROTOCOL_VERSION:
            self._write(output, self._error(request_id, -32602,
                f"Unsupported FengYu worker protocol: {requested}", "PROTOCOL_MISMATCH"))
            return
        self._write(output, self._result(request_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "runtime": "python",
            "sdkVersion": "2.0.0",
            "capabilities": ["cancellation", "locale", "structuredLogs"],
        }))

    def _dispatch(self, request_id: Any, method: str, params: dict[str, Any],
                  locale: str | None, cancelled: threading.Event, output: TextIO) -> None:
        key = str(request_id) if request_id is not None else None
        token = _context.set(RpcContext(key, os.getenv("FENGYU_PLUGIN_ID"),
            os.getenv("FENGYU_PLUGIN_ROOT"), locale, cancelled))
        try:
            handler = self._handlers.get(method)
            if handler is None:
                raise RpcError(f"Unknown method: {method}", "METHOD_NOT_FOUND")
            result = handler(params, current_context())
            current_context().check_cancelled()
            if request_id is not None:
                self._write(output, self._result(request_id, result))
        except RpcError as error:
            if request_id is not None:
                self._write(output, self._error(request_id, -32000, str(error), error.code))
        except Exception as error:
            if request_id is not None:
                self._write(output, self._error(request_id, -32603,
                    f"handler failed: {type(error).__name__}", "INTERNAL_ERROR"))
        finally:
            _context.reset(token)
            if key is not None:
                with self._pending_lock:
                    if self._pending.get(key) is cancelled:
                        self._pending.pop(key, None)

    def _cancel(self, request_id: Any) -> None:
        if request_id is None:
            return
        with self._pending_lock:
            event = self._pending.get(str(request_id))
        if event is not None:
            event.set()

    def _write(self, output: TextIO, frame: dict[str, Any]) -> None:
        wire = json.dumps(frame, separators=(",", ":"), ensure_ascii=False)
        with self._write_lock:
            output.write(wire + "\n")
            output.flush()

    @staticmethod
    def _validate_request(request: Any) -> None:
        if not isinstance(request, dict) or request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str):
            raise ValueError("invalid JSON-RPC 2.0 request")

    @staticmethod
    def _result(request_id: Any, result: Any) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    @staticmethod
    def _error(request_id: Any, code: int, message: str, data_code: str) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id,
                "error": {"code": code, "message": message, "data": {"code": data_code}}}


__all__ = ["Worker", "RpcContext", "RpcError", "current_context", "PROTOCOL_VERSION"]
