---
title: UI 微前端
description: 插件 UI 运行在严格内容安全策略下的沙箱化 iframe 中，并通过 @fengyu/plugin-sdk 的 FengYuClient postMessage API 与宿主桥接——ready、invoke、files、notify、on。
lang: zh-CN
---

# UI 微前端

插件的 `ui/` 目录是一个自包含的微前端（MF），宿主把它作为静态资源通过 `/plugin-runtime/{id}/**` 提供，并加载进一个**沙箱化的 iframe**，受严格的内容安全策略（CSP）约束。在 iframe 内部，`@fengyu/plugin-sdk` 包提供了一个 `FengYuClient`，通过 `postMessage` 与宿主桥接。插件永远不会直接与操作系统交互——每一个特权动作都经由 client 进行。

## 沙箱化 iframe + CSP

宿主加载入口 HTML 并应用一条内容安全策略，限制 MF 能做什么。你会遇到以下后果：

- **不允许内联脚本。** 所有 JS 必须来自外部的 `<script src>` 文件（脚手架写入的是 `<script type="module" src="app.js">`）。参见 [常见陷阱](/zh/plugins/pitfalls)。
- **不能直接 `fetch` 任意来源。** 通过 `FengYuClient` 与后端通信，或者使用 `PluginContext` 提供的 `apiBase` + `token` 做原始的 multipart 调用。
- `/plugin-runtime/{id}/**` 下的资源路径是唯一绕过令牌过滤的插件 URL，因此 UI 无需凭证即可启动。

## `FengYuClient` API

`FengYuClient`（以 `fengyu` 单例导出）是一个通往宿主的 `postMessage` 桥。每个方法都返回一个按 id 关联的 `Promise`；请求默认在 30s 后超时。

```ts
import { fengyu } from './sdk.js'

// 1. 协商 SDK 版本并接收宿主的 Environment。
const env = await fengyu.ready()
//   → { sdkVersion, theme:'dark'|'light', locale, platform?, capabilities? }
//   在插件 SDK 与宿主主版本不匹配时抛出异常。

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
| `ready()` | `Promise<Environment>` | 发送 `sdkVersion`；主版本不匹配时抛出异常。把 `theme` + `locale` 应用到文档。 |
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
interface Environment { sdkVersion?: string; theme: Theme; locale: string; platform?: 'web'|'desktop'; capabilities?: string[] }
```

## 共享宿主集成：`default.mount(el, ctx)`

除了 iframe 路径之外，宿主的 MF 加载器还可以直接加载一个插件 ESM bundle 并调用其 default 导出：

```ts
// 插件 bundle 为共享宿主挂载而 default 导出的内容。
export default {
  mount(el: HTMLElement, ctx: PluginContext): () => void { /* ... */ return () => {} }
}
```

宿主传入的 `PluginContext` 让 MF 能访问共享基础设施，从而不必各自打包一份：

| 字段 | 用途 |
| --- | --- |
| `ctx.vuetify` | 宿主的 Vuetify（MD3）实例——调用 `app.use(ctx.vuetify)` 以让插件共享主题与组件。**不要打包 Vuetify。** |
| `ctx.theme`, `ctx.onThemeChange(cb)` | 当前主题 + 变更订阅。 |
| `ctx.locale`, `ctx.t(key)`, `ctx.onLocaleChange(cb)` | 宿主 locale、一个翻译器以及变更订阅。插件**不得**自带语言切换器。 |
| `ctx.api.invoke(action, args?)` | 通往 worker 的便捷 RPC。 |
| `ctx.notify(msg)` | 显示一个宿主 toast。 |
| `ctx.apiBase`, `ctx.token` | 后端基址 URL 与用于原始 `fetch` 的 `X-FengYu-Token`。 |
| `ctx.desktop?` | 原生 Tauri 文件对话框（`pickFile`、`pickDirectory`）——**仅**在 Tauri 下存在，浏览器中为 `undefined`。 |

locale 契约见 [国际化](/zh/plugins/i18n)，Vue/Vuetify 去重规则见 [常见陷阱](/zh/plugins/pitfalls)。

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

- [文件 I/O](/zh/plugins/file-io)——每个 `files.*` 方法所需的授权。
- [SDK 与 CLI](/zh/plugins/sdk-cli)——完整的 TypeScript + Java SDK 参考。
- [常见陷阱](/zh/plugins/pitfalls)——CSP、MF Vue 去重与 FileRef 时机。
