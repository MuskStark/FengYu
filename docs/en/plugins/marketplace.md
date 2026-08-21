---
title: Marketplace
description: The plugin marketplace serves /api/plugin-market — browse the catalog, install three ways (.fyp upload, local path, catalog id), update, enable/disable, and uninstall plugins. A unified plugin store (/api/plugin-store) also aggregates Claude Code, OpenAI Codex, and Grok Build marketplaces.
lang: en
---

# Marketplace

The marketplace is the host's plugin registry. It exposes `/api/plugin-market` for browsing the catalog and managing the install lifecycle of every plugin — official and third-party alike. All lifecycle operations (install, update, enable, disable, uninstall) go through these endpoints; `POST /upload` is the install path for a built `.fyp` (used by the marketplace UI's upload button).

## Unified plugin store (Claude / Codex / Grok / FengYu)

> Since 4.0.0-alpha.7. Alongside the FengYu marketplace above, the **Stores** tab subscribes to
> third-party **Claude Code**, **OpenAI Codex**, and **Grok Build** marketplace catalogs and merges
> them into one
> browsable, source-badged grid.

- **Sources.** Add/remove/refresh marketplace sources under `/api/plugin-store/sources`. The FengYu
  source is seeded by default; Claude sources serve `.claude-plugin/marketplace.json`, Codex
  sources serve `.agents/plugins/marketplace.json`, and Grok sources serve
  `.grok-plugin/marketplace.json` (for example, the
  [official xAI catalog](https://raw.githubusercontent.com/xai-org/plugin-marketplace/main/.grok-plugin/marketplace.json)).
- **Install.** Claude/Codex/Grok plugins are installed by cloning their git source (JGit). Claude
  and Grok `url`/subdirectory sources verify a pinned sha; Codex and Grok `local` sources record
  the resolved HEAD sha in the install record so every install carries an auditable fingerprint.
- **Security.** Catalog names are slugified to a single safe path segment before they reach the
  filesystem, clone URLs are restricted to `https`/`http`/`file`, skill extraction skips symlinks,
  and catalog responses are capped at 16 MiB. Third-party catalog content is treated as untrusted.
- **Signed FengYu packages.** A FengYu catalog may publish `sha256`, Ed25519 `signature`, and
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

`GET /api/plugin-market` returns the full catalog as `MarketplacePlugin[]` — every installed plugin with its manifest, `source` (`OFFICIAL` or `THIRD_PARTY`), `enabled` flag, and `supportsAi` badge. The marketplace UI renders this list.

## Install a plugin

There are three install paths, all under `/api/plugin-market`:

| Method + path | Body | Use when |
| --- | --- | --- |
| `POST /upload` | multipart `.fyp` file | You have a built `.fyp` archive (the normal path; the CLI uses this). |
| `POST /upload-native` | JSON `{path}` | Desktop only — install from a `.fyp` that already lives at a local filesystem path. |
| `POST /{id}/install` | — | Install a plugin already listed in the catalog by its id. |

- `POST /upload` parses the uploaded `.fyp`, extracts its `manifest.json`, validates the structure, and registers the plugin. Its `source` becomes `THIRD_PARTY`. When the package's id matches an installed plugin the upload **replaces it** — the host stops the running worker (update gate) and atomically swaps the package directory; the enabled state carries over.
- `POST /{id}/install` is the one-click install for a plugin already present in the catalog index but not yet installed locally.

In the marketplace UI, every local `.fyp` pick first goes through a confirmation dialog backed by the inspect endpoints below: it shows the incoming version against the installed one (`1.0.0 → 1.1.0`), warns on a downgrade or a same-version reinstall, and only then uploads. An installed plugin's detail drawer also offers **Update from local package** as the per-plugin entry point.

::: tip
Upload a built `.fyp` from the marketplace UI, or POST it directly:
`curl -F file=@./my-plugin-1.0.0.fyp -H "Authorization: Bearer $FENGYU_TOKEN" http://<host>/api/plugin-market/upload`.
:::

## Update

```
POST /api/plugin-market/{id}/update
```

Pulls the latest version of a catalog plugin and replaces the installed copy. No body required — the host resolves "latest" from the catalog.

Updates are transactional. The old package is retained as a rollback snapshot until the new
Worker passes its reserved startup handshake; a failed spawn/handshake restores and preflights the
old package. Interrupted transactions are recovered on host startup. When the new manifest adds
permissions, pass `?confirmPermissions=true` only after showing the added permissions to the user;
otherwise the host rejects the escalation.

### Update from a local package

For a plugin that is not in any catalog (e.g. installed from a locally built `.fyp`), the catalog update above cannot resolve a download URL. Upload the new package instead — same id, new version:

```
POST /api/plugin-market/inspect       # multipart "file"; or /inspect-native {"path": "..."}
POST /api/plugin-market/upload        # replaces the installed copy after confirmation
```

`/inspect` reads the incoming manifest **without installing** and returns a `PackageInspection` — `{id, name, version, installed, installedVersion, comparison}` where `comparison` is `upgrade`, `downgrade`, `same`, or `null` for a not-yet-installed id — so a client can confirm the version step (and warn on a rollback) before the upload stops the worker and swaps the package.

## Enable / disable

```
PATCH /api/plugin-market/{id}/enabled
{ "enabled": true }   // or false
```

Toggles the plugin's enabled flag. **Disabling stops the worker process immediately** — the host's `PluginProcessManager` tears the OS process down and any in-flight RPC rejects. Enabling does not eagerly spawn the worker; the process is started lazily on first invoke. See [Plugin Overview](/en/plugins/overview) for the full lifecycle.

## Uninstall

```
DELETE /api/plugin-market/{id}?deleteData=true|false
```

The data policy is required and explicit. The marketplace UI asks twice: first whether to uninstall,
then whether to permanently delete runtime data. `deleteData=false` stops the worker and removes the
unpacked package while retaining `plugin-data/<id>` and the provisioned DB namespace/credentials for
a later reinstall. `deleteData=true` also removes those resources; if filesystem deletion cannot be
completed, the endpoint returns an error instead of reporting a false success. Database cleanup that
cannot complete is retained as `DELETE_PENDING` for retry.

## Catalog URL override

The catalog the marketplace browses is fetched from a configurable URL. Point the host at a different catalog (e.g. a private registry) with a system property:

```bash
java -Dfengyu.marketplace.catalog-url=https://internal.example/fengyu-catalog.json -jar fengyu.jar
```

## Endpoints summary

| Endpoint | Action |
| --- | --- |
| `GET /api/plugin-market` | Browse catalog → `MarketplacePlugin[]` |
| `POST /api/plugin-market/upload` | Install from uploaded `.fyp` (same id installed → update) |
| `POST /api/plugin-market/upload-native` | Install from a local path (desktop) |
| `POST /api/plugin-market/inspect` | Preview an uploaded `.fyp` → install-vs-update + version step |
| `POST /api/plugin-market/inspect-native` | Preview from a local path (desktop) |
| `POST /api/plugin-market/{id}/install` | Install a catalog plugin by id |
| `POST /api/plugin-market/{id}/update?confirmPermissions=<boolean>` | Health-gated update to latest; explicit permission escalation confirmation |
| `PATCH /api/plugin-market/{id}/enabled` | Enable/disable (disabling stops the worker) |
| `DELETE /api/plugin-market/{id}?deleteData=<boolean>` | Uninstall with explicit runtime-data retain/delete policy |

## Next steps

- [Plugin Overview](/en/plugins/overview) — the install → enable → invoke → disable → uninstall lifecycle.
- [Build & Deploy](/en/plugins/build-deploy) — produce a `.fyp` to upload.
- [SDK & CLI](/en/plugins/sdk-cli) — the `create` + `build` commands.
