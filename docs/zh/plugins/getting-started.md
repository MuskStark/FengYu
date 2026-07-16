---
title: 入门
description: 用 fengyu plugin create 脚手架生成一个 FengYu 插件（默认 Vue + Java），了解生成的项目结构，并用真实 worker 开发模拟器在本地运行它。
lang: zh
---

# 入门

本页将带你从零开始创建一个新插件，讲解脚手架生成的目录结构，以及在本地运行它。`fengyu plugin` CLI 共有五个子命令——`create`、`dev`、`build`、`validate`、`install`——本页覆盖前两个。完整的命令表见 [SDK 与 CLI](/zh/plugins/sdk-cli) 页面。

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
npx --yes @infinia/plugin-cli@1.0.0 plugin create my-plugin --id com.example.my-plugin
cd my-plugin
export FENGYU_GITHUB_TOKEN='<GitHub token with read:packages>'
npx --yes @infinia/plugin-cli@1.0.0 plugin dev .
npx --yes @infinia/plugin-cli@1.0.0 plugin build .
npx --yes @infinia/plugin-cli@1.0.0 plugin install dist-package/com.example.my-plugin-1.0.0.fyp --host http://127.0.0.1:24056
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
│   ├── vite.config.ts     # 构建到 ./dist
│   └── src/{main.ts, App.vue}
└── worker/                # Java JSON-RPC worker
    ├── pom.xml            # 依赖 fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.0.0
    └── src/main/java/<pkg>/<Prefix>WorkerMain.java
```

`ui-src` 构建到 `ui-src/dist`，构建时会在 `.fyp` 内暂存为 `ui/`。worker 构建到 `worker/target/<prefix>-worker.jar`，暂存为 `backend/worker.jar`。

## 在本地运行

`fengyu plugin dev` 会探测声明式项目，若 worker JAR 缺失则先构建它，启动**真实的** Java JSON-RPC worker，并提供一个回环模拟器，其 `rpc.invoke` 调用会通过 `POST /__rpc` 转发给 worker：

```bash
fengyu plugin dev .
```

- 开发宿主仅绑定 `127.0.0.1`。
- 打开打印出的 URL（`http://127.0.0.1:4173/__fengyu`）会加载 RPC 检查器外壳。
- 对 `worker/` 下的 Java 源码（不含 `target/`）的编辑会触发一个防抖重建 + worker 重启；重建期间，RPC 调用会返回 `worker rebuilding`。
- 在检查器的控制按钮上切换**主题**（dark/light）与 **locale**（en/zh），以验证你的 UI 是否对 `bindFengYuEnvironment` 做出反应。
- 默认端口是 `4173`；传入 `--port` 可更改。

对于纯 UI 和静态项目，模拟器保留之前的 mock 行为（没有 worker）。

## 下一步

- [UI 组件](/zh/plugins/ui-components)——`@infinia/plugin-ui` 套件：外壳、文件选择器、步骤向导等。
- [清单](/zh/plugins/manifest)——每个字段、类型与默认值。
- [Worker（JSON-RPC）](/zh/plugins/worker)——编写 `backend/worker.jar`。
- [构建与部署](/zh/plugins/build-deploy)——分阶段生命周期、GitHub Packages 认证与 `.fyp` 打包。
