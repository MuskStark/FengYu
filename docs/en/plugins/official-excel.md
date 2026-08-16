---
title: Official Plugin — Excel
description: Walkthrough of fan.summer.excel (v4.0.0) — a file-category plugin with files.read/files.write permissions, three actions (analyze, configure, split) and six aiTools, three split modes (BY_SHEET, BY_COLUMN, COMPLEX), file I/O usage, and a four-step wizard MF.
lang: en
---

# Official Plugin — Excel

`fan.summer.excel` is the more capable of the two shipped official plugins. It splits an Excel workbook into multiple files by sheet, by column value, or by complex rules, writing the results into an output directory the user picks. It is the canonical example of a plugin that combines permissions, file I/O, and AI tools.

## What it does

- Reads an uploaded Excel file and reports its sheets and headers.
- Lets the user (or the AI) choose one of three split modes and configure it.
- Executes the split, writing one file per result into an authorized output directory.
- Exposes six AI tools so a chat/agent flow can drive the whole pipeline without the UI.

## The manifest

```json
{
  "schemaVersion": 2,
  "id": "fan.summer.excel",
  "name": "Excel Splitter",
  "description": "Split Excel workbooks by sheet, column value, or complex rules",
  "version": "4.0.0",
  "author": "FengYu",
  "icon": "file-excel",
  "category": "file",
  "ui": { "entry": "ui/index.html" },
  "backend": { "callTimeoutSeconds": 30 },
  "permissions": ["files.read", "files.write"],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "rpc": {
    "methods": {
      "excel_analyze": {
        "description": "Analyze an Excel file and return sheets and headers.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "filePath": { "type": "string", "description": "Resolved path of a granted FengYu FileRef." }
          },
          "required": ["filePath"]
        },
        "outputSchema": {
          "type": "object",
          "properties": { "success": { "type": "boolean" }, "summary": { "type": "string" } },
          "required": ["success", "summary"]
        }
      },
      "excel_configure": {
        "description": "Configure BY_SHEET, BY_COLUMN, or COMPLEX splitting.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "mode": { "type": "string", "enum": ["BY_SHEET", "BY_COLUMN", "COMPLEX"] },
            "sheets": { "type": "array", "items": { "type": "string" } },
            "splitSheet": { "type": "string" },
            "splitColumn": { "type": "string" }
          },
          "required": ["mode"]
        },
        "outputSchema": {
          "type": "object",
          "properties": { "success": { "type": "boolean" }, "summary": { "type": "string" } },
          "required": ["success", "summary"]
        }
      },
      "excel_complex_config": {
        "description": "Add, list, or clear complex split rules.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "action": { "type": "string", "enum": ["add", "list", "clear"] },
            "sheetName": { "type": "string" },
            "headerIndex": { "type": "integer" },
            "columnIndex": { "type": "integer" }
          },
          "required": ["action"]
        },
        "outputSchema": {
          "type": "object",
          "properties": { "success": { "type": "boolean" }, "summary": { "type": "string" } },
          "required": ["success", "summary"]
        }
      },
      "excel_execute": {
        "description": "Execute the configured split into an authorized output directory.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "outputDir": { "type": "string", "description": "Resolved writable FengYu output directory." },
            "filePrefix": { "type": "string" }
          },
          "required": ["outputDir"]
        },
        "outputSchema": {
          "type": "object",
          "properties": { "success": { "type": "boolean" }, "summary": { "type": "string" } },
          "required": ["success", "summary"]
        }
      },
      "excel_query": {
        "description": "Query the active Excel split session.",
        "inputSchema": { "type": "object", "properties": {} },
        "outputSchema": {
          "type": "object",
          "properties": { "success": { "type": "boolean" }, "summary": { "type": "string" } },
          "required": ["success", "summary"]
        }
      },
      "excel_cancel": {
        "description": "Cancel and clear the active Excel split session.",
        "inputSchema": { "type": "object", "properties": {} },
        "outputSchema": {
          "type": "object",
          "properties": { "success": { "type": "boolean" }, "summary": { "type": "string" } },
          "required": ["success", "summary"]
        }
      }
    }
  },
  "aiTools": [
    { "name": "excel_analyze", "method": "excel_analyze", "effect": "read", "description": "Analyze an Excel file and return sheets and headers." },
    { "name": "excel_configure", "method": "excel_configure", "effect": "write", "description": "Configure BY_SHEET, BY_COLUMN, or COMPLEX splitting." },
    { "name": "excel_complex_config", "method": "excel_complex_config", "effect": "write", "description": "Add, list, or clear complex split rules." },
    { "name": "excel_execute", "method": "excel_execute", "effect": "write", "description": "Execute the configured split into an authorized output directory." },
    { "name": "excel_query", "method": "excel_query", "effect": "read", "description": "Query the active Excel split session." },
    { "name": "excel_cancel", "method": "excel_cancel", "effect": "write", "description": "Cancel and clear the active Excel split session." }
  ]
}
```

