---
title: UI 微前端
description: 插件 UI 运行在严格内容安全策略下的沙箱化 iframe 中，并通过 @infinia/plugin-sdk 的 FengYuClient postMessage API 与宿主桥接——ready、invoke、files、notify、on。
lang: zh-CN
---

# UI 微前端

插件的 `ui/` 目录是一个自包含的微前端（MF），宿主把它作为静态资源通过 `/plugin-runtime/{id}/**` 提供，并加载进一个**沙箱化的 iframe**，受严格的内容安全策略（CSP）约束。在 iframe 内部，`@infinia/plugin-sdk` 包提供了一个 `FengYuClient`，通过 `postMessage` 与宿主桥接。插件永远不会直接与操作系统交互——每一个特权动作都经由 client 进行。

## 默认入口：一个 Vue/Vuetify 应用

`fengyu init` 脚手架生成的是一个构建到 `ui/` 的 Vue 3 + Vuetify 应用。生成的 `src/main.ts` 会把完整的启动与销毁生命周期交给共享 UI 套件：

```ts
import { fengyu } from '@infinia/plugin-sdk'
import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './App.vue'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
// 绑定到一个局部变量，使得收窄（非 undefined）的类型能撑过下面
// 的顶层 `await`——TS 会跨 await 把导入的 `const` 绑定重新放宽。
const client = fengyu
await mountFengYuApp({ root: App, client })
```

`mountFengYuApp` 持有完整的 UI 生命周期：只协商一次环境、同步 Vuetify 主题/locale、注入
client、挂载应用，并在 `pagehide` 时卸载、取消订阅和销毁。额外 Vue 插件可通过
`plugins: [pinia, i18n]` 传入；插件自己的消息表可在 `onEnvironment` 中同步。在组件内部应
调用 `useFengYuClient()`，而不是直接 import 单例。完整组件套件见 [UI 组件](/zh/plugins/ui-components)。

旧式静态插件——手写的 `ui/index.html` + `ui/app.js`，`import { fengyu } from './sdk.js'`——依然被接受。本页其余部分描述**两种**入口方式共用的 SDK API。

## 沙箱化 iframe + CSP

宿主加载入口 HTML 并应用一条内容安全策略，限制 MF 能做什么。你会遇到以下后果：

- **不允许内联脚本。** 所有 JS 必须来自外部的 `<script src>` 文件（脚手架写入的是 `<script type="module" src="app.js">`）。参见 [常见陷阱](/zh/plugins/pitfalls)。
- **不能直接 `fetch` 任意来源。** 通过 `FengYuClient` 与后端通信，或者使用 `PluginContext` 提供的 `apiBase` + `token` 做原始的 multipart 调用。
- **字体必须随插件打包。** `font-src 'self' data:` 同时支持当前的同源字体资源和旧版工具链内嵌的字体；远程字体来源仍被阻止。MDI 字体打包由 `@infinia/plugin-ui` 自动完成。
- `/plugin-runtime/{id}/**` 下的资源路径是唯一绕过令牌过滤的插件 URL，因此 UI 无需凭证即可启动。

## `FengYuClient` API

`FengYuClient`（以 `fengyu` 单例导出）是一个通往宿主的 `postMessage` 桥。每个方法都返回一个按 id 关联的 `Promise`；请求默认在 30s 后超时。

```ts
import { fengyu } from './sdk.js'

// 1. 协商协议 2.0.0 并接收宿主的 Environment。
const env = await fengyu.ready()
//   → { protocolVersion, theme, locale, platform, capabilities }
//   宿主协议版本不完全一致时抛出异常。

// 2. 调用插件自己的 worker（宿主以 JSON-RPC 转发）。
const out = await fengyu.invoke('render', { markdown: '# hi' })

// 3. 向用户请求文件/目录。
const file: FileRef | null    = await fengyu.files.open({ extensions: ['xlsx'] })
const inDir: FileRef | null   = await fengyu.files.inputDirectory()
const outDir: FileRef | null  = await fengyu.files.outputDirectory()
const exported: boolean       = await fengyu.files.export(outDir)  // 打 zip + 下载

// 4. 在宿主中弹出一个 toast。
await fengyu.notify('Split complete')

// 5. 订阅宿主事件。返回一个取消订阅函数。
const off = fengyu.on('environment', (e) => applyTheme(e.theme))
// ...稍后
off()
```

### 方法参考

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `ready(options?)` | `Promise<Environment>` | 协商精确的协议 `2.0.0`；并发调用共享一次握手，并缓存 `theme` + `locale`。 |
| `currentEnvironment()` | `Environment \| undefined` | 无需再次访问宿主即可读取最近一次合并后的 ready/event 状态。 |
| `invoke(method, params?, options?)` | `Promise<T>` | 对插件 worker 的 RPC。`options:{signal?, timeoutMs?}`。 |
| `notify(message)` | `Promise<boolean>` | 显示一个宿主 toast。 |
| `files.open({extensions?, filters?}, req?)` | `Promise<FileRef \| null>` | 打开单个文件。用户取消时返回 `null`。需要权限 `files.read`。 |
| `files.inputDirectory(req?)` | `Promise<FileRef \| null>` | 选取一个输入目录。需要权限 `files.read`。 |
| `files.outputDirectory(req?)` | `Promise<FileRef \| null>` | 分配一个可写的输出目录。需要权限 `files.write`。 |
| `files.export(ref, req?)` | `Promise<boolean>` | 把输出目录打 zip 并触发下载。需要权限 `files.write`。 |
| `on(event, handler)` | `() => void` | 订阅；返回一个取消订阅函数。 |
| `dispose()` | `void` | 销毁监听器并拒绝所有未完成的请求。 |

配套类型：

```ts
type Theme = 'dark' | 'light'
type FileAccess = 'read' | 'write' | 'read-write'

interface FileRef { id: string; name: string; kind: 'file'|'directory'; access: FileAccess; size: number }
interface FileFilter { name: string; extensions: string[] }
interface Environment { protocolVersion: string; theme: Theme; locale: string; platform: 'web'|'desktop'; capabilities: HostMethod[] }
```

## 最小化的 `ui/index.html`

脚手架生成的文件与之接近——一份没有内联脚本、把控制权交给 `app.js` 的入口 HTML：

```html
<!doctype html>
<html>
  <body>
    <h1>FengYu Plugin</h1>
    <button id="hello">Call host</button>
    <pre id="out"></pre>
    <script type="module" src="app.js"></script>
  </body>
</html>
```

以及配套的 `ui/app.js`：

```js
import { fengyu } from './sdk.js'

await fengyu.ready()  // 在任何其他调用之前先协商

document.querySelector('#hello').onclick = async () => {
  const result = await fengyu.invoke('hello', {})
  document.querySelector('#out').textContent = JSON.stringify(result, null, 2)
}
```

## 下一步

- [UI 组件](/zh/plugins/ui-components)——脚手架应用所用的 `@infinia/plugin-ui` Vuetify 套件。
- [文件 I/O](/zh/plugins/file-io)——每个 `files.*` 方法所需的授权。
- [SDK 与 CLI](/zh/plugins/sdk-cli)——完整的 TypeScript + Java SDK 参考。
- [常见陷阱](/zh/plugins/pitfalls)——CSP、MF Vue 去重与 FileRef 时机。
