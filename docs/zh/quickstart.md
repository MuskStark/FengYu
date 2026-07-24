---
title: 快速开始
description: 从源码构建并运行 Infinia 4.0.0。
lang: zh-CN
---

# 快速开始

几分钟内从源码跑起 Infinia 4.0.0 —— 一个 AI 原生的流程编排平台。整个 reactor 由两个 Maven 模块组成——按顺序构建，然后分别启动后端与前端。

## 前置条件

| 工具 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 21+（推荐 Eclipse Temurin） | 后端（`Java 21`） |
| Node.js + npm | 20+ | 前端开发服务器 |
| Node.js + npm | 24.17+ | 仅桌面端外壳需要（只用 Web 可跳过） |

## 从源码构建

构建是一个两模块的 reactor。`FengYu-Api` **必须先 install**，因为 `FengYu` 依赖于它。

```bash
git clone https://github.com/MuskStark/FengYu.git
cd FengYu
mvn install -f FengYu-Api/pom.xml -DskipTests
mvn clean package -f FengYu/pom.xml -DskipTests
```

打包好的后端 jar 位于 `FengYu/target/FengYu-4.0.0-alpha.2.jar`。

## 运行后端

启动无头（headless）Spring Boot 后端。它默认绑定 `127.0.0.1:24056`，并在启动时打印 `FENGYU_PORT=<n>`。

```bash
java -jar FengYu/target/FengYu-4.0.0-alpha.2.jar --token=<your-token>
```

入口类是 `fan.summer.fengyu.HeadlessLauncher`。CLI 参数只有 `--port` 和 `--token` 两个。

## 运行前端（开发模式）

Vue 3 + Vuetify 3 前端通过 Vite 连接后端，Vite 会把 `/api` 和 `/plugin-runtime` 代理到 `localhost:24056`。

```bash
cd frontend
npm install
npm run dev
```

打开打印出的本地 URL，UI 就会与你刚才启动的后端通信。

## 冒烟测试

一个辅助脚本会启动打包好的 jar，并探测每一个 endpoint。

```bash
scripts/e2e-smoke.sh
```

每次构建后想做一次快速的端到端健康检查时都可以运行它。

## 运行桌面端（开发模式）

Electron 桌面外壳会以 sidecar 方式拉起 Java 后端。在仓库根目录下：

```bash
cd desktop/electron
npm install
npm run dev       # 将 FENGYU_JAR 指向一个已构建的 shaded jar，或在外部于 :24056 上运行后端
```

构建可分发包：

```bash
npm run build     # = npm run build:ts && electron-builder（当前平台）
```

::: tip
桌面外壳自带 Chromium（无需系统 WebView）。你只需 Java 来运行后端。暂存 JAR / 插件以及带/不带 JRE 的两种构建变体，请参见 [desktop README](https://github.com/MuskStark/FengYu/blob/4.0.0-FengYu/desktop/README.md)。
:::

## 发布（Alpha）

发布标签（`v4.0.0-alpha.2`，以及后续的 stable/beta/rc）会触发一条 GitHub Actions 流水线，发布**未签名**的 Electron 安装包（Windows/macOS/Linux）和一个**可移植的 Web 分发包**。Web 压缩包直接从文件夹运行同一套后端 + 内嵌的 Vue SPA：

```bash
# 解压 Infinia-<version>-web.zip 后：
./run.sh          # macOS/Linux（Windows 用 run.bat）
```

需要 **Java 21**（或使用内嵌 JRE 的 Electron 构建版本）。后端仅绑定**回环地址**（`127.0.0.1`）。代码签名将留待后续版本实现；Electron 自动更新器随 Alpha 一起发布（GitHub Releases，未签名）。

## 下一步

- [架构概述](/zh/architecture/overview)——无头后端、Vue UI 与 Electron 外壳如何拼装在一起。
- [配置](/zh/guide/configuration)——端口、令牌、数据库选择与 AI 后端。
