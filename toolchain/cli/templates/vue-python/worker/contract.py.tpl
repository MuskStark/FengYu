"""Typed code-first contract extracted by `fengyu generate`."""

from dataclasses import dataclass
from pathlib import Path
from typing import Annotated

from fengyu_plugin_sdk import Contract, Field

HELLO_METHOD = "hello"


@dataclass
class HelloInput:
    name: Annotated[str, Field("Name to greet.")]


@dataclass
class HelloOutput:
    message: Annotated[str, Field("Rendered greeting.")]


CONTRACT = Contract("{{pluginId}}").rpc(
    HELLO_METHOD,
    "Echo a greeting back to the UI.",
    HelloInput,
    HelloOutput,
    origin="worker/contract.py",
)


if __name__ == "__main__":
    CONTRACT.write(Path(__file__).resolve().parent.parent)
