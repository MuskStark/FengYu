# FengYu Plugin CLI

Toolchain 2 provides one conventional plugin workflow:

```bash
fengyu init my-plugin --id com.example.my-plugin --runtime java # or python / go
cd my-plugin
fengyu dev                       # UI simulator; debug PluginDevMain separately in the IDE
fengyu check
fengyu build                     # → dist/com.example.my-plugin-1.0.0.fyp
fengyu sign dist/com.example.my-plugin-1.0.0.fyp --key publisher.pem --key-id example-2026
```

`--runtime java|python|go` selects a conventional Worker (Python 3.12+, Go 1.26+), `--ui-only`
creates a plugin without a worker, `--no-install` skips the initial npm install,
and `build --skip-tests` skips npm/Maven tests. Installation remains a host marketplace operation.

## Standard layout

The CLI no longer reads `fengyu.plugin.json` or executes user-defined command arrays. A plugin uses
`manifest.json`, `ui-src/package.json`, and a conventional runtime project: Maven for Java,
`worker.py` for Python, or `go.mod`/`main.go` for Go. UI commands
come from the project's standard scripts (`dev`, optional `test`, `build` — npm for scaffolds,
Yarn 4 for the in-repo official plugins); workers use the Maven `test` and
`package` lifecycle through the nearest Maven Wrapper. The build must produce exactly one
`target/*-worker.jar`; Python/Go builds stage only the host-owned conventional artifact. A UI-only plugin may use a root `package.json`; a prebuilt plugin may contain
`ui/index.html`.

Packaging stages only `manifest.json`, the UI output, and the worker JAR, validates the runtime
tree, then atomically writes the `.fyp` and its `.sha256` sidecar under `dist/`.
