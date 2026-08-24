"""Dependency-free FengYu JSON-RPC 2.0 worker runtime."""

from __future__ import annotations

import contextvars
import json
import os
import secrets
import socket
import sys
import threading
import types
from concurrent.futures import ThreadPoolExecutor
from dataclasses import MISSING, dataclass, fields, is_dataclass
from pathlib import Path
from typing import Annotated, Any, Callable, Literal, TextIO, Union, get_args, get_origin, get_type_hints

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


@dataclass(frozen=True)
class Field:
    """Display metadata attached to a typed contract field with ``Annotated``."""
    description: str = ""
    title: str = ""


def _schema_for(annotation: Any) -> dict[str, Any]:
    metadata: tuple[Any, ...] = ()
    if get_origin(annotation) is Annotated:
        annotation, *metadata = get_args(annotation)
    origin, args = get_origin(annotation), get_args(annotation)
    if annotation is Any:
        schema: dict[str, Any] = {}
    elif annotation is str:
        schema = {"type": "string"}
    elif annotation is bool:
        schema = {"type": "boolean"}
    elif annotation is int:
        schema = {"type": "integer"}
    elif annotation is float:
        schema = {"type": "number"}
    elif origin is Literal:
        values = list(args)
        schema = _schema_for(type(values[0])) if values else {}
        schema["enum"] = values
    elif origin in (list, tuple, set):
        schema = {"type": "array", "items": _schema_for(args[0] if args else Any)}
    elif origin is dict:
        schema = {"type": "object", "additionalProperties": _schema_for(args[1] if len(args) > 1 else Any)}
    elif origin in (Union, types.UnionType):
        variants = [value for value in args if value is not type(None)]
        schema = _schema_for(variants[0]) if len(variants) == 1 else {"anyOf": [_schema_for(value) for value in variants]}
    elif is_dataclass(annotation):
        hints = get_type_hints(annotation, include_extras=True)
        properties: dict[str, Any] = {}
        required: list[str] = []
        for item in fields(annotation):
            item_type = hints[item.name]
            properties[item.name] = _schema_for(item_type)
            optional = type(None) in get_args(item_type)
            if item.default is not MISSING:
                properties[item.name]["default"] = item.default
            elif item.default_factory is not MISSING:  # type: ignore[comparison-overlap]
                properties[item.name]["default"] = item.default_factory()
            elif not optional:
                required.append(item.name)
        schema = {"type": "object", "properties": properties}
        if required:
            schema["required"] = required
    else:
        raise TypeError(f"unsupported FengYu contract type: {annotation!r}")
    for item in metadata:
        if isinstance(item, Field):
            if item.description:
                schema["description"] = item.description
            if item.title:
                schema["title"] = item.title
    return schema


class Contract:
    """Small typed builder for the code-first contract IR consumed by ``fengyu generate``."""
    def __init__(self, plugin_id: str) -> None:
        self.plugin_id = plugin_id
        self.methods: dict[str, Any] = {}
        self.tools: list[dict[str, Any]] = []
        self.origins: dict[str, str] = {}

    def rpc(self, name: str, description: str, input_type: type, output_type: type,
            *, origin: str | None = None) -> "Contract":
        self.methods[name] = {"description": description, "inputSchema": _schema_for(input_type),
                              "outputSchema": _schema_for(output_type)}
        if origin:
            self.origins[f"rpc.methods.{name}"] = origin
        return self

    def ai_tool(self, name: str, method: str, description: str, *, effect: str = "read",
                idempotent: bool | None = None) -> "Contract":
        tool: dict[str, Any] = {"name": name, "method": method,
                                "description": description, "effect": effect}
        if idempotent is not None:
            tool["idempotent"] = idempotent
        self.tools.append(tool)
        return self

    def to_dict(self) -> dict[str, Any]:
        return {"formatVersion": 1, "pluginId": self.plugin_id,
                "rpc": {"methods": self.methods}, "aiTools": self.tools,
                "origins": self.origins}

    def write(self, plugin_root: str | Path) -> Path:
        output = Path(plugin_root) / "target" / "fengyu-contract" / "contract.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(self.to_dict(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        return output


class Worker:
    def __init__(self) -> None:
        self._handlers: dict[str, Handler] = {}
        self._pending: dict[str, threading.Event] = {}
        self._pending_lock = threading.Lock()
        self._write_lock = threading.Lock()
        self._plugin_id: str | None = None
        self._plugin_root: str | None = None

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

    def serve_tcp(self, host: str = "127.0.0.1", port: int = 24057,
                  plugin_id: str | None = None, plugin_root: str | None = None) -> None:
        """Serve the IDE/Vite development protocol on an authenticated loopback socket."""
        if host not in {"127.0.0.1", "::1", "localhost"}:
            raise ValueError("the FengYu development worker must bind to loopback")
        self._plugin_id = plugin_id
        self._plugin_root = plugin_root
        token_dir = os.path.join(os.path.expanduser("~"), ".fengyu")
        os.makedirs(token_dir, mode=0o700, exist_ok=True)
        token_path = os.path.join(token_dir, f"dev-token-{port}")
        token = secrets.token_urlsafe(32)
        descriptor = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.chmod(token_path, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as token_file:
            token_file.write(token + "\n")

        family = socket.AF_INET6 if host == "::1" else socket.AF_INET
        with socket.socket(family, socket.SOCK_STREAM) as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind((host, port))
            server.listen()
            while True:
                connection, _ = server.accept()
                threading.Thread(target=self._serve_connection,
                                 args=(connection, token), daemon=True).start()

    def _serve_connection(self, connection: socket.socket, token: str) -> None:
        with connection:
            reader = connection.makefile("r", encoding="utf-8", newline="\n")
            writer = connection.makefile("w", encoding="utf-8", newline="\n")
            try:
                if reader.readline().rstrip("\r\n") != f"AUTH {token}":
                    return
                self.run(reader, writer)
            finally:
                reader.close()
                writer.close()

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
        token = _context.set(RpcContext(key, self._plugin_id or os.getenv("FENGYU_PLUGIN_ID"),
            self._plugin_root or os.getenv("FENGYU_PLUGIN_ROOT"), locale, cancelled))
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


__all__ = ["Contract", "Field", "Worker", "RpcContext", "RpcError", "current_context", "PROTOCOL_VERSION"]
