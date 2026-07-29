---
title: 后端
description: Infinia 4.0.0 后端是由 fan.summer.fengyu.HeadlessLauncher 启动的无头 Spring Boot 4.1.0 应用——绑定环回地址、由令牌守护，并在 SETUP 与 APP 模式之间自动切换。
lang: zh-CN
---

# 后端

Infinia 后端是一个**无头（headless）Spring Boot** 应用。它自身没有 JavaFX，也没有内置的 UI 服务器——它通过环回地址暴露一个 REST + SSE API，而由一个独立的 Vue SPA 渲染 UI。入口类是 `fan.summer.fengyu.HeadlessLauncher`。

## 技术栈

- **Spring Boot 4.1.0**
- **Spring AI 2.0.0**
- **Java 21**

## 入口与 CLI

`HeadlessLauncher` 通过 `SpringApplicationBuilder` 直接构建 Spring 上下文。它恰好接受两个 CLI 参数：

| 参数 | 默认值 | 行为 |
| --- | --- | --- |
| `--port=<n>` | `24056` | 如果端口已被占用，启动器会回退到由操作系统分配的端口。 |
| `--token=<t>` | — | 存为系统属性 `fengyu.auth.token`；客户端把它作为 `X-FengYu-Token` 头发送。 |

没有 `--mode` 标志。启动器无条件地强制 `server.address=127.0.0.1`，因此 API 只能从本机访问。

## 端口公告

内嵌服务器启动后，启动器会以固定的、机器可读的形式把所选端口打印到 stdout：

```text
FENGYU_PORT=<n>
```

桌面外壳和任何外部监管程序都通过解析这一行来发现该与哪个端口通信。`PortAnnouncer` 负责发出它。

## SETUP 与 APP 模式

启动器会自动检测该启动哪个 Spring 应用。决策依据是位于
`<运行目录>/.fengyu/config/datasource.properties` 的数据源配置文件，以及所配置的数据库当前是否可达：

```text
datasource.properties present? ──► probe DB (JDBC SELECT 1, 5s login timeout)
   │
   ├─ absent        ──► SETUP mode
   ├─ present + OK  ──► APP mode
   └─ present + unreachable ──► back up config to .bak, then SETUP mode
```

- **SETUP 模式**启动 `SetupApplication`，**不带 JPA**。它提供首次启动向导的 `/api/setup/*` 接口，并在初始化完成后以 `SETUP_DONE = 0` 退出。
- **APP 模式**启动 `FengYuApplication`，带上应用属性 `fengyu.mode=app` 以及完整的持久化 + AI + 插件技术栈。

可达性探测会执行一个普通的 JDBC `SELECT 1`，登录超时为 **5 秒**。一旦数据库不可达，启动器在回退到 SETUP 模式（以便向导收集一份修正后的配置）之前，会把既有配置备份为一个 `.bak` 同名文件。

## 退出码

| 码 | 名称 | 含义 |
| --- | --- | --- |
| `0` | `SETUP_DONE` | SETUP 模式干净地完成了初始化。 |
| `1` | `FATAL` | 不可恢复的启动失败。 |

## 鉴权

每个请求都会经过 `TokenAuthFilter`，它会把 `X-FengYu-Token` 头与通过 `--token` 提供的值进行比较。有三类路径前缀绕过该过滤器，使系统能在没有凭据的情况下完成自举：

- `/api/health`——存活探针。
- `/api/setup/*`——首次启动向导（此时令牌可能尚不存在）。
- `/plugin-runtime/{id}/**`——静态插件 UI 资产，在严格的 CSP 下提供。

所有其他 endpoint 都要求令牌匹配。

## 进程模型

后端进程是插件 Worker 的宿主，但它**不会**把插件代码加载进自己的 Spring 上下文。插件 Worker 由 `PluginProcessManager` 作为独立的、进程外的 JSON-RPC 2.0 服务器来拉起和持有。见[插件系统](/zh/architecture/plugin-system)。

## 下一步

- [架构概述](/zh/architecture/overview)——后端如何夹在 SPA 与 Electron 外壳之间。
- [桌面端](/zh/architecture/desktop)——外壳如何监管 SETUP → APP 的切换。
- [插件系统](/zh/architecture/plugin-system)——Worker 进程模型。
