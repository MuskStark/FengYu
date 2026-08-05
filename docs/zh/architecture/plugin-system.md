---
title: 插件系统
description: Infinia 4.0.0 插件是自包含的 .fyp 包——一个清单、一个 UI 微前端，以及一个进程外的 JSON-RPC 2.0 worker.jar——挂载在沙箱化的 iframe 中，并由宿主的 PluginProcessManager 监管。
lang: zh-CN
---

# 插件系统

一个 Infinia 插件是一个自包含的 **`.fyp` 包**，它在不触碰宿主进程的前提下同时新增 UI 和后端能力。决定性的设计准则是**隔离**：插件的代码永远不会跑在宿主 Spring 上下文里，它的 UI 也永远不会与宿主的 DOM 树共享。

## `.fyp` 包

一个 `.fyp` 文件是一个 zip 归档，包含三部分：

| 路径 | 内容 |
| --- | --- |
| `manifest.json` | 插件元数据、权限，以及它所暴露的 AI 工具 |
| `ui/` | 微前端资产，由宿主在 `/plugin-runtime/{id}/**` 下提供 |
| `backend/worker.jar` | Worker 可执行文件，作为独立进程被拉起 |

清单字段参考见[清单](/zh/plugins/manifest)。

## 进程外 Worker

Worker 是插件的后端。它通过 stdio 使用 **JSON-RPC 2.0** 通信，并被作为**独立的操作系统进程**拉起——它永远不在宿主 Spring 上下文中存活。宿主通过 `POST /api/plugin-runtime/{id}/invoke` 以一个 `{method, params}` 请求体来调用它；宿主的 `PluginProcessManager` 会：

- 在插件的整个生命周期内拉起并**持有**该 Worker 进程，
- 把每一次 JSON-RPC 调用派发给该进程，
- 在派发之前把参数中的任何 `ref_*` FileRef 解析为绝对文件系统路径，使 Worker 拿到的是它能直接打开的真实路径。

由于 Worker 是进程外的，Worker 崩溃或挂起都无法拖垮宿主，Worker 也无法触及宿主的 bean 或 JPA 会话。

## 进程隔离后端

Worker 与 AI 编写的命令由 `ProcessSandbox` 包装，它会按平台选择一个原生隔离器。`GET /api/security/process-isolation`
端点报告当前后端，并在无可用隔离器时返回 `compatibilityMode: true`。

| 平台 | 后端 | 进程树终止 | 文件系统隔离 | 网络隔离 |
| --- | --- | --- | --- | --- |
| Linux | `bubblewrap`（`bwrap --die-with-parent --new-session`） | 内核级 | 系统文件只读，写入限制在插件/工作根目录 | 默认隔离，声明时放行 |
| macOS | `sandbox-exec`（Seatbelt profile） | tree-kill | 系统文件只读，写入限制在插件/工作根目录 | 默认隔离，声明时放行 |
| Windows | `windows-job`（Win32 Job Object，`KILL_ON_JOB_CLOSE`） | **可靠**——宿主关闭句柄或调用 `TerminateJobObject` 时 Job 杀掉整棵树 | **不强制**（已知缺口） | **不强制**（已知缺口） |
| 其他/无隔离器 | `none` | 宿主 shutdown hook + tree-kill 兜底 | 无——仅靠显式审批 | 无——仅靠显式审批 |

Windows 上的 Job Object 后端**仅做进程层**隔离：它保证 worker 及其任何后代（如 `pip` 子进程）
在宿主关闭 job 句柄时被可靠终止，弥补了长期以来 Windows 上 `ProcessHandle.descendants()` 可能
漏杀孤儿进程的缺口。它**不像** Linux 上的 `bwrap` 和 macOS 上的 `sandbox-exec` 那样约束文件系统写入
或阻断网络——这一缺口是有意为之并已记录在案；Windows 上每一次副作用仍由显式审批闸门守护。
`unsandboxedPlugins` 设置开关（在受支持处可见）允许受信任用户退出进程层隔离：关闭 = Windows 上 Job
隔离 / 其他平台原生沙箱；开启 = worker 以应用同等权限裸跑。

## 沙箱化 UI

插件的 `ui/` 微前端由宿主作为静态资产提供，位于严格的 Content Security Policy 之下的 `/plugin-runtime/{id}/**`——这些资产路径是唯一绕过令牌过滤器的插件 URL，因此 UI 能在没有凭据的情况下自举。宿主通过其[微前端宿主](/zh/architecture/frontend)加载该 UI（`import(uiEntry)` → `default.mount(el, ctx)`），并复用宿主的 Vuetify 实例以获得一致的 MD3 主题。

在 iframe 内部，SDK `@infinia/plugin-sdk` 提供一个 `FengYuClient`，它通过 `postMessage` 与宿主桥接。插件用这个 client 调用自己的 Worker（由宿主以 JSON-RPC 转发）以及请求文件访问——它永远不会直接与操作系统打交道。

## 已安装插件描述符

宿主通过 `InstalledPluginDescriptor` 暴露已安装的插件。其字段为：

| 字段 | 说明 |
| --- | --- |
| `id` | 唯一插件 id |
| `name` | 显示名 |
| `description` | 简短描述 |
| `category` | 分类 id（见分类树） |
| `icon` | 图标标识 |
| `version` | 插件版本字符串 |
| `uiEntry` | 解析后的 UI 入口 URL |
| `author` | 作者字符串 |
| `permissions` | 声明的权限（例如 `files.read`、`files.write`） |
| `enabled` | 该插件当前是否启用 |
| `iconStyle` | 硬编码为 `"BLUE"` |
| `supportsAi` | 当 `aiTools` 非空时为 `true` |
| `source` | `OFFICIAL` 或 `THIRD_PARTY` |

`GET /api/plugin-runtime` 返回已启用描述符的数组；SPA 的 `plugins` store 消费它们。

## 各部分如何连接

```
┌─────────────────────────┐        postMessage         ┌──────────────────────────┐
│  Plugin UI micro-frontend│  ◄──────────────────────►  │  Host SPA (loader.ts)    │
│  (sandboxed iframe,      │       FengYuClient          │  mounts via MF host       │
│   @infinia/plugin-sdk)    │                             └──────────┬───────────────┘
└──────────────────────────┘                                        │ HTTP (token-gated)
                                                                     ▼
                                                    ┌──────────────────────────────┐
                                                    │  Host Spring backend         │
                                                    │  PluginProcessManager        │
                                                    └──────────┬───────────────────┘
                                                               │ JSON-RPC 2.0 (stdio)
                                                               ▼
                                                    ┌──────────────────────────────┐
                                                    │  Worker process              │
                                                    │  backend/worker.jar          │
                                                    └──────────────────────────────┘
```

## 下一步

- [清单](/zh/plugins/manifest)——`manifest.json` 字段参考。
- [架构概述](/zh/architecture/overview)——插件在三层系统中的位置。
- [前端](/zh/architecture/frontend)——挂载插件 UI 的微前端宿主。
