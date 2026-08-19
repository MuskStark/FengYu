---
title: Manifest
description: Full reference for manifest.json — the schemaVersion, id, name, ui/backend sub-records, permissions, category values, and aiTools declarations that define a FengYu plugin.
lang: en
---

# Manifest

`manifest.json` is the single source of truth for a plugin. The host parses it at install time to learn the plugin's identity, how to launch its worker, what UI to mount, what it is allowed to do, and which AI tools it exposes. It lives at the root of the `.fyp` archive.

## Schema reference

| Field | Type | Required | Default | Notes |
| --- | --- | --- | --- | --- |
| `schemaVersion` | number | yes | — | Manifest schema version. Currently `2`. |
| `id` | string | yes | — | Reverse-DNS plugin id, e.g. `fan.summer.excel`. Must be unique across installed plugins. |
| `name` | string | yes | — | Human-readable display name. |
| `description` | string | yes | — | One-line description shown in the marketplace and plugin list. |
| `version` | string | yes | — | SemVer-style version string, e.g. `4.0.0`. |
| `author` | string | yes | — | Author or organization name. |
| `icon` | string | yes | — | Icon identifier (a Vuetify/Material design icon name, e.g. `file-excel`). |
| `category` | string | yes | — | One of the [valid category values](#valid-category-values). |
| `ui` | object | yes | — | UI sub-record. See [`ui`](#ui). |
| `backend` | object | no | — | Worker sub-record. See [`backend`](#backend). **Optional** — omit it for supported UI-only plugins. |
| `rpc` | object | no | — | Worker JSON-RPC method declarations with typed input/output schemas. See [`rpc.methods`](#rpc-methods). |
| `permissions` | string[] | no | `[]` | Declared [permissions](#valid-permissions). Drives file-I/O authorization. |
| `homepage` | string | no | — | URL to the plugin's homepage or source repository. |
| `official` | boolean | no | `false` | `true` for plugins seeded by `OfficialPluginSeeder`; sets descriptor `source = OFFICIAL`. |
| `aiTools` | object[] | no | `[]` | Declared [AI tools](/en/plugins/ai-tools). Empty array means `supportsAi = false`. |

### `ui`

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `entry` | string | yes | Archive-relative path to the entry HTML, typically `ui/index.html`. Served at `/plugin-runtime/{id}/<entry>`. |

### `backend`

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `callTimeoutSeconds` | integer | no | Plugin-wide default per-call timeout in seconds. Clamped to `[1, 600]`. When omitted, the host uses `60`. |

Toolchain 2 dropped the v1 `command` and `protocol` fields: the host derives the worker launch from the standard `backend/worker.jar` layout and speaks JSON-RPC 2.0 over stdio unconditionally.

### `rpc.methods`

Each entry declares one worker JSON-RPC method with its typed input/output JSON Schemas. In Toolchain 2 the schemas are JSON-Schema **objects** (not escaped strings) and live here rather than on `aiTools`.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `description` | string | no | Human-readable summary of the method. |
| `inputSchema` | object | no | JSON-Schema **object** describing the method's arguments. Path/FileRef inputs are typed as `string` because the host resolves FileRefs to absolute paths before the worker sees them. |
| `outputSchema` | object | no | JSON-Schema **object** describing the worker's result envelope. Most results follow `{ success: boolean, summary: string, … }`. |

### `aiTools[]`

Each entry declares one AI-callable tool that the host aggregates into its Spring AI `ToolCallback[]`. An `aiTool` references an [`rpc.methods`](#rpc-methods) entry by `method` and carries no schema of its own — the input/output schemas live on the method it points at.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `name` | string | yes | Tool name surfaced to the model. |
| `method` | string | yes | The `rpc.methods` key the host invokes when the model calls this tool. |
| `effect` | string | yes | Approval classification: `read`, `write`, or `external`. |
| `description` | string | yes | Natural-language description for the model. |

A method that may exceed `backend.callTimeoutSeconds` **must be split into `*_start` / `*_status` / `*_cancel` job methods** — see [Worker → Long tasks (job mode)](/en/plugins/worker#long-tasks-job-mode).

See [AI Tools](/en/plugins/ai-tools) for the end-to-end flow.

### `flowNodes[]`

Explicit flow-canvas declarations for the Flows builder. A tool with a `flowNodes` entry renders
as a first-class canvas node — typed ports, examples, help text, custom widgets; without one it
still appears behind the palette's "show all tools" toggle as a schema-derived fallback node.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `tool` | string | yes | The `aiTools[].name` this node renders and executes. |
| `label` | string | no | Card label (defaults to the humanized tool name). |
| `kind` | string | no | `action` (default), `control`, or `start` — reserved for canvas-authored structural nodes. |
| `help` | string | no | Node-level help shown in the inspector's help drawer (plain text / Markdown-ish). |
| `docsUrl` | string | no | External documentation link. |
| `color` | string | no | Hex color for the card. |
| `icon` | string | no | MDI icon name for the badge. |
| `inputs[]` | array | no | Declared inputs; see below. |
| `outputs[]` | array | no | Named output ports; see below. |

Each **input** carries `name` + `widget` (`text` / `number` / `switch` / `select` / `textarea` /
`json` / `analyze` / `rows`) plus optional `title`, `description`, `help` (field-level hint),
`type` (flow data type: `string` / `number` / `boolean` / `object` / `array` / `file` / `any` —
drives the variable picker's type filter; omitted = `any`), `required`, `placeholder`,
`examples[]`, `advanced` (fold into Advanced settings), `default`, `options[]` (for `select`),
`source` (options loaded from a plugin list RPC), `context` (analyze-style edit-time feeds), and
`fields[]` (per-row fields for the `rows` widget).

Each **output** carries `name`, `title`, `type` (colors the port and filters the picker),
`description` / `help`, `examples[]` (shown until a real run provides data), and — for object or
array outputs — a recursive `properties` map / `items` descriptor so the variable tree can offer
nested paths like `confirmation.confirmationId` or `files[0]`.

The full vocabulary is defined in
[`toolchain/spec/manifest.schema.json`](https://github.com/MaskStark/FengYu/blob/4.0.0/toolchain/spec/manifest.schema.json)
(definitions `flowNode`, `flowNodeInput`, `flowNodeOutput`, `flowOutputProperty`); the host's
`flow-nodes/builtin.json` validates against
[`flow-node.schema.json`](https://github.com/MaskStark/FengYu/blob/4.0.0/toolchain/spec/flow-node.schema.json).

## Valid category values

`category` is a free-form advisory string the UI uses to group plugins — the host does **not** validate it against a fixed set (it upper-cases whatever you write, defaulting to `OTHER` when blank). Use one of these conventional values for consistency:

| Value | Use for |
| --- | --- |
| `dev` | Developer tooling |
| `text` | Text editing / rendering (e.g. `fan.summer.markdown`) |
| `image` | Image processing |
| `net` | Networking |
| `network` | Networking (e.g. `fan.summer.email`) |
| `file` | File processing (e.g. `fan.summer.excel`) |
| `ai` | AI-centric plugins |
| `other` | Anything not covered above (the scaffolder's default) |

## Valid permissions

`permissions` is an array containing zero or more of the canonical set enforced by both the CLI and the host:

| Value | Authorizes |
| --- | --- |
| `files.read` | `POST /api/plugin-runtime/{id}/files/upload`, `upload-directory`, `native` (read access) |
| `files.write` | `POST .../files/native` (write access), `POST .../files/output`, `GET .../files/export/{ref}` |
| `network` | General outbound network access from the worker. |
| `network.email` | The worker may open SMTP/IMAP connections (used by `fan.summer.email`). |
| `clipboard.read` | Read from the host clipboard. |
| `clipboard.write` | Write to the host clipboard. |
| `notifications` | Show host notifications / toasts. |
| `database` | The host injects database connection coordinates (`FENGYU_DB_*` — type/driver/url/username/password — plus a private data directory) into the worker environment, provisioned as an isolated DB user/schema. The worker opens its own connection. See [Plugin Database Standard](/en/plugins/database). |

Any other value is rejected as an unknown permission at both validate and install time. A file operation attempted without the matching permission is rejected with `403`. See [File I/O](/en/plugins/file-io).

> **Enforcement is not uniform (P1-9).** Do not assume every accepted token is enforced to the same
> degree:
> - **Enforced by the host/OS sandbox:** `files.read`, `files.write` (FileRef grant gate), `network`
>   (OS network namespace).
> - **Host-bridge gated (plugin `notify`):** `notifications`. The host bridge reads this at
>   runtime — a plugin that declared it gets real unified host notifications from its `notify`
>   calls (in-app toast + native desktop notification + the persisted notification center);
>   undeclared calls fall back to `@infinia/plugin-ui`'s iframe-internal notification center.
> - **Treated as full network egress (advisory at the network layer):** `network.email` and `database`
>   currently grant broad outbound network access — the host does not yet broker SMTP/IMAP or
>   restrict DB connections to a specific host. A real mail/DB proxy is a tracked follow-up.
> - **Advisory only (no host enforcement yet):** `clipboard.read`, `clipboard.write`
>   document intent for a future capability bridge to the desktop shell; nothing reads them at
>   runtime today.
>
> Surface these honestly in any UI that summarizes a plugin's permissions — do not imply finer
> network isolation than the OS actually enforces for `network.email` / `database`.

## Examples

### Markdown plugin

The `fan.summer.markdown` manifest — a text plugin with no permissions and no AI tools:

```json
{
  "schemaVersion": 2,
  "id": "fan.summer.markdown",
  "name": "Markdown Editor",
  "description": "Split-pane Markdown editor with isolated server-side rendering",
  "version": "4.0.0",
  "author": "FengYu",
  "icon": "language-markdown",
  "category": "text",
  "ui": { "entry": "ui/index.html" },
  "backend": { "callTimeoutSeconds": 30 },
  "permissions": [],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "rpc": {
    "methods": {
      "render": {
        "description": "Render Markdown source to sanitized HTML via commonmark (server-side).",
        "inputSchema": {
          "type": "object",
          "properties": {
            "markdown": { "type": "string", "description": "The Markdown source to render." }
          },
          "required": ["markdown"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "success": { "type": "boolean" },
            "summary": { "type": "string" },
            "html": { "type": "string", "nullable": true, "description": "The rendered, sanitized HTML." }
          },
          "required": ["success", "summary"]
        }
      }
    }
  },
  "aiTools": []
}
```

### Excel plugin (with aiTools)

The `fan.summer.excel` manifest — a file plugin with read/write permissions and AI tools. Two methods are declared in full under `rpc.methods`: `excel_analyze` (a short synchronous call) and `excel_execute_start` (the launcher half of a job-mode pair for long-running splits). Each `aiTools` entry references one by `method` and adds an `effect`; the rest follow the same shape:

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
          "properties": {
            "success": { "type": "boolean" },
            "summary": { "type": "string" }
          },
          "required": ["success", "summary"]
        }
      },
      "excel_execute_start": {
        "description": "Launch the configured split as a background job for large workbooks and return a jobId immediately. Poll excel_execute_status with a cursor to drain progress logs.",
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
          "properties": {
            "success": { "type": "boolean" },
            "summary": { "type": "string" },
            "jobId": { "type": "string" }
          },
          "required": ["success", "summary"]
        }
      }
    }
  },
  "aiTools": [
    { "name": "excel_analyze", "method": "excel_analyze", "effect": "read", "description": "Analyze an Excel file and return sheets and headers." },
    { "name": "excel_execute_start", "method": "excel_execute_start", "effect": "write", "description": "Launch the configured split as a background job for large workbooks and return a jobId immediately. Poll excel_execute_status with a cursor to drain progress logs." }
  ]
}
```

> `inputSchema` and `outputSchema` are JSON-Schema **objects** (not strings). The host reads `inputSchema` to build the Spring AI `ToolDefinition` handed to the model.

## Next steps

- [Worker (JSON-RPC)](/en/plugins/worker) — implement the `rpc.methods` handlers.
- [AI Tools](/en/plugins/ai-tools) — declare and expose `aiTools`.
- [File I/O](/en/plugins/file-io) — what each `permissions` entry unlocks.
