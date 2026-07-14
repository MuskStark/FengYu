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
  "schemaVersion": 1,
  "id": "fan.summer.excel",
  "name": "Excel Splitter",
  "description": "Split Excel workbooks by sheet, column value, or complex rules",
  "version": "4.0.0",
  "author": "FengYu",
  "icon": "file-excel",
  "category": "file",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": ["files.read", "files.write"],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": [
    {
      "name": "excel_analyze",
      "description": "Analyze an Excel file and return sheets and headers.",
      "method": "excel_analyze",
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}"
    },
    {
      "name": "excel_configure",
      "description": "Configure BY_SHEET, BY_COLUMN, or COMPLEX splitting.",
      "method": "excel_configure",
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\",\"enum\":[\"BY_SHEET\",\"BY_COLUMN\",\"COMPLEX\"]},\"sheets\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"splitSheet\":{\"type\":\"string\"},\"splitColumn\":{\"type\":\"string\"}},\"required\":[\"mode\"]}"
    },
    {
      "name": "excel_complex_config",
      "description": "Add, list, or clear complex split rules.",
      "method": "excel_complex_config",
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"add\",\"list\",\"clear\"]},\"sheetName\":{\"type\":\"string\"},\"headerIndex\":{\"type\":\"integer\"},\"columnIndex\":{\"type\":\"integer\"}},\"required\":[\"action\"]}"
    },
    {
      "name": "excel_execute",
      "description": "Execute the configured split into an authorized output directory.",
      "method": "excel_execute",
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"outputDir\":{\"type\":\"object\",\"description\":\"A writable FengYu DirectoryRef\"},\"filePrefix\":{\"type\":\"string\"}},\"required\":[\"outputDir\"]}"
    },
    {
      "name": "excel_query",
      "description": "Query the active Excel split session.",
      "method": "excel_query",
      "inputSchema": "{\"type\":\"object\",\"properties\":{}}"
    },
    {
      "name": "excel_cancel",
      "description": "Cancel and clear the active Excel split session.",
      "method": "excel_cancel",
      "inputSchema": "{\"type\":\"object\",\"properties\":{}}"
    }
  ]
}
```

Key points:

- **`category: "file"`** — a file-processing plugin.
- **`permissions: ["files.read", "files.write"]`** — it reads an uploaded input file and writes split files into an output directory (then exports a zip). Both permissions are required.
- **`aiTools`** has six entries, so `supportsAi` is `true`. Each `{name, description, method, inputSchema}` maps a model-facing tool to a worker JSON-RPC method.
- **`backend.command: "java -jar backend/worker.jar"`** with **`protocol: "json-rpc-2.0"`**.

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

`excel_configure` sets `mode` (enum `BY_SHEET | BY_COLUMN | COMPLEX`) plus mode-specific fields (`sheets`, `splitSheet`, `splitColumn`); `excel_complex_config` manages the rule list for `COMPLEX` mode with `action` enum `add | list | clear` and integer `headerIndex` / `columnIndex`.

## File I/O usage

The plugin uses the host's file-grant model end to end (see [File I/O](/en/plugins/file-io)):

1. **Analyze reads an uploaded file.** The UI calls `fengyu.files.open({ extensions: ['xlsx'] })` → `POST .../files/upload` (needs `files.read`) → a `FileRef`. It passes that ref to `analyze` / `excel_analyze`; the host rewrites the `ref_*` to an absolute path before the worker sees it.
2. **Execute writes split files into an output directory.** The UI calls `fengyu.files.outputDirectory()` → `POST .../files/output` (needs `files.write`) → a writable `DirectoryRef`, passed to `split` / `excel_execute` as `outputDir`. The worker writes the split files there.
3. **Export zips it.** `fengyu.files.export(outDir)` → `GET .../files/export/{ref}` (needs `files.write`) streams a zip of the output directory for download.

```js
const file   = await fengyu.files.open({ extensions: ['xlsx'] })   // files.read
await fengyu.invoke('analyze', { filePath: file })                 // host rewrites ref → path

const outDir = await fengyu.files.outputDirectory()                // files.write
await fengyu.invoke('split', { outputDir: outDir, filePrefix: 'q3-' })
await fengyu.files.export(outDir)                                  // zip + download
```

A `files.write` operation (output/export) without `files.write` in `permissions` returns `403`. The Excel manifest declares both, so the full pipeline is authorized. See [Pitfalls](/en/plugins/pitfalls).

## The wizard UI

The UI is a **four-step wizard** micro-frontend, built with the host's Vuetify instance:

```
1. Select file  ──►  2. Choose mode  ──►  3. Configure  ──►  4. Output
   pick .xlsx         BY_SHEET /           mode-specific       output dir +
   (files.read)       BY_COLUMN /          params              export zip
                      COMPLEX                                  (files.write)
```

- **Step 1 — Select file:** upload the input workbook (`files.open`).
- **Step 2 — Choose mode:** pick `BY_SHEET`, `BY_COLUMN`, or `COMPLEX`.
- **Step 3 — Configure:** mode-specific options (sheets, split column, or complex rules).
- **Step 4 — Output:** allocate the output directory, execute the split, and export the zip.

It loads in the sandboxed iframe under `/plugin-runtime/fan.summer.excel/**` and bridges to the host via `@fengyu/plugin-sdk`. See [UI Micro-frontend](/en/plugins/ui-microfrontend).

## Next steps

- [Manifest](/en/plugins/manifest) — the full schema, including `aiTools` and `permissions`.
- [AI Tools](/en/plugins/ai-tools) — how the six `excel_*` tools are aggregated into Spring AI `ToolCallback[]`.
- [File I/O](/en/plugins/file-io) — the grant model behind the analyze/execute/export flow.
- [Official Plugin — Markdown](/en/plugins/official-markdown) — the simpler sibling.
