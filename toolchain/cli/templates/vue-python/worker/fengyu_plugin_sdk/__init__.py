"""Vendored FengYu Python Worker SDK; replace with the published package when desired."""
from __future__ import annotations
import contextvars, json, os, secrets, socket, sys, threading, types
from concurrent.futures import ThreadPoolExecutor
from dataclasses import MISSING, dataclass, fields, is_dataclass
from pathlib import Path
from typing import Annotated, Any, Callable, Literal, Union, get_args, get_origin, get_type_hints

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

@dataclass(frozen=True)
class Field:
    description: str = ""
    title: str = ""

def _schema_for(annotation):
    metadata=()
    if get_origin(annotation) is Annotated: annotation,*metadata=get_args(annotation)
    origin,args=get_origin(annotation),get_args(annotation)
    if annotation is Any: schema={}
    elif annotation is str: schema={"type":"string"}
    elif annotation is bool: schema={"type":"boolean"}
    elif annotation is int: schema={"type":"integer"}
    elif annotation is float: schema={"type":"number"}
    elif origin is Literal:
        values=list(args); schema=_schema_for(type(values[0])) if values else {}; schema["enum"]=values
    elif origin in (list,tuple,set): schema={"type":"array","items":_schema_for(args[0] if args else Any)}
    elif origin is dict: schema={"type":"object","additionalProperties":_schema_for(args[1] if len(args)>1 else Any)}
    elif origin in (Union,types.UnionType):
        variants=[value for value in args if value is not type(None)]; schema=_schema_for(variants[0]) if len(variants)==1 else {"anyOf":[_schema_for(value) for value in variants]}
    elif is_dataclass(annotation):
        hints=get_type_hints(annotation,include_extras=True); properties={}; required=[]
        for item in fields(annotation):
            item_type=hints[item.name]; properties[item.name]=_schema_for(item_type); optional=type(None) in get_args(item_type)
            if item.default is not MISSING: properties[item.name]["default"]=item.default
            elif item.default_factory is not MISSING: properties[item.name]["default"]=item.default_factory()
            elif not optional: required.append(item.name)
        schema={"type":"object","properties":properties}
        if required: schema["required"]=required
    else: raise TypeError(f"unsupported FengYu contract type: {annotation!r}")
    for item in metadata:
        if isinstance(item,Field):
            if item.description: schema["description"]=item.description
            if item.title: schema["title"]=item.title
    return schema

class Contract:
    def __init__(self,plugin_id): self.plugin_id=plugin_id; self.methods={}; self.tools=[]; self.origins={}
    def rpc(self,name,description,input_type,output_type,*,origin=None):
        self.methods[name]={"description":description,"inputSchema":_schema_for(input_type),"outputSchema":_schema_for(output_type)}
        if origin: self.origins[f"rpc.methods.{name}"]=origin
        return self
    def ai_tool(self,name,method,description,*,effect="read",idempotent=None):
        tool={"name":name,"method":method,"description":description,"effect":effect}
        if idempotent is not None: tool["idempotent"]=idempotent
        self.tools.append(tool); return self
    def to_dict(self): return {"formatVersion":1,"pluginId":self.plugin_id,"rpc":{"methods":self.methods},"aiTools":self.tools,"origins":self.origins}
    def write(self,plugin_root):
        output=Path(plugin_root)/"target"/"fengyu-contract"/"contract.json"; output.parent.mkdir(parents=True,exist_ok=True); output.write_text(json.dumps(self.to_dict(),indent=2,ensure_ascii=False)+"\n",encoding="utf-8"); return output

class Worker:
    def __init__(self):
        self.handlers: dict[str, Callable] = {}
        self.pending: dict[str, threading.Event] = {}
        self.lock, self.write_lock = threading.Lock(), threading.Lock()
        self.plugin_id = self.plugin_root = None
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
    def serve_tcp(self, host="127.0.0.1", port=24057, plugin_id=None, plugin_root=None):
        if host not in {"127.0.0.1", "::1", "localhost"}: raise ValueError("development worker must bind to loopback")
        self.plugin_id, self.plugin_root = plugin_id, plugin_root
        token_dir = os.path.join(os.path.expanduser("~"), ".fengyu"); os.makedirs(token_dir, mode=0o700, exist_ok=True)
        token_path = os.path.join(token_dir, f"dev-token-{port}"); token = secrets.token_urlsafe(32)
        descriptor = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600); os.chmod(token_path, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as token_file: token_file.write(token + "\n")
        family = socket.AF_INET6 if host == "::1" else socket.AF_INET
        with socket.socket(family, socket.SOCK_STREAM) as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); server.bind((host, port)); server.listen()
            while True:
                connection, _ = server.accept()
                threading.Thread(target=self._serve_connection, args=(connection, token), daemon=True).start()
    def _serve_connection(self, connection, token):
        with connection:
            reader = connection.makefile("r", encoding="utf-8", newline="\n"); writer = connection.makefile("w", encoding="utf-8", newline="\n")
            try:
                if reader.readline().rstrip("\r\n") != f"AUTH {token}": return
                self.run(reader, writer)
            finally: reader.close(); writer.close()
    def initialize(self, rid, params, output):
        if params.get("protocolVersion") != PROTOCOL_VERSION:
            self.write(output, self.error(rid, -32602, "unsupported protocol", "PROTOCOL_MISMATCH")); return
        self.write(output, {"jsonrpc":"2.0","id":rid,"result":{"protocolVersion":1,"runtime":"python","sdkVersion":"2.0.0","capabilities":["cancellation","locale","structuredLogs"]}})
    def dispatch(self, rid, method, params, locale, event, output):
        key = None if rid is None else str(rid)
        ctx = RpcContext(key, self.plugin_id or os.getenv("FENGYU_PLUGIN_ID"), self.plugin_root or os.getenv("FENGYU_PLUGIN_ROOT"), locale, event)
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
