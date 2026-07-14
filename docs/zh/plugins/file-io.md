---
title: 文件 I/O
description: 授权模型与 /api/plugin-runtime/{id}/files 下的五个 endpoint，让沙箱化的插件通过不透明的 FileRef 读写文件，临时存储在宿主关停时被清除。
lang: zh-CN
---

# 文件 I/O

插件运行在沙箱中，无法直接触碰文件系统。它通过 `FengYuClient.files.*` 请求文件访问，宿主将其转换为 `/api/plugin-runtime/{id}/files/**` 下的调用。宿主为每个被授权的路径签发一个不透明的 **FileRef**（id 为 `ref_<uuid>`），把上传快照进一个临时目录树，并仅在派发 worker RPC 的那一刻把 FileRef 改写为绝对路径。权限把守着每一个 endpoint。

## 授权模型

一个 FileRef 是一个不透明的句柄：

```ts
interface FileRef { id: string; name: string; kind: 'file'|'directory'; access: 'read'|'write'|'read-write'; size: number }
```

- `id`（例如 `ref_3f2a...`）是 UI 能看到的唯一值。
- 授权存放在一个以 id 为键、按插件 id 划分作用域的内存 `ConcurrentHashMap` 中。
- 在 RPC 派发时，宿主遍历 `params`，找出任何形如 `{id:"ref_..."}` 的值，通过授权表解析，并在发送给 worker **之前将其改写为绝对文件系统路径**。worker 收到的是真实路径，永远不会是原始上传字节，也永远不会是其他插件的 ref。

## Endpoint

所有五个 endpoint 都位于基址 `/api/plugin-runtime/{id}/files` 之下。每一个都由插件清单中声明的权限把守。

| 方法 + 路径 | Body | 权限 | 返回 |
| --- | --- | --- | --- |
| `POST /upload` | multipart `file` | `files.read` | `FileRef`（单个被上传文件，快照进 temp） |
| `POST /upload-directory` | multipart `files` + `paths[]` | `files.read` | `FileRef`（由上传的目录树在 temp 中重建的目录） |
| `POST /native` | JSON `{path, kind, access}` | `files.read` **和/或** `files.write` | `FileRef`（仅桌面端——Tauri 对话框返回一个原生路径，宿主将其包装为 ref） |
| `POST /output` | （无） | `files.write` | `FileRef`（一个新分配的可写输出目录） |
| `GET /export/{ref}` | — | `files.write` | 被授权目录的 zip，以流式下载 |

`/native` 仅在 Tauri 桌面外壳下才有意义，在那里 `ctx.desktop.pickFile` / `pickDirectory` 会给出真实的 OS 路径；在浏览器中，请改用 `/upload` 与 `/upload-directory`。你传给 `/native` 的 `access` 必须与插件实际持有的权限一致。

一个需要某项权限但插件未声明该权限的请求会返回 `403`。参见 [常见陷阱](/zh/plugins/pitfalls)。

## 临时存储与清理

上传与输出目录位于按插件划分的临时根目录下：

```
${java.io.tmpdir}/fengyu/runtime-files/<pluginId>/<uuid>/{in|out}/...
```

- 宿主把每一次上传快照进一个全新的 `<uuid>/in/` 目录树。上传树内的符号链接会被拒绝，越过快照根的路径穿越会被阻止。
- `POST /output` 分配一个全新的 `<uuid>/out/` 目录。
- 授权仅保存在内存中——它们在宿主重启后不会留存。

### 清理

- 整个 `runtime-files` 目录树会在宿主关停时由一个 `@PreDestroy` 钩子删除。
- **没有定时清扫。** 一个长时间运行的宿主会持续累积被授权的文件，直到进程退出。不要指望单个文件在会话中途被回收。

## UI 如何使用

`FengYuClient.files.*` 辅助方法封装了这些 endpoint，因此 UI 永远不会自己拼装 multipart：

```js
const file   = await fengyu.files.open({ extensions: ['xlsx'] })      // → 底层是 POST /upload
const inDir  = await fengyu.files.inputDirectory()                    // → POST /upload-directory
const outDir = await fengyu.files.outputDirectory()                   // → POST /output  （需要 files.write）
await fengyu.files.export(outDir)                                     // → GET  /export/{ref}
```

然后把 FileRef 直接传进一个 RPC——宿主会在 worker 看到它之前完成改写：

```js
const analysis = await fengyu.invoke('analyze', { filePath: file })
await fengyu.invoke('excel_execute', { outputDir: outDir, filePrefix: 'q3-' })
```

此流程的端到端讲解见 [官方插件——Excel](/zh/plugins/official-excel)。

## 下一步

- [Worker（JSON-RPC）](/zh/plugins/worker)——宿主如何在派发前改写 FileRef。
- [UI 微前端](/zh/plugins/ui-microfrontend)——`files.*` 客户端 API。
- [清单](/zh/plugins/manifest)——声明 `permissions`。
