import argparse
from pathlib import Path

from fengyu_plugin_sdk import Worker
from contract import HELLO_METHOD


def hello(params, _context):
    return {"message": f"Hello, {params['name']}"}


def create_worker():
    return Worker().on(HELLO_METHOD, hello)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--dev", action="store_true")
    parser.add_argument("--port", type=int, default=24057)
    args = parser.parse_args()
    worker = create_worker()
    if args.dev:
        worker.serve_tcp(port=args.port, plugin_id="{{pluginId}}",
                         plugin_root=str(Path(__file__).resolve().parent.parent))
    else:
        worker.run()
