---
title: 入门
description: 按约定创建、开发、检查和构建 FengYu 插件。
lang: zh
---

# 入门

创建一个 Vue + Java Worker 插件：

```bash
fengyu init ./my-plugin --id com.example.my-plugin
cd my-plugin
```

使用 `--no-install` 跳过 npm 安装，或用 `--ui-only` 省略 Java Worker。生成项目遵循
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
以及 Maven 的 `test`、`package` 生命周期。Worker 构建必须产出唯一的
`worker/target/*-worker.jar`。

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
