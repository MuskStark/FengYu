import io
import json
import unittest

from fengyu_plugin_sdk import Worker


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


if __name__ == "__main__":
    unittest.main()
