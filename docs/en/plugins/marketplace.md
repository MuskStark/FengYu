---
title: Marketplace
description: The plugin marketplace serves /api/plugin-packages for the local .fyp lifecycle — install (.fyp upload, local path), inspect, enable/disable, and uninstall plugins. Catalog browsing and install/update by id live under the unified plugin store (/api/plugin-store), backed entirely by the official Infinia Store.
lang: en
---

# Marketplace

The marketplace is the host's plugin registry. Since 4.0.0-rc.1 it serves the local `.fyp` lifecycle under `/api/plugin-packages` — install (upload), inspect, enable, disable, and uninstall for every plugin, official and third-party alike; `POST /upload` is the install path for a built `.fyp` (used by the marketplace UI's upload button). Catalog browsing and install/update by id moved to the unified plugin store under `/api/plugin-store`. A deprecated `/api/plugin-market` compat layer still forwards the lifecycle endpoints 1:1 (with `Deprecation` headers); its old catalog endpoints answer `410 Gone` naming their `/api/plugin-store` replacements.

## Official store integration

> The client-side Claude Code / OpenAI Codex / Grok Build marketplace adapters were retired:
> the official Infinia Store aggregates third-party content server-side, and the host pulls
> **everything** — plugins, skills, MCP servers, cloud-account sign-in — from that one store
> (`fengyu.store.api-base`, production `https://www.infinia.fyi`).

- **Whole catalog.** The default FengYu source is seeded on every start and browses the
  official store's full catalog (PLUGIN + SKILL + MCP; 1300+ listings) through the shared
  store client — pagination at the store's 100-row cap, bounded at 30 pages, with a
  5-minute browse cache that any install/uninstall invalidates. The store page's front tab
  renders this catalog with type filters and the cloud-account sign-in entry; entries carry
  a store coordinate and install through the store transaction pipeline (dependency resolve
  → signed download ticket → apply → ledger → commit) — the same audited channel as the
  skills market and `/api/store`.
- **Sources.** `/api/plugin-store/sources` still lists and refreshes sources, but FENGYU is
  the only subscribable type; adding any other type answers 400.
- **Legacy self-hosted catalog.** `fengyu.marketplace.catalog-url` opts the default source
  into a self-hosted JSON-array catalog (direct `.fyp` download URLs) instead of the
  official store:

  ```bash
  java -Dfengyu.marketplace.catalog-url=https://internal.example/fengyu-catalog.json -jar fengyu.jar
  ```

- **Signed FengYu packages.** A legacy catalog may publish `sha256`, Ed25519 `signature`, and
  `keyId`. The host downloads once, verifies those exact bytes, checks publisher namespace and
  package/key revocation against bundled plus user trust roots, then installs the same file.
  Configure user roots in `<runtime-root>/trusted-plugin-publishers.json` (by default
  `<working-directory>/.fengyu/...`); create catalog signature
  metadata with `fengyu sign`.
- **Windows unsandboxed toggle.** On platforms without a native process sandbox, a Settings row
  (gated behind a confirmation dialog, defaulting off) lets you opt plugin workers into the
  `unrestricted()` channel. See the changelog for the alpha.7 security hardening.

## Official plugins

Infinia ships with a set of official plugins — real capabilities the Agent can orchestrate out of the box. Each has its own page:

| Plugin | What it does | Docs |
| --- | --- | --- |
| **Excel Splitter** | Split workbooks by sheet, column value, or complex rules — with six AI tools. | [Excel Splitter →](/en/plugins/official-excel) |
| **Email Center** | Multi-account SMTP/IMAP, contact management, batch sending, archives — nine confirmation-first AI tools. | [Email Center →](/en/plugins/email-center) |
| **Offline Python Builder** | Build offline Python install repositories (wheelhouses) with all dependencies — six AI tools and async builds. | [Offline Python →](/en/plugins/official-offlinepython) |
| **Markdown Editor** | Split-pane editor with isolated server-side rendering. | [Markdown Editor →](/en/plugins/official-markdown) |

## Browse the catalog

Since 4.0.0-rc.1, catalog browsing happens under `/api/plugin-store` — the unified view rendered by the UI's **Store** page. The FengYu source lists the official store's whole catalog (plugins, skills, MCP servers), merged with local install state. The deprecated `GET /api/plugin-market` alias answers `410 Gone`, naming `/api/plugin-store/catalog` as its replacement.

## Install a plugin

There are three install paths — two local ones under `/api/plugin-packages`, one from the store catalog under `/api/plugin-store`:

| Method + path | Body | Use when |
| --- | --- | --- |
| `POST /api/plugin-packages/upload` | multipart `.fyp` file | You have a built `.fyp` archive (the normal path; the CLI uses this). |
| `POST /api/plugin-packages/upload-native` | JSON `{path}` | Desktop only — install from a `.fyp` that already lives at a local filesystem path. |
| `POST /api/plugin-store/{uid}/install` | — | Install a plugin already listed in the store catalog by its uid. |

- `POST /api/plugin-packages/upload` parses the uploaded `.fyp`, extracts its `manifest.json`, validates the structure, and registers the plugin. Its `source` becomes `THIRD_PARTY`. When the package's id matches an installed plugin the upload **replaces it** — the host stops the running worker (update gate) and atomically swaps the package directory; the enabled state carries over.
- `POST /api/plugin-store/{uid}/install` is the one-click install for a plugin already present in the store catalog but not yet installed locally. The deprecated `POST /api/plugin-market/{id}/install` answers `410 Gone` naming this replacement.

