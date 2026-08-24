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
├── manifest.base.json
├── manifest/
│   ├── flow-nodes.json
│   └── i18n/
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

Worker 项目默认采用代码优先：语言契约拥有 RPC Schema，短小的 `manifest.base.json` 拥有包元数据。
`fengyu generate` 将两者合并到 `target/fengyu-manifest/manifest.json`，不要编辑该生成文件。CLI 使用 npm 的 `dev`、可选 `test`、`build` scripts，
以及 Maven 的 `test`、`package` 生命周期。Java Worker 构建必须产出唯一的
`worker/target/*-worker.jar`。Python 脚手架使用 `worker/worker.py` 与 vendored
`fengyu_plugin_sdk`；Go 脚手架使用 `worker/main.go` 与 vendored SDK module，并构建单个原生
可执行文件。打包时分别规范化为 `backend/worker.py` 与 `backend/worker[.exe]`。

通过统一命令运行 UI 模拟器：

```bash
fengyu dev
```

还需启动真实开发 Worker：Java 在 IDE 中 Debug `PluginDevMain.main()`，Python 在 `worker/`
运行 `python3 worker.py --dev`，Go 运行 `go run . --dev`。三者都通过令牌认证的回环 TCP
暴露与生产环境相同的处理器。模拟器地址为
`http://127.0.0.1:5173/__fengyu`。

检查并打包项目：

```bash
fengyu check
fengyu build
```

包与校验和写入 `dist/<id>-<version>.fyp[.sha256]`。通过宿主插件市场 UI 安装 `.fyp`。
更多契约见 [SDK 与 CLI](/zh/plugins/sdk-cli) 和[构建与部署](/zh/plugins/build-deploy)。
