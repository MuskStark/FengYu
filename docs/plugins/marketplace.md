# Plugin Marketplace

FengYu installs third-party extensions as `.fyp` packages. A package is a ZIP archive whose root
contains `manifest.json`; packages are expanded into `${user.home}/.fengyu/plugins/<plugin-id>/`.
Override that directory with `fengyu.plugins.directory` when embedding or testing the backend.

## Package manifest

```json
{
  "schemaVersion": 1,
  "id": "com.example.demo",
  "name": "Demo",
  "description": "A demonstration plugin",
  "version": "1.0.0",
  "author": "Example",
  "icon": "puzzle-outline",
  "category": "dev",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": ["files.read"],
  "homepage": "https://example.com/demo",
  "official": false
}
```

The installer rejects packages over 100 MB, expanded content over 300 MB, unsafe archive paths,
unknown manifest schemas, invalid reverse-domain IDs, invalid semantic versions, and missing UI
entries. Updates use a same-filesystem staging directory and atomic replacement; a failed update
restores the previous package. Enablement is host state stored as a `.disabled` marker and cannot be
supplied by the archive.

## Runtime isolation

The host discovers enabled manifests through `GET /api/plugin-runtime`. It serves the declared UI
entry under `/plugin-runtime/<id>/` and renders it in an iframe without `allow-same-origin`. Plugin
assets receive a restrictive CSP; the page cannot access the host DOM, authentication token,
Pinia, Vue, Vuetify, Tauri, or arbitrary network endpoints.

An optional backend is started with the package directory as its working directory. Requests and
responses are one JSON-RPC 2.0 object per UTF-8 line on stdin/stdout. Calls time out after 60
seconds; disabling, uninstalling, crashing, or timing out terminates the worker.

`aiTools` entries in the manifest declare tool name, description, JSON Schema, and worker method.
The host converts them to Spring AI `ToolCallback` instances while keeping execution in the worker.
The migrated Excel package exposes its six existing analyze/configure/complex/execute/query/cancel
tools through this mechanism.

## Host bridge and files

Plugin pages send capability requests with `postMessage`. Supported capabilities are
`rpc.invoke`, `host.ready`, `notify`, `files.open`, `files.outputDirectory`, and `files.export`.
File capabilities require `files.read` or `files.write` in the manifest.

Both Web and desktop return the same opaque object:

```json
{"id":"ref_...","name":"book.xlsx","kind":"file","access":"read","size":1024}
```

On Web, the host uploads the selected file into its temporary grant store. On desktop, Tauri opens
the native dialog and the backend registers the chosen canonical path. The iframe sees only the
reference. Before a worker call, the host recursively resolves authorized references to paths.
Browser output is zipped for download; desktop output writes to the user-selected directory.

Use the published TypeScript and Java SDKs instead of implementing this protocol manually. The
`fengyu plugin` CLI scaffolds, previews, validates, builds, and installs packages; see
[Plugin SDK and CLI](sdk-cli.md).

## Marketplace catalog

Set `fengyu.marketplace.catalog-url` to an HTTP(S) JSON document containing an array:

```json
[
  {
    "id": "com.example.demo",
    "name": "Demo",
    "description": "A demonstration plugin",
    "version": "1.1.0",
    "author": "Example",
    "icon": "puzzle-outline",
    "category": "dev",
    "permissions": ["files.read"],
    "homepage": "https://example.com/demo",
    "downloadUrl": "https://example.com/releases/demo-1.1.0.fyp",
    "official": false
  }
]
```

The UI compares catalog versions with installed manifests and exposes install or update actions.
Local packages use the same validation path through `POST /api/plugin-market/upload`.

## Lifecycle API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/api/plugin-market` | Catalog merged with local state |
| `POST` | `/api/plugin-market/upload` | Install/update a multipart `.fyp` |
| `POST` | `/api/plugin-market/{id}/install` | Download and install from the catalog |
| `POST` | `/api/plugin-market/{id}/update` | Download and atomically replace |
| `PATCH` | `/api/plugin-market/{id}/enabled` | Enable or disable locally |
| `DELETE` | `/api/plugin-market/{id}` | Uninstall and remove package data |
| `GET` | `/api/plugin-runtime` | Discover enabled installed plugins |
| `POST` | `/api/plugin-runtime/{id}/invoke` | Invoke the isolated JSON-RPC worker |
