# FengYu Plugin CLI

Toolchain 2 provides one conventional plugin workflow:

```bash
fengyu init my-plugin --id com.example.my-plugin
cd my-plugin
fengyu dev                       # UI simulator; debug PluginDevMain separately in the IDE
fengyu check
fengyu build                     # → dist/com.example.my-plugin-1.0.0.fyp
```

`--ui-only` creates a plugin without a Java worker, `--no-install` skips the initial npm install,
and `build --skip-tests` skips npm/Maven tests. Installation remains a host marketplace operation.

## Standard layout

The CLI no longer reads `fengyu.plugin.json` or executes user-defined command arrays. A Java plugin
uses `manifest.json`, `ui-src/package.json`, and `worker/pom.xml` (or a root `pom.xml`). UI commands
come from the project's standard scripts (`dev`, optional `test`, `build` — npm for scaffolds,
Yarn 4 for the in-repo official plugins); workers use the Maven `test` and
`package` lifecycle through the nearest Maven Wrapper. The build must produce exactly one
`target/*-worker.jar`. A UI-only plugin may use a root `package.json`; a prebuilt plugin may contain
`ui/index.html`.

Packaging stages only `manifest.json`, the UI output, and the worker JAR, validates the runtime
tree, then atomically writes the `.fyp` and its `.sha256` sidecar under `dist/`.
