"""E2e smoke fixture worker: the conventional python runtime shape.

Mirrors the `fengyu init --runtime python` scaffold exactly — the entrypoint
imports its sibling contract module at run time, which is the packaging
surface this fixture exists to pin (a package missing contract.py crashes at
import on the host).
"""

from fengyu_plugin_sdk import Worker
from contract import HELLO_METHOD


def hello(params, _context):
    return {"message": f"Hello, {params['name']}"}


def create_worker():
    return Worker().on(HELLO_METHOD, hello)


if __name__ == "__main__":
    create_worker().run()
