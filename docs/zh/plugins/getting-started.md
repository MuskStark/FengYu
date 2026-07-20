---
title: 入门
description: 用 fengyu plugin create 脚手架生成一个 FengYu 插件（默认 Vue + Java），了解生成的项目结构，并通过 @infinia/plugin-dev Vite 插件 + fengyu-plugin-devkit 的 PluginDevMain 在 IDE 里本地调试它。
lang: zh
---

# 入门

本页将带你从零开始创建一个新插件，讲解脚手架生成的目录结构，以及在 IDE 里本地调试它。`fengyu plugin` CLI 共有两个子命令——`create`、`build`；本页覆盖 `create` 和 IDE 开发循环。完整的命令表见 [SDK 与 CLI](/zh/plugins/sdk-cli) 页面。

## 脚手架生成插件

用 `fengyu plugin create` 创建一个新插件。你必须传入一个反向 DNS 的 `--id`：

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin
```

默认情况下，脚手架会产出一个**完整的 Vue + Java 插件**：一个 Vue 3 + Vuetify UI 调用一个 Java JSON-RPC worker，外加 Maven Wrapper 和构建声明。它还会在 `ui-src` 内运行 `npm install`，使项目立即可运行。传入 `--no-install` 可跳过安装：

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin --no-install
```

传入 `--ui-only` 可保留轻量的纯 UI 脚手架（没有 Java worker）——适合纯前端插件：

```bash
fengyu plugin create ./my-plugin --id com.example.my-plugin --ui-only
```

脚手架会拒绝覆盖已存在的目录。人类可读的 `name` 由 `--id` 的最后一段派生（本例中为 `My Plugin`）；Java 包名与类前缀同样由 id 派生（`com.example.my_plugin` → `MyPluginWorkerMain`）。

## 快速开始

从无到有打包出 `.fyp` 的完整循环：

```bash
npx --yes @infinia/plugin-cli@1.1.0 plugin create my-plugin --id com.example.my-plugin
cd my-plugin
export FENGYU_GITHUB_TOKEN='<GitHub token with read:packages>'
# 在 IDE 里开发（见下方“在本地运行”）：
#   UI:    cd ui-src && npm run dev                 # → http://127.0.0.1:5173/__fengyu
#   Worker: Debug PluginDevMain（在 worker/src/test/java 下）
npx --yes @infinia/plugin-cli@1.1.0 plugin build .
# 构建出的 .fyp 通过宿主的插件市场 UI 安装（POST /api/plugin-market/upload）。
```

- `create` 默认安装 UI 依赖；`--no-install` 可跳过。
- worker 通过 `.mvn/settings.xml` 从 GitHub Packages 解析 Java Worker SDK（`FENGYU_GITHUB_TOKEN` 环境变量）。参见 [构建与部署](/zh/plugins/build-deploy)。
- 生成的 `App.vue` 通过宿主 RPC 桥端到端地调用 worker 的 `hello` 方法。

## 目录结构

脚手架生成后（Vue + Java），项目结构如下：

```
my-plugin/
├── manifest.json          # 运行时元数据、权限、aiTools——见 /zh/plugins/manifest
├── fengyu.plugin.json     # 构建编排（UI + worker 命令）——见 /zh/plugins/build-deploy
├── mvnw, mvnw.cmd         # Maven Wrapper（3.9.11）——构建使用的唯一 Maven
├── .mvn/
│   ├── settings.xml       # GitHub Packages 认证（环境驱动，不提交 token）
│   └── wrapper/…
├── ui-src/                # Vue/Vuetify 前端
│   ├── package.json
│   ├── vite.config.ts     # 加载 @infinia/plugin-dev；构建到 ./dist
│   └── src/{main.ts, App.vue}
└── worker/                # Java JSON-RPC worker
    ├── pom.xml            # 依赖 fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.1.0（+ devkit，test scope）
    └── src/
        ├── main/java/<pkg>/{<Prefix>Worker, <Prefix>WorkerMain}.java
        └── test/java/<pkg>/{<Prefix>WorkerTest, PluginDevMain}.java
```

`ui-src` 构建到 `ui-src/dist`，构建时会在 `.fyp` 内暂存为 `ui/`。worker 构建到 `worker/target/<prefix>-worker.jar`，暂存为 `backend/worker.jar`。

## 在本地运行

开发在 IDE 里完成——CLI 不再运行开发服务器。脚手架生成的 `vite.config.ts` 加载了
`@infinia/plugin-dev`，把 Vite dev server 变成 FengYu 宿主模拟器；脚手架生成的
`PluginDevMain`（在 `worker/src/test/java` 下）通过回环 TCP 暴露你的 worker，让 IDE 断点
直接命中。

**UI 侧**——在 `ui-src/` 下启动 Vite dev server：

```bash
cd ui-src && npm run dev
```

打开 `http://127.0.0.1:5173/__fengyu` 加载模拟器外壳。插件 UI 在 iframe 里运行，带完整 HMR；
外壳桥接 `@infinia/plugin-sdk` 的 `postMessage` 调用，并把 `rpc.invoke` 转发给 `vite.config.ts`
里配置的 worker 端点（默认 `127.0.0.1:24057`）。

**Worker 侧**——在 IDE 里用 **Debug** 运行 `PluginDevMain.main()`（不是
`<Prefix>WorkerMain`，那是生产 stdio 入口）。它启动 `fengyu-plugin-devkit` 回环 TCP 服务器
（`127.0.0.1:24057`），通过 `<Prefix>Worker.create()` 提供**与生产 worker 相同的处理器**。在
处理器里设断点——UI 调 `rpc.invoke` 时它们会直接命中。

- 两个端点都仅绑定 `127.0.0.1`。
- 在模拟器的控制按钮上切换**主题**（dark/light）与 **locale**（en/zh），验证你的 UI 是否对
  `bindFengYuEnvironment` 做出反应。
- 当插件 iframe 请求文件/目录时，模拟器会在侧边栏渲染一个路径输入框（浏览器无法弹出原生
  选择器）；你输入的路径会被注册为 FileRef，在后续传给 `rpc.invoke` 时重写为真实路径。
- 用 `-Dfengyu.dev.port=<n>` 更改 worker 端口，并同步更新 `vite.config.ts` 里的 `workerEndpoint`。

对于纯 UI 插件，`vite.config.ts` 设了 `mockWorker: true`——`rpc.invoke` 返回一个确定性的桩
响应，让你在 worker 还不存在时就能迭代 UI。完整指南见
[`plugin-dev/README.md`](https://github.com/MuskStark/FengYu/tree/main/plugin-dev)。

## 下一步

- [UI 组件](/zh/plugins/ui-components)——`@infinia/plugin-ui` 套件：外壳、文件选择器、步骤向导等。
- [清单](/zh/plugins/manifest)——每个字段、类型与默认值。
- [Worker（JSON-RPC）](/zh/plugins/worker)——编写 `backend/worker.jar`。
- [构建与部署](/zh/plugins/build-deploy)——分阶段生命周期、GitHub Packages 认证与 `.fyp` 打包。
