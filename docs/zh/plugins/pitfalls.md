---
title: 常见陷阱
description: 插件作者最常踩的五个坑——iframe CSP、FileRef 解析时机、MF Vue/Vuetify 去重、权限把关，以及 worker stdio 帧化——每个都以问题、原因、修复的形式呈现。
lang: zh-CN
---

# 常见陷阱

这些是插件作者最常踩的五个坑。每一个都按**问题 → 原因 → 修复**的形式展开。

## 1. 内联脚本在 iframe 中不加载

**问题。** 你的插件 UI 的内联 `<script>`（或内联事件处理器）静默地无法运行；资源被阻止。

**原因。** 宿主在插件 iframe 上强制执行严格的 Content-Security-Policy。内联脚本和不被允许的资源会被 CSP 拒绝——它们永远不执行。

**修复。** 把所有 JavaScript 放进单独的文件中，通过 `<script src>` 加载（脚手架写入的是 `<script type="module" src="app.js">`），并从插件自己的 `/plugin-runtime/{id}/**` 目录树加载每一个资源。如果你确实需要内联脚本，请使用 CSP 允许的 nonce。参见 [UI 微前端](/zh/plugins/ui-microfrontend)。

## 2. Worker 收到的是路径字符串，而不是 FileRef

**问题。** 你试图在 UI 中解析一个 `ref_*` FileRef，或者硬编码一个你在授权里看到的临时路径——然后它就崩了。

**原因。** 宿主**在**派发 RPC **之前**就把 `ref_*` FileRef 改写为绝对文件系统路径。等到 worker 看到 params 时，每一个 FileRef 都已经被替换成了一个真实的路径字符串。worker 永远不会收到 FileRef 对象。同样，`${java.io.tmpdir}/fengyu/runtime-files/...` 下的临时路径是一个实现细节——授权 id 在宿主重启后不复存在，其布局也不稳定。

**修复。** 把 FileRef 从 UI 原样透传给 worker，让宿主去改写它；在 worker 中，把该值当作普通的路径字符串处理。永远不要在 UI 中解析 ref 或硬编码临时路径。参见 [文件 I/O](/zh/plugins/file-io) 与 [Worker（JSON-RPC）](/zh/plugins/worker)。

```js
// UI——把 ref 透传；不要尝试读取 .id 或自行拼路径
const file = await fengyu.files.open({ extensions: ['xlsx'] })
await fengyu.invoke('analyze', { filePath: file })   // 宿主把 ref → path
```

## 3. 插件始终无法完成宿主握手

**问题。** iframe 只渲染了一部分，但宿主 RPC 调用超时，主题或语言变化也始终没有到达。

**原因。** 插件与宿主没有使用相同的 `@infinia/plugin-sdk/protocol` 版本，或者 UI 在 `fengyu.ready()` 完成前就开始调用方法。Toolchain 2 有意要求协议版本完全匹配。

**修复。** 让 `@infinia/plugin-sdk`、`@infinia/plugin-ui`、CLI 与宿主使用同一套 toolchain release。在第一次宿主调用前直接等待 `fengyu.ready()`，或使用 `bindFengYuEnvironment`。参见 [UI 微前端](/zh/plugins/ui-microfrontend)。

## 4. 某个文件操作返回 403

**问题。** 调用某个 output 或 export 操作（`files.outputDirectory()`、`files.export(ref)`，或其底层的 `POST .../files/output`、`GET .../files/export/{ref}`）返回 `403`。

**原因。** 每一个文件 endpoint 都由清单中声明的一项权限把守。一个 `files.write` 操作在清单 `permissions` 没有 `files.write` 时会被拒绝。对于 upload/native-read 的 endpoint，`files.read` 同理。

**修复。** 声明你用到的**每一项**权限。如果你既读取一个上传的文件，又写拆分结果 + 导出一个 zip，你两者都需要：`"permissions": ["files.read", "files.write"]`。参见 [清单](/zh/plugins/manifest) 与 [文件 I/O](/zh/plugins/file-io)。

## 5. 往 stdout 打日志会破坏 RPC 流

**问题。** 你在 worker 里加了一个 `System.out.println(...)`（或一个往 stdout 写的 logger），于是宿主开始无法解析响应——RPC 调用挂起或报错。

**原因。** JSON-RPC worker 通过 **stdio 上换行分隔的 JSON** 通信。`stdout` 是协议通道：每行一条 JSON-RPC 消息。stdout 上的一行日志不是合法的 JSON-RPC，于是它让帧化失去同步。

**修复。** 只往 **stderr** 打日志。Worker SDK 会通过在运行循环期间把 `System.out` 重定向到 `System.err` 来替你强制这一点——但如果你捕获或绕过了它，请把所有诊断输出留在 `stderr` 上。参见 [Worker（JSON-RPC）](/zh/plugins/worker)。

## 下一步

- [UI 微前端](/zh/plugins/ui-microfrontend)——CSP 与 Vue/Vuetify 契约。
- [Worker（JSON-RPC）](/zh/plugins/worker)——stdio 纪律与 FileRef 解析。
- [文件 I/O](/zh/plugins/file-io)——权限模型。
