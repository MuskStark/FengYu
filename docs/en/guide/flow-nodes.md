---
title: Flow Nodes
description: How the flow canvas moves data between nodes — references, the variable tree, branch conditions, and single-step debugging.
lang: en
---

# Flow Nodes

The FengyuFlow canvas is a **dataflow editor**: every node is one tool invocation, every edge says "this node runs after that one and may read its output". This page explains what flows along the wires and how to debug one node in isolation. For the authoring side of the node declaration protocol, see [Flow nodes](../plugins/manifest.md#flownodes) in the plugin manifest reference.

## Nodes and edges

- **Start** — the visual editor for the flow's run-time inputs. Each declared input renders as a typed chip row; referencing one on the canvas is <code v-pre>{{inputs.email}}</code>.
- **Action nodes** — one tool each. The inspector form is driven by the plugin's `flowNodes` declaration (typed fields, placeholders, examples, help) or, for undeclared tools, by a schema-derived fallback form.
- **Condition node (IF)** — the built-in `flow_if` control node. It compares two values and exposes two output ports, **true / false**. Every action node has exactly one output port; the condition node is the only one with two, because the port an edge leaves from *is* the branch semantics.

Edges are dependencies: a node runs once everything upstream has finished (ready steps run concurrently). Wiring is whole-node — you don't connect individual fields, you bind fields to upstream *outputs*.

## Getting data in: references

Every input has three source states:

| State | What it does |
|---|---|
| ✏️ Manual | A literal value typed into the widget |
| 🔗 Reference | Pick from the **variable tree**: workflow inputs or any upstream node's output field, filtered by the expected type |
| ƒ Expression | Raw text with <code v-pre>{{ }}</code> placeholders — string templates for experts |

Both reference states serialize to the same syntax: <code v-pre>{{node.&lt;id&gt;.result.&lt;path&gt;}}</code> for upstream outputs (paths accept array indexes, e.g. `result.files[0]`), <code v-pre>{{inputs.&lt;name&gt;}}</code> for run-time inputs. Unknown references are flagged before you can save.

## Seeing what a node outputs

Anywhere outputs are listed you get three tiers, honestly labeled:

1. **Declared** — the plugin's output schema (field names and types).
2. **Example** — the declaration's example values.
3. **Last run** — the node's real output from the most recent execution (truncated to 16 KB in the graph).

Hover a node's output port for the declared contract; open the node to browse the full output tree, copy a <code v-pre>{{node.…}}</code> reference path, or pin the last run.

## Branch conditions and skips

Draw an edge from the IF node's **true** or **false** port and the compile step turns it into a `runWhen` condition on the target. At run time:

- The IF node evaluates (`contains`, `gt`, `is_empty`, … — both operands may bind upstream references) and outputs `{"branch":"true|false"}`.
- Steps whose branch did not fire are recorded **skipped** — gray badge on the node, no result, no tool call.
- A step whose dependencies were *all* skipped is skipped too (the dead branch cascades). A step with any live dependency still runs.

## The AI generation node (LLM)

The built-in **AI 生成** node runs one non-interactive model completion as an ordinary
flow step, using whatever provider the AI settings currently have active:

- **提示词 (prompt)** — the full instruction; bind upstream outputs with 🔗 references
  exactly like any other input (e.g. hand the Excel summary to the model for rewriting).
- **系统提示词 / 温度** (advanced) — an optional role ("你是邮件文案助手") and an optional
  sampling temperature 0–2; blank falls back to the global AI settings.
- **输出 Schema** (advanced) — a JSON Schema object. When set, the model is instructed to
  answer with a matching JSON object, available under the **结构化数据 (data)** output for
  field-by-field referencing (`{{node.x.result.data.sentiment}}`). If the reply fails to
  parse or misses required fields, one repair retry feeds the exact error back into the
  prompt. The **原文 (text)** output always carries the raw reply — structuring can fail,
  the answer never disappears. Pair `data` fields with an IF node to branch on them.

The node builds a fresh model client per call, so it works inside parallel steps and even
in flows executed from chat via `run_current_flow` without contending with the active
conversation.

## Debugging a single node

**Run this node** (in the node inspector's output section) executes *only that node*:

- Upstream references resolve from each upstream node's **pinned** result or **last run** value — no ancestors are re-executed.
- Workflow inputs bind from the current values in the flow settings.
- The result lands in the same places a full run fills: the node badge, the execution panel, run history, and the node's last-run preview.

If an upstream node has neither a pin nor a last run, the run tells you which one to run or pin first. Combined with **rewind** (re-run from step N after a full run) and **pin**, you can iterate on one node without the whole chain passing.

## Pinned results

Pinning freezes a node's last run as its authored result: the engine serves the pinned value verbatim, never calling the tool — useful to iterate downstream against a known payload. Pinned nodes carry a 📌 marker; publishing a flow with pins is allowed but the pins stay in effect until removed.
