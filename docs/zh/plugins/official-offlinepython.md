---
title: 官方插件 —— Offline Python
description: fan.summer.offlinepython（v4.0.0-alpha.2）详解 —— 一个 dev 类别插件，具有 files.read/files.write/network 权限，通过三栏 UI 与六个无状态 AI 工具，构建包含全部依赖的离线 Python 安装仓库（wheelhouse）。
lang: zh-CN
---

# 官方插件 —— Offline Python

`fan.summer.offlinepython` 用于构建**离线 Python 安装仓库** —— 一个自包含的目录（即 "wheelhouse"），其中存放了在气隙（air-gapped）机器上安装项目依赖所需的每一个 wheel。你配置一个项目、解析依赖、运行异步 `pip download` 构建、校验它，并可选择部署。它是结合了文件 I/O、**异步任务**与 AI 工具的典型插件示例。

## 它做什么

- 初始化一个 offline-python 项目骨架（`config.json` + `requirements.txt`）。
- 解析依赖：在 PyPI 上搜索可用的 wheel（版本 / 平台 / 大小）。
- 通过 `pip download` 以**异步任务**方式构建 wheelhouse（start → 轮询 status → cancel）。
- 依据清单校验构建输出目录（校验和、完整性、requirements）。
- 将构建好的 bundle 部署到目标，并对宿主 Python/pip 环境运行 doctor 诊断。
- 暴露六个无状态 AI 工具，使智能体流程无需 UI 即可驱动整个流水线。

## 清单

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.offlinepython",
  "name": "Offline Python Builder",
  "description": "Build offline Python install repositories with all dependencies",
  "version": "4.0.0-alpha.2",
  "author": "FengYu",
  "icon": "language-python",
  "category": "dev",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": ["files.read", "files.write", "network"],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": [ /* 六个工具，见下文 */ ]
}
```

要点：

- **`category: "dev"`** —— 一个开发者工具类插件。
- **`permissions: ["files.read", "files.write", "network"]`** —— 它读写项目文件，并在 `pip download` 期间**需要联网**访问 PyPI。正是 `network` 权限让离线*构建*（而非安装）成为可能。
- **`backend.command: "java -jar backend/worker.jar"`**，**`protocol: "json-rpc-2.0"`**。
- **`aiTools`** 有六项，因此 `supportsAi` 为 `true`。

## 方法

Worker（`OfflinePythonWorkerMain`）注册了三组 JSON-RPC 方法：

**面向 UI、按会话键控的工作流：**

| 方法 | 用途 |
| --- | --- |
| `init` | 在一个可写目录中初始化项目骨架。 |
| `config.get` / `config.save` | 读取 / 写入项目的 `config.json`。 |
| `requirements.get` / `requirements.save` | 读取 / 写入项目的 `requirements.txt`。 |
| `python.detect` | 探测宿主的 Python/pip 可执行文件与版本。 |
| `deps.latest` / `deps.search` | 解析最新版本 / 在 PyPI 搜索可用 wheel。 |
| `verify` | 依据清单校验构建输出目录。 |
| `package` | 将构建好的输出目录打包为可部署的 bundle。 |
| `doctor` | 诊断宿主 Python/pip 环境的离线构建就绪情况。 |

**异步构建 / 部署**（start → jobId → 轮询 `*.status` → `*.cancel`）：

| 方法 | 用途 |
| --- | --- |
| `build.start` / `build.status` / `build.cancel` | 异步运行 `pip download`；真实下载常常超过宿主单次 RPC 约 60 秒的限制，因此以任务方式运行。 |
| `deploy.start` / `deploy.status` / `deploy.cancel` | 异步将构建好的 bundle 部署到目标。 |

**面向 AI、无状态工具**（声明于 `manifest.aiTools[]`）：

| 工具 | 映射到 | 用途 |
| --- | --- | --- |
| `offlinepython_doctor` | `doctor` | 诊断宿主 Python/pip 的构建就绪情况。 |
| `offlinepython_search_deps` | `deps.search` | 在 PyPI 搜索某个包的 wheel。 |
| `offlinepython_init_project` | `init` | 初始化项目骨架。 |
| `offlinepython_verify` | `verify` | 依据清单校验构建。 |
| `offlinepython_build_start` | `build.start` | 启动一次异步 wheelhouse 构建 → `jobId`。 |
| `offlinepython_build_status` | `build.status` | 轮询构建任务的状态与流式日志。 |

AI 工具与 UI 驱动的是同一套服务 —— 只是可以从对话中调用。参见 [AI 工具](/zh/plugins/ai-tools) 与 [Worker（JSON-RPC）](/zh/plugins/worker)。

## 三栏 UI

UI 是一个带有三个面板的 `FyPluginShell`（而非向导）：

```
  项目（🔧）  ──►  部署（📦）  ──►  诊断（🩺）
  配置、           构建 wheelhouse       诊断宿主
  解析依赖         + 校验 + 打包         Python/pip 环境
```

- **项目** —— 选择一个可写的项目目录授权，初始化它，编辑 `config.json` / `requirements.txt`，探测 Python，并解析/搜索依赖。
- **部署** —— 运行异步 `pip download` 构建，查看流式日志，校验输出，打包并部署到目标。
- **诊断** —— 对宿主 Python/pip 环境运行诊断。

一个共享的可写 `FileRef`（由宿主授权的项目目录）在项目/部署面板中选择，并被其他面板读取 —— App 层只保留这一份共享状态。

它加载于沙箱化 iframe 中，路径为
`/plugin-runtime/fan.summer.offlinepython/**`，通过 `@infinia/plugin-sdk` 与宿主桥接。
参见 [UI 微前端](/zh/plugins/ui-microfrontend)。

## 为什么用异步任务

宿主会在大约 60 秒后杀死任何单次 RPC，但大型依赖树的真实 `pip download` 常常超过这个时间。因此构建与部署以异步**任务**方式运行：`build.start` 立即返回一个 `jobId`，UI 轮询 `build.status`（带日志游标）直到完成。`build.cancel` 可停止运行中的任务。参见 [Worker（JSON-RPC）](/zh/plugins/worker)。

## 后续步骤

- [清单](/zh/plugins/manifest) —— 完整 schema，包含 `aiTools` 与 `permissions`。
- [AI 工具](/zh/plugins/ai-tools) —— 六个 `offlinepython_*` 工具如何聚合为 Spring AI `ToolCallback[]`。
- [文件 I/O](/zh/plugins/file-io) —— 项目目录流程背后的授权模型。
- [官方插件 —— Excel](/zh/plugins/official-excel) —— 一个带有向导 UI 的同类插件。
