---
title: 官方插件——Markdown
description: fan.summer.markdown（v4.0.0-alpha.3）讲解——一个 text 类别插件，无权限、无 AI 工具，仅暴露一个由 MarkdownWorkerMain 支撑的 render 方法，并配有一个带实时预览的 Vuetify 分栏编辑器 UI。
lang: zh-CN
---

# 官方插件——Markdown

`fan.summer.markdown` 是两个随产品发布的官方插件中较简单的一个。它是一个分栏的 Markdown 编辑器，唯一的后端能力是通过 commonmark 做服务端渲染为 HTML。它不声明任何权限，也不声明任何 AI 工具——插件契约的一个最小化、权威示例。

## 它做什么

- 渲染一个 Vuetify 分栏编辑器：左侧是 Markdown 源码，右侧是实时 HTML 预览。
- 每次编辑时，UI 调用 worker 的 `render` 方法，该方法用 commonmark 在服务端解析 Markdown 并返回 HTML。
- 渲染**在进程外的** worker 中发生——宿主自己永远不会解析 Markdown。

## 清单

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.markdown",
  "name": "Markdown Editor",
  "description": "Split-pane Markdown editor with isolated server-side rendering",
  "version": "4.0.0-alpha.3",
  "author": "FengYu",
  "icon": "language-markdown",
  "category": "text",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": [],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": []
}
```

要点：

- **`category: "text"`**——一个文本编辑/渲染插件。
- **`permissions: []`**——它永远不触碰文件系统；没有任何文件 I/O 授权。
- **`aiTools: []`**——`supportsAi` 为 `false`；它是一个纯 UI 工具，无法从聊天中调用。
- **`backend.command: "java -jar backend/worker.jar"`** 搭配 **`protocol: "json-rpc-2.0"`**——宿主启动该 shaded jar 并通过 stdio 上的 JSON-RPC 驱动它。

每个字段见 [清单](/zh/plugins/manifest)。

## `render` 动作

`MarkdownWorkerMain` 注册了**单个** JSON-RPC 方法：`render`。

```java
new JsonRpcWorker().on("render", params -> plugin.invoke("render", params)).run()
```

UI 在每次按键时调用它（做了防抖）：

```js
const { html } = await fengyu.invoke('render', { markdown: source })
```

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `render` | `{ markdown: string }` | `{ success: true, html: string }`——commonmark 渲染出的 HTML |

由于没有文件 I/O，也没有 AI 表面，这就是整个后端契约：一个方法进，一个 HTML 字符串出。协议见 [Worker（JSON-RPC）](/zh/plugins/worker)。

## UI

UI 是一个 Vuetify 分栏 MF：

- **左栏**——一个 Markdown 源码编辑器（`<v-textarea>` 或等价物）。
- **右栏**——实时 HTML 预览，每次 `render` 结果都会重绘。
- 通过共享宿主的 `PluginContext.vuetify` 使用宿主的 Vuetify 实例（MD3）构建——它**不**自带一份副本。Vue/Vuetify 去重规则见 [常见陷阱](/zh/plugins/pitfalls)。

它加载在 `/plugin-runtime/fan.summer.markdown/**` 下的沙箱化 iframe 中，并通过 `@infinia/plugin-sdk` 与宿主桥接。参见 [UI 微前端](/zh/plugins/ui-microfrontend)。

## 下一步

- [清单](/zh/plugins/manifest)——完整的 schema 参考。
- [Worker（JSON-RPC）](/zh/plugins/worker)——`render` 注册模式。
- [官方插件——Excel](/zh/plugins/official-excel)——那个更复杂的、带权限、文件 I/O 与六个 AI 工具的兄弟插件。