Key points:

- **`category: "file"`** — a file-processing plugin.
- **`permissions: ["files.read", "files.write"]`** — it reads an uploaded input file and writes split files into an output directory (then exports a zip). Both permissions are required.
- **`aiTools`** has six entries, so `supportsAi` is `true`. Each `{name, method, effect, description}` references an `rpc.methods` entry whose typed input/output schemas the host reads to build the Spring AI `ToolDefinition`.
- **`backend.callTimeoutSeconds: 30`** — the host spawns the standard `backend/worker.jar` and drives it over JSON-RPC on stdio.

## The three actions

Beyond the six AI-tool methods, the worker exposes three **actions** the UI drives directly (registered in `ExcelWorkerMain`):

| Action | Purpose |
| --- | --- |
| `analyze` | Inspect the input file and return its sheets + headers (the UI side of `excel_analyze`). |
| `configure` | Set the split mode and its parameters (the UI side of `excel_configure`). |
| `split` | Execute the configured split into the output directory (the UI side of `excel_execute`). |

The action methods and the `excel_*` AI-tool methods share the same underlying services — the AI tools are the same pipeline the wizard drives, just callable from chat. See [AI Tools](/en/plugins/ai-tools) and [Worker (JSON-RPC)](/en/plugins/worker).

## The three split modes

| Mode | Behavior |
| --- | --- |
| `BY_SHEET` | Produce one output file per selected sheet. |
| `BY_COLUMN` | Group rows by the unique values of a chosen column; one file per value. |
| `COMPLEX` | Apply multi-config rules (add/list/clear via `excel_complex_config`) for finer-grained splits. |

`excel_configure` sets `mode` (enum `BY_SHEET | BY_COLUMN | COMPLEX`) plus mode-specific fields (`sheets`, `splitSheet`, `splitColumn`); `excel_complex_config` manages the rule list for `COMPLEX` mode with `action` enum `add | list | clear`.

`excel_complex_config` is workflow-friendly: `action: "add"` accepts an `entries` array declaring the
**complete** rule set in one call (one rule per sheet; `columnName` resolves to a column index
against the analysis, so raw indexes are never required), and an optional `filePath` analyzes the
workbook in the same call — after a successful add the session is already in `COMPLEX` mode, so a
FengyuFlow canvas needs just `excel_complex_config → excel_execute`. `excel_execute` with a blank
`outputDir` writes into the plugin's default output folder (always sandbox-writable, no grant
needed); a typed arbitrary path is still allowed but must survive the worker sandbox.

## File I/O usage

The plugin uses the host's file-grant model end to end (see [File I/O](/en/plugins/file-io)):

1. **Analyze reads an uploaded file.** The UI calls `fengyu.files.open({ extensions: ['xlsx'] })` → `POST .../files/upload` (needs `files.read`) → a `FileRef`. It passes that ref to `analyze` / `excel_analyze`; the host rewrites the `ref_*` to an absolute path before the worker sees it.
2. **Execute writes split files into an output directory.** The UI calls `fengyu.files.outputDirectory()` → `POST .../files/output` (needs `files.write`) → a writable `DirectoryRef`, passed to `split` / `excel_execute` as `outputDir`. The worker writes the split files there.
3. **Export zips it.** `fengyu.files.export(outDir)` → `GET .../files/export/{ref}` (needs `files.write`) streams a zip of the output directory for download.

```js
import { createPluginRpc } from './generated/fengyu-rpc'
const rpc = createPluginRpc(fengyu)

const file   = await fengyu.files.open({ extensions: ['xlsx'] })   // files.read
await rpc.analyze({ filePath: file as unknown as string })         // host rewrites ref → path

const outDir = await fengyu.files.outputDirectory()                // files.write
await rpc.split({ outputDir: outDir as unknown as string, filePrefix: 'q3-' })
await fengyu.files.export(outDir)                                  // zip + download
```

