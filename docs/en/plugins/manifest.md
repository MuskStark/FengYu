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
| `schemaVersion` | number | yes | — | Manifest schema version. Currently `1`. |
| `id` | string | yes | — | Reverse-DNS plugin id, e.g. `fan.summer.excel`. Must be unique across installed plugins. |
| `name` | string | yes | — | Human-readable display name. |
| `description` | string | yes | — | One-line description shown in the marketplace and plugin list. |
| `version` | string | yes | — | SemVer-style version string, e.g. `4.0.0`. |
| `author` | string | yes | — | Author or organization name. |
| `icon` | string | yes | — | Icon identifier (a Vuetify/Material design icon name, e.g. `file-excel`). |
| `category` | string | yes | — | One of the [valid category values](#valid-category-values). |
| `ui` | object | yes | — | UI sub-record. See [`ui`](#ui). |
| `backend` | object | no | — | Worker sub-record. See [`backend`](#backend). **Optional** — omit it for supported UI-only plugins. |
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
| `command` | string | yes | Shell command the host uses to spawn the worker, e.g. `java -jar backend/worker.jar`. |
| `protocol` | string | yes | Wire protocol. Currently `json-rpc-2.0`. |

### `aiTools[]`

Each entry declares one AI-callable tool that the host aggregates into its Spring AI `ToolCallback[]`.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `name` | string | yes | Tool name surfaced to the model. |
| `description` | string | yes | Natural-language description for the model. |
| `method` | string | yes | Worker JSON-RPC method to invoke when the model calls this tool. |
| `inputSchema` | string | yes | JSON Schema describing the tool's arguments, serialized as a **string**. |

See [AI Tools](/en/plugins/ai-tools) for the end-to-end flow.

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
| `database` | The host injects a datasource connection (`FENGYU_DB_*` + a private data directory) into the worker environment. See [Plugin Database Standard](/en/plugins/database). |

Any other value is rejected as an unknown permission at both validate and install time. A file operation attempted without the matching permission is rejected with `403`. See [File I/O](/en/plugins/file-io).

## Examples

### Markdown plugin

The `fan.summer.markdown` manifest — a text plugin with no permissions and no AI tools:

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.markdown",
  "name": "Markdown Editor",
  "description": "Split-pane Markdown editor with isolated server-side rendering",
  "version": "4.0.0-alpha.1",
  "author": "FengYu",
  "icon": "language-markdown",
  "category": "text",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": [],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": []
}
```

### Excel plugin (with aiTools)

The `fan.summer.excel` manifest — a file plugin with read/write permissions and six AI tools. Only the first tool is shown in full; the rest follow the same `{name, description, method, inputSchema}` shape:

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.excel",
  "name": "Excel Splitter",
  "description": "Split Excel workbooks by sheet, column value, or complex rules",
  "version": "4.0.0-alpha.1",
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
    }
  ]
}
```

> `inputSchema` is a **JSON Schema serialized as a string** — note the escaped quotes. The host parses it to build the Spring AI `ToolDefinition`.

## Next steps

- [Worker (JSON-RPC)](/en/plugins/worker) — implement the `backend.command` target.
- [AI Tools](/en/plugins/ai-tools) — declare and expose `aiTools`.
- [File I/O](/en/plugins/file-io) — what each `permissions` entry unlocks.
