---
title: 架构概述
description: Infinia 4.0.0 是一个三层系统——一个无头 Spring Boot 后端、一个 Vue 3 SPA，以及一个 Tauri 2.0 桌面外壳——绑定到环回地址 127.0.0.1，并由每次启动时生成的令牌进行守护。
lang: zh-CN
---

# 架构概述

Infinia 4.0.0 是一个**三层系统**：一个无头 Spring Boot 后端、一个 Vue 3 单页应用，以及一个掌控进程生命周期的 Tauri 2.0 桌面外壳。同一套 Vue UI 既可以在浏览器标签页中运行，也可以在 Tauri 窗口里运行——外壳改变的只是后端的启动方式和 UI 的服务方式。

## 三层结构

```
┌─────────────────────────────────────────────────────────────────┐
│  Tauri 2.0 desktop shell  (desktop/, Rust)                      │
│  • spawns / owns the Java sidecar process                       │
│  • injects window.__FENGYU_TOKEN__ | __FENGYU_PORT__ |          │
│    __FENGYU_API_BASE__  before page load                        │
│  • serves the built SPA in a system WebView                     │
│  • kills the sidecar when the window is destroyed               │
└───────────────┬───────────────────────────────┬─────────────────┘
                │ spawns (release)               │ loads HTML/JS
                ▼                                │
┌──────────────────────────────────┐             │
│  Headless Spring Boot backend    │             │
│  fan.summer.fengyu.HeadlessLauncher             │
│  • binds 127.0.0.1:24056         │             │
│  • token-gated REST + SSE        │             │
│  • spawns plugin worker processes│             │
│  • Spring Boot 4.1.0 + Spring AI │             │
└───────────────┬──────────────────┘             │
                │ HTTP (loopback only)            │
                ▼                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  Vue 3 SPA  (frontend/, TypeScript)                             │
│  Pinia + vue-router 4 + vue-i18n 10, Vuetify 3 (MD3)            │
│  • talks to the backend over the loopback HTTP API              │
│  • loads plugin UI micro-frontends via the MF host              │
└─────────────────────────────────────────────────────────────────┘
```

## 请求流

每一个来自 UI 的请求都走同一条环回路径：

1. SPA（在浏览器中或 Tauri WebView 中）向后端发起一个 HTTP 请求。
2. 该请求指向 `127.0.0.1:<port>`，其中 `<port>` 是后端在启动时以 `FENGYU_PORT=<n>` 打印出来的值（默认 `24056`）。
3. 后端的 `TokenAuthFilter` 检查 `X-FengYu-Token` 头。不携带匹配令牌的请求会被拒绝——有三类豁免：`/api/health`、`/api/setup/*`，以及 `/plugin-runtime/{id}/**` 下的静态插件 UI 资产。
4. 通过鉴权的请求到达控制器，后者可能会通过 JSON-RPC 2.0 把工作派发给进程外的插件 Worker。

## 仅绑定环回地址

后端强制 `server.address=127.0.0.1`。它不会监听任何外部网络接口——API 只能从运行该进程的同一台机器访问。这是首要的网络安全边界：没有任何方式能从另一台主机访问该 API。

## 每次启动的令牌鉴权

每一次后端启动都由单个令牌进行鉴权：

- 启动器接受 `--token=<t>` CLI 参数，并把它存为系统属性 `fengyu.auth.token`。
- 每个受保护的请求都必须携带 `X-FengYu-Token: <t>`。
- 该令牌在每次启动时重新生成；不存在持久化的凭据。桌面外壳会在页面加载前把它以 `window.__FENGYU_TOKEN__` 的形式注入到 SPA 中。

环回绑定与每次启动的令牌相结合，使得即使进程正在运行，API 也只对本地用户可见。

## 各层职责

| 层 | 负责内容 |
| --- | --- |
| [后端](/zh/architecture/backend) | REST/SSE 接口、持久化、AI 后端、插件 Worker 生命周期、鉴权 |
| [前端](/zh/architecture/frontend) | Vue 3 SPA、Pinia store、插件 UI 挂载、初始化向导路由 |
| [桌面端](/zh/architecture/desktop) | Sidecar 的拉起/健康检查/初始化编排、bridge 注入、窗口生命周期 |
| [插件系统](/zh/architecture/plugin-system) | `.fyp` 包契约、进程外 Worker、沙箱化 UI |

## 下一步

- [后端](/zh/architecture/backend)——`HeadlessLauncher`、SETUP/APP 模式与令牌过滤器。
- [桌面端](/zh/architecture/desktop)——Tauri 外壳如何拉起并监管后端。
- [快速开始](/zh/quickstart)——从源码构建并运行这三层。
