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

### 云账号登录

上述启动令牌用于保护本地宿主 API；它与可选的 Infinia Store 身份彼此独立，后者只用于
发起已鉴权的 Store 出站调用。SPA 通过本地 `/api/account/*` endpoint 发起登录；无头的
`CloudAccountService` 创建 OAuth 2.1 Authorization Code + PKCE 尝试并返回 authorization
URL，再由 renderer 在系统浏览器中打开。宿主在一次性随机 loopback 端口（RFC 8252 §7.3）
接收 code、换取令牌，并通过 `GET /api/v1/me` 解析 Store 用户资料。浏览器唤起不得依赖
Java AWT：无头测试环境与部分打包环境通常会让 `Desktop.isDesktopSupported()` 返回 false。

桌面授权请求必须包含 `openid profile offline_access`，Store 的 `fengyu-desktop` 注册客户端
也必须允许同一组 scope，并启用 authorization-code 与 refresh grant。这是两端互操作的不变量：
缺少 `offline_access` 时首次登录仍能成功，但不会获得 refresh token；30 分钟 access token
到期后，已鉴权的 Store 调用会退回匿名访问。Store 仓库中的
`AuthAndAccountFlowTest.fengYuDesktopPkceGrantCanRefreshAndCallMe` 覆盖完整的
PKCE → `/me` → refresh → `/me` 契约。

access token 仅存内存（刷新串行化，服务端轮换的 refresh token 恰好持久化一次），refresh
token 仅存操作系统凭据库——macOS Keychain、Windows Credential Manager 或 Linux Secret
Service；数据库只保留身份绑定行（Flyway V2 已删除遗留的令牌列）。登录绝不会改变本地虚拟
用户对聊天、Flow 或插件数据的所有权；登出会先尽力撤销 refresh token，再删除云账号绑定。

用户中心页面经由同一 access token 读取实时 Store 数据：`/api/account/store-profile`、
`/profile`、`/password`、`/library`、`/organizations`、`/sessions`、`/devices` 代理已登录
用户的 Store 资源（见 [REST API——账号](/zh/reference/rest-api#账号)），未登录时返回 401。
除显示名称改名会同步绑定行（让快速的 `/api/account/me` 视图随之更新）外，本地不持久化
任何数据。

Store 基址逐请求经 `StoreEndpointProvider` 解析：设置中的升级渠道（`updateApiBase`）优先生效——
生产环境商店与主程序分开部署，插件安装/更新、云账号登录与用户中心全部经由该渠道通信、
无需重启——`FENGYU_STORE_API_BASE`（默认 `http://localhost:8080`）只是启动兜底。每次解析都会
重新执行 SSRF 策略：除非显式设置 `fengyu.store.allow-private-network`，渠道不得指向内网、
传输必须为 HTTPS。该开关为自建内网/异地商店而设——放行内网目标及其 plain HTTP（此类部署
通常没有 CA 签发证书）；公网 plain HTTP 仍然拒绝。该姿态是实时设置：设置 → 更新通道 →
「允许私有网络」（或启动参数）在下一次商店调用立即生效，策略受阻的基址在启动时只警告、
不再导致启动失败。异地商店自身也必须把 `store.base-url` 配置为外部可达地址，因为浏览器
登录重定向指向该地址。
桌面 OAuth 客户端以公开客户端形态发布（RFC 8252 §8.5）：仅 PKCE、不携带共享密钥——打包进
分发版桌面构建的密钥等同于公开信息，无法让客户端变为机密。若 Store 部署仍把
`fengyu-desktop` 注册为机密客户端，对接方通过 `FENGYU_STORE_CLIENT_SECRET`
（`fengyu.store.client-secret`）显式选择加入；此时令牌与吊销请求以 `client_secret_post`
携带该密钥，叠加在始终强制要求的 PKCE verifier 之上。公开形态的长期登录属于 Store 侧机制
（每安装实例凭据或 BFF），而非随包分发的密钥。若商店返回 `invalid_client`，登录错误信息会
附带指向该配置项的提示。

## 进程模型

后端进程是插件 Worker 的宿主，但它**不会**把插件代码加载进自己的 Spring 上下文。插件 Worker 由 `PluginProcessManager` 作为独立的、进程外的 JSON-RPC 2.0 服务器来拉起和持有。见[插件系统](/zh/architecture/plugin-system)。

## 下一步

- [架构概述](/zh/architecture/overview)——后端如何夹在 SPA 与 Electron 外壳之间。
- [桌面端](/zh/architecture/desktop)——外壳如何监管 SETUP → APP 的切换。
- [插件系统](/zh/architecture/plugin-system)——Worker 进程模型。
