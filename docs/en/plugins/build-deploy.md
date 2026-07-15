---
title: Build & Deploy
description: Produce a .fyp package — the single-plugin CLI flow (fengyu plugin validate then build), the official multi-plugin build-packages.sh script, the .fyp layout, and installing the result.
lang: en
---

# Build & Deploy

A `.fyp` is just a zip archive with a fixed layout. There are two build flows: the **single-plugin CLI flow** for third-party plugins, and the **official multi-plugin script** that assembles the three shipped official plugins. Both end with a `.fyp` you install through the [marketplace](/en/plugins/marketplace).

## The `.fyp` layout

Every `.fyp` is a zip containing exactly these entries:

```
my-plugin-1.0.0.fyp
├── manifest.json          # metadata, permissions, aiTools
├── ui/
│   ├── index.html         # entry HTML (+ any CSS/JS assets beside it)
│   └── sdk.js             # @fengyu/plugin-sdk bundle the UI imports
└── backend/
    └── worker.jar         # the shaded JSON-RPC worker executable
```

`manifest.json` declares `ui.entry` (typically `ui/index.html`) and `backend.command` (typically `java -jar backend/worker.jar`). The host serves `ui/**` under `/plugin-runtime/{id}/**` and spawns the worker from `backend.command`. See [Manifest](/en/plugins/manifest) and [Plugin Overview](/en/plugins/overview).

## Building the worker jar

Workers are shaded fat JARs produced by `maven-shade-plugin`. Set `finalName` and `mainClass` to your `*WorkerMain`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <configuration>
    <finalName>my-worker</finalName>     <!-- → my-worker.jar -->
  </configuration>
  <executions>
    <execution>
      <phase>package</phase><goals><goal>shade</goal></goals>
      <configuration>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>com.example.myplugin.MyWorkerMain</mainClass>
          </transformer>
        </transformers>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The official plugins use the same recipe: `markdown-worker` / `excel-worker` / `email-worker` as the `finalName`, with main classes `MarkdownWorkerMain` / `ExcelWorkerMain` / `EmailWorkerMain`. See [Worker (JSON-RPC)](/en/plugins/worker) for writing the worker itself.

## Single-plugin build (CLI)

For a third-party plugin, use the `fengyu plugin` CLI. First validate, then build:

```bash
# 1. Validate the project (manifest + structure)
fengyu plugin validate

# 2. Build the .fyp
fengyu plugin build --out dist-package/<id>-<version>.fyp
```

`build` runs `validate` internally and zips the project into the `.fyp` at `--out` (default `dist-package/<id>-<version>.fyp`). See [SDK & CLI](/en/plugins/sdk-cli) for the full command reference.

## Official multi-plugin build

The three shipped plugins are assembled by `OfficialPlugins/build-packages.sh`. It does, in order:

1. **Builds the worker JARs** for all three plugin modules (`plugin-markdown`, `plugin-excel`, `plugin-email`).
2. **Builds the TypeScript SDK** (`plugin-sdk/typescript`) and copies the bundle into the markdown and excel `ui/sdk.js`.
3. **Builds the Email UI** (`plugin-email/ui-src`) from source and runs the **Excel UI tests** + **validates the POI services** and the Email worker's manifest main class.
4. **Assembles** `packages/{markdown,excel,email}/` for each plugin, laying out:
   - `manifest.json`
   - `ui/` (entry HTML + assets, including `ui/sdk.js` for markdown/excel)
   - `backend/worker.jar` (the shaded jar from the matching `-worker` module)
5. **Zips** each assembled tree to `target/packages/fan.summer.{markdown,excel,email}-4.0.0.fyp`, then runs post-build checks (e.g. the email entry HTML must declare UTF-8 and contain no icon font).

The resulting `.fyp` files are what the `OfficialPluginSeeder` installs into a fresh host. Their manifests carry `"official": true`, so the descriptor `source` is `OFFICIAL`.

## Install the result

Push the built `.fyp` into a running host with the CLI (which wraps `POST /api/plugin-market/upload`):

```bash
fengyu plugin install ./dist-package/com.example.my-plugin-1.0.0.fyp \
  --host http://127.0.0.1:24056 --token "$FENGYU_TOKEN"
```

Or upload the file directly through the marketplace UI. Either way, see [Marketplace](/en/plugins/marketplace) for the install, update, enable/disable, and uninstall endpoints.

## Next steps

- [SDK & CLI](/en/plugins/sdk-cli) — `validate`, `build`, and `install` reference.
- [Worker (JSON-RPC)](/en/plugins/worker) — what goes into `worker.jar`.
- [Marketplace](/en/plugins/marketplace) — installing the `.fyp` you built.