In the marketplace UI, every local `.fyp` pick first goes through a confirmation dialog backed by the inspect endpoints below: it shows the incoming version against the installed one (`1.0.0 → 1.1.0`), warns on a downgrade or a same-version reinstall, and only then uploads. The catalog, inspect, and install responses all carry `permissionsOsEnforced` — when it is `false` (every platform except the Linux sandbox today), the confirmation states that the plugin's declared permissions are **not enforced by the operating system** on this platform. An installed plugin's detail drawer also offers **Update from local package** as the per-plugin entry point.

::: tip
Upload a built `.fyp` from the marketplace UI, or POST it directly:
`curl -F file=@./my-plugin-1.0.0.fyp -H "Authorization: Bearer $FENGYU_TOKEN" http://<host>/api/plugin-packages/upload`.
:::

## Update

```
POST /api/plugin-store/{uid}/update
```

Pulls the latest version of a store-catalog plugin and replaces the installed copy. No body required — the host resolves "latest" from the source catalog. The deprecated `POST /api/plugin-market/{id}/update` answers `410 Gone` naming this replacement.

Updates are transactional. The old package is retained as a rollback snapshot until the new
Worker passes its reserved startup handshake; a failed spawn/handshake restores and preflights the
old package. Interrupted transactions are recovered on host startup. When the new manifest adds
permissions, pass `?confirmPermissions=true` only after showing the added permissions to the user;
otherwise the host rejects the escalation.

### Update from a local package

For a plugin that is not in any catalog (e.g. installed from a locally built `.fyp`), the catalog update above cannot resolve a download URL. Upload the new package instead — same id, new version:

```
POST /api/plugin-packages/inspect       # multipart "file"; or /inspect-native {"path": "..."}
POST /api/plugin-packages/upload        # replaces the installed copy after confirmation
```

`/inspect` reads the incoming manifest **without installing** and returns a `PackageInspection` — `{id, name, version, installed, installedVersion, comparison}` where `comparison` is `upgrade`, `downgrade`, `same`, or `null` for a not-yet-installed id — so a client can confirm the version step (and warn on a rollback) before the upload stops the worker and swaps the package.

## Enable / disable

```
PATCH /api/plugin-packages/{id}/enabled
{ "enabled": true }   // or false
```

Toggles the plugin's enabled flag. **Disabling stops the worker process immediately** — the host's `PluginProcessManager` tears the OS process down and any in-flight RPC rejects. Enabling does not eagerly spawn the worker; the process is started lazily on first invoke. See [Plugin Overview](/en/plugins/overview) for the full lifecycle.

## Uninstall

```
DELETE /api/plugin-packages/{id}?deleteData=true|false
```

The data policy is required and explicit. The marketplace UI asks twice: first whether to uninstall,
then whether to permanently delete runtime data. `deleteData=false` stops the worker and removes the
unpacked package while retaining `plugin-data/<id>` and the provisioned DB namespace/credentials for
a later reinstall. `deleteData=true` also removes those resources; if filesystem deletion cannot be
completed, the endpoint returns an error instead of reporting a false success. Database cleanup that
cannot complete is retained as `DELETE_PENDING` for retry.

## Endpoints summary

| Endpoint | Action |
| --- | --- |
| `GET /api/plugin-store/catalog` | Browse the unified store catalog → source-badged plugin grid |
| `POST /api/plugin-packages/upload` | Install from uploaded `.fyp` (same id installed → update) |
| `POST /api/plugin-packages/upload-native` | Install from a local path (desktop) |
| `POST /api/plugin-packages/inspect` | Preview an uploaded `.fyp` → install-vs-update + version step |
| `POST /api/plugin-packages/inspect-native` | Preview from a local path (desktop) |
| `POST /api/plugin-store/{uid}/install` | Install a store-catalog plugin by uid |
| `POST /api/plugin-store/{uid}/update?confirmPermissions=<boolean>` | Health-gated update to latest; explicit permission escalation confirmation |
| `PATCH /api/plugin-packages/{id}/enabled` | Enable/disable (disabling stops the worker) |
| `DELETE /api/plugin-packages/{id}?deleteData=<boolean>` | Uninstall with explicit runtime-data retain/delete policy |

The deprecated `/api/plugin-market` compat layer forwards the lifecycle rows above 1:1 (with `Deprecation` headers); its catalog rows — `GET /api/plugin-market`, `POST /api/plugin-market/{id}/install`, `POST /api/plugin-market/{id}/update` — answer `410 Gone` naming their `/api/plugin-store` replacements.

## Store download trust

The native Infinia Store client bundles the production `platform-ed25519-2026`
public key for `https://store.summer.fan`. Configure that HTTPS address as the
store API base in Settings. Downloads still require both SHA-256 integrity and
an Ed25519 signature over the downloaded bytes; an unknown or revoked key fails
closed.

Operators can add public keys or revoke a bundled key in
`<runtime-root>/trusted-store-keys.json`, then restart the backend. Obtain keys
through an authenticated deployment channel, such as the Jenkins public trust
artifact, and verify its fingerprint before installing it. Never copy the
store's private `.b64` key into a client. To revoke the bundled key:

```json
{"keys": [], "revokedKeys": ["platform-ed25519-2026"]}
```

Store platform trust is separate from `trusted-plugin-publishers.json`, which
governs third-party catalog publishers.

## Next steps

- [Plugin Overview](/en/plugins/overview) — the install → enable → invoke → disable → uninstall lifecycle.
- [Build & Deploy](/en/plugins/build-deploy) — produce a `.fyp` to upload.
- [SDK & CLI](/en/plugins/sdk-cli) — the `create` + `build` commands.
