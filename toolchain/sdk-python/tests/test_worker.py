import io
import json
import multiprocessing
import os
import socket
import tempfile
import time
import unittest
from dataclasses import dataclass
from pathlib import Path
from typing import Annotated

from fengyu_plugin_sdk import Contract, Field, Worker


def _serve_worker(home: str, port: int) -> None:
    os.environ["HOME"] = home
    Worker().on("hello", lambda params, ctx: {
        "message": "Hello, " + params["name"], "pluginId": ctx.plugin_id,
    }).serve_tcp(port=port, plugin_id="com.example.python", plugin_root="/plugin")


class WorkerTest(unittest.TestCase):
    def test_handshake_and_handler(self):
        source = io.StringIO(
            '{"jsonrpc":"2.0","id":"init","method":"$/fengyu/initialize","params":{"protocolVersion":1}}\n'
            '{"jsonrpc":"2.0","id":"1","method":"hello","params":{"name":"Ada"}}\n'
        )
        output = io.StringIO()
        Worker().on("hello", lambda params, _ctx: {"message": "Hello, " + params["name"]}).run(source, output)
        frames = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual("python", frames[0]["result"]["runtime"])
        self.assertEqual("Hello, Ada", frames[1]["result"]["message"])

    def test_authenticated_tcp_development_loop(self):
        with tempfile.TemporaryDirectory() as home:
            probe = socket.socket()
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
            probe.close()
            process = multiprocessing.Process(target=_serve_worker, args=(home, port), daemon=True)
            process.start()
            token_path = Path(home) / ".fengyu" / f"dev-token-{port}"
            try:
                deadline = time.time() + 5
                while not token_path.exists() and time.time() < deadline:
                    time.sleep(0.02)
                token = token_path.read_text(encoding="utf-8").strip()
                with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
                    wire = connection.makefile("rw", encoding="utf-8", newline="\n")
                    wire.write(f"AUTH {token}\n")
                    wire.write('{"jsonrpc":"2.0","id":"1","method":"hello","params":{"name":"Ada"}}\n')
                    wire.flush()
                    response = json.loads(wire.readline())
                self.assertEqual("Hello, Ada", response["result"]["message"])
                self.assertEqual("com.example.python", response["result"]["pluginId"])
                self.assertEqual(0o600, token_path.stat().st_mode & 0o777)
            finally:
                process.terminate()
                process.join(2)

    def test_typed_contract_generates_json_schema(self):
        @dataclass
        class Input:
            name: Annotated[str, Field("Name to greet.")]
            project_dir: Annotated[str, Field(
                "Writable project directory.",
                format="fengyu-directory",
                file_access="read-write",
            )] = ""
            count: int = 2

        @dataclass
        class Output:
            message: str

        with tempfile.TemporaryDirectory() as root:
            output = Contract("com.example.python").rpc("hello", "Greeting", Input, Output).write(root)
            method = json.loads(output.read_text(encoding="utf-8"))["rpc"]["methods"]["hello"]
            self.assertEqual(["name"], method["inputSchema"]["required"])
            self.assertEqual(2, method["inputSchema"]["properties"]["count"]["default"])
            self.assertEqual("Name to greet.", method["inputSchema"]["properties"]["name"]["description"])
            project = method["inputSchema"]["properties"]["project_dir"]
            self.assertEqual("fengyu-directory", project["format"])
            self.assertEqual("read-write", project["x-fengyu-file-access"])

    def test_typed_contract_rejects_file_access_without_format(self):
        @dataclass
        class Input:
            path: Annotated[str, Field(file_access="read-write")]

        with self.assertRaisesRegex(TypeError, "requires a FengYu file format"):
            Contract("com.example.python").rpc("bad", "Bad", Input, Input)

    def test_typed_contract_rejects_file_format_on_non_string(self):
        @dataclass
        class Input:
            path: Annotated[int, Field(format="fengyu-file")]

        with self.assertRaisesRegex(TypeError, "requires a string field"):
            Contract("com.example.python").rpc("bad", "Bad", Input, Input)


if __name__ == "__main__":
    unittest.main()
