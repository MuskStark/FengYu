# @infinia/plugin-dev

Vite host simulator used by the Toolchain 2 `fengyu dev` command. Generated plugin projects wire
`fengyuPluginDev()` into `ui-src/vite.config.ts`; the CLI runs their standard `npm run dev` script.

```ts
fengyuPluginDev({
  manifest: '../manifest.json',
  workerEndpoint: { host: '127.0.0.1', port: 24057 },
})
```

Debug the generated `PluginDevMain.main()` separately in the IDE. The simulator forwards
`rpc.invoke` to that loopback DevKit endpoint while preserving IDE breakpoints. UI-only plugins may
set `mockWorker: true`; a configured but unavailable Worker always returns an error.

Open `http://127.0.0.1:5173/__fengyu` for theme/locale controls, RPC inspection, FileRef-backed
file and directory selection, writable workspaces, and output export. The simulator consumes the
same `@infinia/plugin-sdk/protocol` contract as the production host.
