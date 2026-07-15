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

The dev command serves a sandboxed host simulator with hot reload and an RPC inspector. Build uses
a cross-platform ZIP writer and runs the same manifest checks as `validate` before producing `.fyp`.
