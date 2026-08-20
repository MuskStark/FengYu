---
title: 入门
description: 按约定创建、开发、检查和构建 FengYu 插件。
lang: zh
---

# 入门

创建一个带 Java、Python 或 Go Worker 的 Vue 插件：

```bash
fengyu init ./my-plugin --id com.example.my-plugin --runtime java
cd my-plugin
```

使用 `--runtime python` 或 `--runtime go` 选择对应 Worker SDK；构建基线分别为 Python
3.12+ 与 Go 1.26+。使用 `--no-install` 跳过依赖安装，或用 `--ui-only` 省略 Worker。生成项目遵循
Toolchain 2 标准布局：

```text
my-plugin/
├── manifest.json
├── mvnw, mvnw.cmd
├── .mvn/
├── ui-src/
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
└── worker/
    ├── pom.xml
    ├── src/main/java/…
    └── src/test/java/…/PluginDevMain.java
```

项目不再包含旧版独立配置文件（已统一为 `manifest.json`）。CLI 使用 npm 的 `dev`、可选 `test`、`build` scripts，
以及 Maven 的 `test`、`package` 生命周期。Java Worker 构建必须产出唯一的
`worker/target/*-worker.jar`。Python 脚手架使用 `worker/worker.py` 与 vendored
`fengyu_plugin_sdk`；Go 脚手架使用 `worker/main.go` 与 vendored SDK module，并构建单个原生
可执行文件。打包时分别规范化为 `backend/worker.py` 与 `backend/worker[.exe]`。

通过统一命令运行 UI 模拟器：

```bash
fengyu dev
```

Java 插件还需在 IDE 中 Debug `PluginDevMain.main()`。它通过回环 TCP 暴露相同处理器，
因此 CLI 不必托管 Worker 进程也能命中断点。模拟器地址为
`http://127.0.0.1:5173/__fengyu`。

检查并打包项目：

```bash
fengyu check
fengyu build
```

包与校验和写入 `dist/<id>-<version>.fyp[.sha256]`。通过宿主插件市场 UI 安装 `.fyp`。
更多契约见 [SDK 与 CLI](/zh/plugins/sdk-cli) 和[构建与部署](/zh/plugins/build-deploy)。
