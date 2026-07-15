# FengYu Plugin CLI

```bash
fengyu plugin create my-plugin --id com.example.my-plugin
fengyu plugin create my-plugin --id com.example.my-plugin --no-install   # skip npm install
fengyu plugin dev my-plugin --port 4173
fengyu plugin validate my-plugin
fengyu plugin build my-plugin
fengyu plugin install my-plugin/dist-package/com.example.my-plugin-1.0.0.fyp --host http://127.0.0.1:24056
```

`create` scaffolds a Codex-style Vue 3 + Vuetify plugin: a Vite project that builds `ui/index.html`
from `src/main.ts` and `src/App.vue` on top of `@fengyu/plugin-ui`, then runs `npm install`. Pass
`--no-install` to skip installation (the scaffold is still complete; `ui/` is produced by
`npm run build`). If installation fails, the generated files are left in place and the CLI prints
the exact command to retry.

The dev command serves a sandboxed host simulator with hot reload and an RPC inspector.

Build is project-kind aware: for Vue/Vite projects it runs `npm run build` first (so `ui/index.html`
and its hashed assets exist), then validates the manifest + `ui.entry`, then packages. A frontend-build
failure rethrows the npm error as-is and never produces a `.fyp`. Legacy static plugins skip npm and go
straight to validate + package. The archive write is atomic: the zip is written to
`<output>.tmp-<pid>` and renamed onto the final path only after success, so a failure never leaves a
partial `.fyp`. The archive contains `manifest.json` and the built `ui/` (Vite output) but excludes
`src/`, `node_modules/`, `.git/`, `target/`, and prior `dist-package/` output.
