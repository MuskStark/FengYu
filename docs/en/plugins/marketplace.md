---
title: Marketplace
description: The plugin marketplace serves /api/plugin-market — browse the catalog, install three ways (.fyp upload, local path, catalog id), update, enable/disable, and uninstall plugins.
lang: en
---

# Marketplace

The marketplace is the host's plugin registry. It exposes `/api/plugin-market` for browsing the catalog and managing the install lifecycle of every plugin — official and third-party alike. All lifecycle operations (install, update, enable, disable, uninstall) go through these endpoints; `POST /upload` is the install path for a built `.fyp` (used by the marketplace UI's upload button).

## Browse the catalog

`GET /api/plugin-market` returns the full catalog as `MarketplacePlugin[]` — every installed plugin with its manifest, `source` (`OFFICIAL` or `THIRD_PARTY`), `enabled` flag, and `supportsAi` badge. The marketplace UI renders this list.

## Install a plugin

There are three install paths, all under `/api/plugin-market`:

| Method + path | Body | Use when |
| --- | --- | --- |
| `POST /upload` | multipart `.fyp` file | You have a built `.fyp` archive (the normal path; the CLI uses this). |
| `POST /upload-native` | JSON `{path}` | Desktop only — install from a `.fyp` that already lives at a local filesystem path. |
| `POST /{id}/install` | — | Install a plugin already listed in the catalog by its id. |

- `POST /upload` parses the uploaded `.fyp`, extracts its `manifest.json`, validates the structure, and registers the plugin. Its `source` becomes `THIRD_PARTY`.
- `POST /{id}/install` is the one-click install for a plugin already present in the catalog index but not yet installed locally.

::: tip
Upload a built `.fyp` from the marketplace UI, or POST it directly:
`curl -F file=@./my-plugin-1.0.0.fyp -H "Authorization: Bearer $FENGYU_TOKEN" http://<host>/api/plugin-market/upload`.
:::

## Update

```
POST /api/plugin-market/{id}/update
```

Pulls the latest version of a catalog plugin and replaces the installed copy. No body required — the host resolves "latest" from the catalog.

## Enable / disable

```
PATCH /api/plugin-market/{id}/enabled
{ "enabled": true }   // or false
```

Toggles the plugin's enabled flag. **Disabling stops the worker process immediately** — the host's `PluginProcessManager` tears the OS process down and any in-flight RPC rejects. Enabling does not eagerly spawn the worker; the process is started lazily on first invoke. See [Plugin Overview](/en/plugins/overview) for the full lifecycle.

## Uninstall

```
DELETE /api/plugin-market/{id}
```

Removes the plugin entirely: stops the worker process if it is running, deletes the unpacked package, and drops the descriptor from the catalog.

## Catalog URL override

The catalog the marketplace browses is fetched from a configurable URL. Point the host at a different catalog (e.g. a private registry) with a system property:

```bash
java -Dfengyu.marketplace.catalog-url=https://internal.example/fengyu-catalog.json -jar fengyu.jar
```

## Endpoints summary

| Endpoint | Action |
| --- | --- |
| `GET /api/plugin-market` | Browse catalog → `MarketplacePlugin[]` |
| `POST /api/plugin-market/upload` | Install from uploaded `.fyp` |
| `POST /api/plugin-market/upload-native` | Install from a local path (desktop) |
| `POST /api/plugin-market/{id}/install` | Install a catalog plugin by id |
| `POST /api/plugin-market/{id}/update` | Update to latest |
| `PATCH /api/plugin-market/{id}/enabled` | Enable/disable (disabling stops the worker) |
| `DELETE /api/plugin-market/{id}` | Uninstall (stops process + removes package) |

## Next steps

- [Plugin Overview](/en/plugins/overview) — the install → enable → invoke → disable → uninstall lifecycle.
- [Build & Deploy](/en/plugins/build-deploy) — produce a `.fyp` to upload.
- [SDK & CLI](/en/plugins/sdk-cli) — the `create` + `build` commands.