A `files.write` operation (output/export) without `files.write` in `permissions` returns `403`. The Excel manifest declares both, so the full pipeline is authorized. See [Pitfalls](/en/plugins/pitfalls).

## The wizard UI

The UI is a controlled, stateful **four-step wizard** micro-frontend built with `FyStepWizard` and the iframe-local `@infinia/plugin-ui` Vuetify instance:

```
1. Source  ──►  2. Mode  ──►  3. Output  ──►  4. Run
   pick +         choose and       authorize an      execute,
   analyze        configure        output folder     review, download
```

- **Source:** choose an `.xlsx` or `.xls` grant with `files.open`; the step analyzes the workbook and exposes its sheets and columns.
- **Mode:** select and configure one of the three modes. `BY_SHEET` optionally selects sheets: a non-empty selection is sent as `selectedSheets`, while an empty selection omits that field so the worker expands it to every analyzed sheet. `BY_COLUMN` requires a sheet and header that still exist in the latest analysis. `COMPLEX` requires one or more complete rules: ordinary indices must be integers of 1 or greater, while copy-all rules require and send `headerIndex: -1` and `columnIndex: -1`. The optional output filename prefix also belongs to this step; the UI always sends `filePrefix`, including `""`, so clearing it resets a value stored by a previous configure call.
- **Output:** choose a fresh writable directory grant with `files.outputDirectory()`.
- **Run:** invoke `split`. Successful validation explicitly completes the wizard and shows the written-file count/list; **Download results** is a separate user action that calls `files.export`.

Analyze, configure, output-selection, and split failures stay on their current step with one wizard-owned inline error announcement. For worker responses, the UI displays `error`, then `summary`, then its operation-specific fallback, so the real JSON-RPC `{ success: false, summary }` contract remains actionable. The forward action becomes **Retry** and reruns that step's validation; duplicate forward/run actions are single-flight, and obsolete async results cannot advance the workflow. An export error remains visible on the completed result because it occurs after wizard validation.

Upstream edits invalidate dependent progress, stale downstream errors, and the previous split result. Changing Source resets Mode, Output, and Run; changing Mode or any mode-specific option resets Output and Run; changing the output grant resets Run. The real visited path is trimmed back to the changed step, so invalidated future steps are locked until their dependencies validate again. In particular, a new Source or Mode selection also clears the prior output-directory grant.

The Excel plugin—not `FyStepWizard`—persists a versioned JSON wizard snapshot and its domain draft in `sessionStorage`. On reload it validates the record shape—including the COMPLEX index/copy-all invariants—restores the draft, and re-analyzes the stored source/session before resuming. If the saved path had reached Output, it then replays `configure` with the restored mode and options; Output is enabled only after that worker state is rebuilt. A restored `BY_COLUMN` sheet or header that disappeared during re-analysis returns to Mode without calling configure. A failed or thrown configure likewise returns to Mode with an inline, retryable error. For `BY_SHEET`, an empty restored selection still omits `selectedSheets`, preserving the worker's “all sheets” behavior. A corrupt record or unavailable storage starts cleanly at Source; serialization and storage read/write/remove failures are contained, and a save/clear failure warns once without crashing the current workflow. An expired source grant returns to Source with an error. Results are never restored as completed work.

Under the current SDK contract, an output-directory permission grant cannot be safely recovered from `sessionStorage`. Reload therefore always clears Output and Run, resumes no later than Output, and requires the user to select the output directory again before Run. Completion and the subsequent zip download are both explicit actions.

It loads in the sandboxed iframe under `/plugin-runtime/fan.summer.excel/**` and bridges to the host via `@infinia/plugin-sdk`. See [UI Micro-frontend](/en/plugins/ui-microfrontend).

## Next steps

- [Manifest](/en/plugins/manifest) — the full schema, including `aiTools` and `permissions`.
- [AI Tools](/en/plugins/ai-tools) — how the six `excel_*` tools are aggregated into Spring AI `ToolCallback[]`.
- [File I/O](/en/plugins/file-io) — the grant model behind the analyze/execute/export flow.
- [Official Plugin — Markdown](/en/plugins/official-markdown) — the simpler sibling.
