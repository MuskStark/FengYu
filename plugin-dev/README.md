# @infinia/plugin-dev

The Vite dev plugin for [FengYu](https://github.com/MuskStark/FengYu) plugin authors. It turns the
Vite dev server into a FengYu host **simulator**, so you develop and debug a plugin's UI and worker
**directly in your IDE** — no separate dev CLI process, no JDWP remote attach.

> Replaces the former `fengyu plugin dev` command. The CLI now only scaffolds (`create`) and
> packages (`build`); development happens entirely through your editor + this Vite plugin.

## What it does

- **UI (this plugin)** — injects `/__fengyu`, `/__fengyu/rpc`, `/__fengyu/ref` middleware into Vite.
  `/__fengyu` serves an iframe shell that runs your real plugin UI (with Vite HMR) and bridges
  `@infinia/plugin-sdk`'s `postMessage` calls.
- **Worker (separate process, your IDE)** — forward `rpc.invoke` to a `fengyu-plugin-devkit` dev
  server you run via `PluginDevMain.main()`. Breakpoints in your handlers fire directly.

## Install

In your plugin's `ui-src/`:

```bash
npm install --save-dev @infinia/plugin-dev
```

For a Java-worker plugin, also add the devkit as a **test-scope** Maven dependency in `worker/pom.xml`:

```xml
<dependency>
  <groupId>fan.summer.fengyu.sdk</groupId>
  <artifactId>fengyu-plugin-devkit</artifactId>
  <version>1.1.0</version>
  <scope>test</scope>
</dependency>
```

## Usage

`ui-src/vite.config.ts`:

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fengyuPluginDev } from '@infinia/plugin-dev'

export default defineConfig({
  base: './',
  plugins: [
    vue(),
    fengyuPluginDev({
      manifest: '../manifest.json',
      workerEndpoint: { host: '127.0.0.1', port: 24057 },
    }),
  ],
})
```

Then:

```bash
cd ui-src && npm run dev     # UI: http://127.0.0.1:5173/__fengyu
```

For the Java worker, run `PluginDevMain.main()` (scaffolded into `worker/src/test/java/...`) from
your IDE's **Debug** action — it starts the loopback TCP server at `127.0.0.1:24057`. Set
breakpoints in your `JsonRpcWorker` handlers; they fire when the UI calls `rpc.invoke`.

### UI-only plugins

Drop `workerEndpoint` (or set `mockWorker: true`) — `rpc.invoke` returns a deterministic stub so
you can iterate the UI before the worker exists:

```ts
fengyuPluginDev({ manifest: './manifest.json', mockWorker: true })
```

## License

GPL-3.0
