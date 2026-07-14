---
title: 故障排查
description: Infinia 4.0.0 的常见问题及其修复——端口冲突、数据库连接失败、令牌不匹配、插件 worker 崩溃，以及微前端加载错误——每项都以「症状、原因、修复」的形式呈现。
lang: zh-CN
---

# 故障排查

用户与插件作者最常碰到的五个问题。每个都按**症状 → 原因 → 修复**展开。

## 24056 端口冲突

**症状。** 后端无法绑定 `24056`（已被其他进程占用），或者两个实例发生碰撞。

**原因。** `--port` 默认为 `24056`。当该端口已被占用时，启动器会回退到由操作系统分配的端口，并在 stdout 上以 `FENGYU_PORT=<n>` 公告它——但一个硬编码了 `24056` 的客户端此时就会与错误的进程（或什么都联系不上）通信。

**修复。**

- 从启动器的 stdout `FENGYU_PORT=` 行读取实际端口。桌面外壳以及任何外部监管程序都靠解析这一行来发现端口——照此办理即可。
- 若要彻底放弃固定端口，请以 `--port=0` 启动，并始终读取已公告的 `FENGYU_PORT`。
- 释放被卡住的端口：找到持有它的进程（在 macOS/Linux 上用 `lsof -i :24056`），在重新启动前停掉它。

参见 [后端——端口公告](/zh/architecture/backend#port-announcement)。

## 数据库连接失败

**症状。** 后端虽然启动，却即便在 `datasource.properties` 已存在的情况下仍回退进首次启动向导；或者 `POST /api/setup/initialize` 报告数据库不可达。

**原因。** 启动时，启动器会用一个 JDBC `SELECT 1`（5 秒登录超时）探测所配置的数据库。如果探测失败，它会先把既有配置备份为一个 `.bak` 同名文件，再重新进入 SETUP 模式，以便你提供修正后的参数。

**修复。**

1. 重跑向导：`GET /api/setup/status`、`GET /api/setup/types`，然后带上 `{type, params}` 调用 `POST /api/setup/test-connection` 以**不持久化**地校验。
2. 一旦测试通过，调用 `POST /api/setup/initialize` 持久化并重启进入 APP 模式。
3. 你之前的配置是安全的——请在活动配置旁边寻找 `datasource.properties.bak`。
4. 对于外部数据库（`MYSQL`、`POSTGRESQL`），确认服务器可达、凭据正确、且 JDBC URL 指向正确的主机。对于嵌入式后端（`H2`、`SQLITE`），确认文件路径可写。

参见 [数据库——不可达的数据库](/zh/guide/database#unreachable-database) 与 [后端——SETUP 与 APP 模式](/zh/architecture/backend#setup-vs-app-mode)。

## 令牌不匹配（到处 401 / 403）

**症状。** 每个经过鉴权的请求都返回 `401` 或 `403`，但 `/api/health` 正常。

**原因。** 客户端发送的 `X-FengYu-Token` 头与启动器通过 `--token` 收到的值不匹配。令牌绕过清单（`/api/health`、`/api/setup/*`、`/plugin-runtime/{id}/**`）仍然工作，这就是为什么健康探针仍有响应。

**修复。**

- 确认启动器启动时所用的令牌：它被存为系统属性 `fengyu.auth.token`，由 `--token=<t>` 推导而来。
- 在每个请求上原样发送同一值作为 `X-FengYu-Token` 头——包括 SSE 流（`?streamId=` / `?runId=` 携带的是流 id，**绝不**是令牌）。
- 如果你不知道令牌，请用一个全新的 `--token` 重启后端，并更新所有客户端。

参见 [REST API——鉴权](/zh/reference/rest-api#authentication)。

## 插件 worker 崩溃

**症状。** 某插件的 `client.invoke(...)` 被拒、宿主报告 worker 以非零码退出，或者调用开始超时。

**原因。** worker 是一个独立的操作系统进程（`backend/worker.jar`），通过 stdio 通信换行分隔的 JSON-RPC 2.0。它可能因未处理的异常、内存溢出而崩溃，或者——最常见的——因为一行写到 `stdout` 的日志破坏了 RPC 帧的同步。

**修复。**

1. **检查 stderr。** worker 的日志走 `stderr`（SDK 把 `System.out` 重定向到 `System.err`，以保护协议通道）。崩溃原因就在那里。
2. **检查 JSON-RPC 帧。** `stdout` 专用于协议消息——每行一个 JSON-RPC 对象。`stdout` 上的任何其他内容（一次多余的 `println`、一条横幅、一段堆栈跟踪）都会破坏该流。请把所有诊断信息都放在 `stderr`。
3. **重启 worker**：先禁用再重新启用插件——`PATCH /api/plugin-market/{id}/enabled {enabled:false}`，然后 `{enabled:true}`。禁用会把进程拆下；启用会在下次调用时按需重新拉起。
4. 对于挂起而非崩溃的 worker，取消任何在途的 RPC 并禁用插件以回收该进程。

参见 [Worker（JSON-RPC）](/zh/plugins/worker) 与 [常见陷阱——向 stdout 打日志](/zh/plugins/pitfalls)。

## 微前端加载错误

**症状。** 插件 UI 的 iframe 是空白的、脚本悄悄执行失败，或者 Vue 的响应式/主题/组件表现异常。

**原因。** 两个彼此独立的机制：

- **CSP。** 宿主在严格的内容安全策略下提供插件 UI 资产。内联脚本与不被允许的来源都会被拒绝——它们从不执行。
- **Vue/Vuetify 去重。** 如果 MF 打包了自己的 Vue 或 Vuetify，两个实例会并存，从而破坏响应式以及共享的组件/主题注册表。

**修复。**

- **CSP：** 把所有 JavaScript 放进通过 `<script src>` 加载的外部文件（脚手架写的是 `<script type="module" src="app.js">`），并且从插件自身的 `/plugin-runtime/{id}/**` 树加载每一份资产。不要内联脚本或内联事件处理器。
- **Vue/Vuetify 去重：** **不要**打包 Vue 或 Vuetify。从宿主的 import map 中解析它们，并从 `PluginContext` 通过 `app.use(ctx.vuetify)` 安装宿主实例。宿主的 MF 加载器专门把共享的 Vuetify（MD3）实例传递进来，以便插件复用。
- 对于「module not found」/ import-map 错误，请确认 MF 是从宿主 map 加载 Vue/Vuetify，而不是自行声明。

参见 [UI 微前端](/zh/plugins/ui-microfrontend) 与 [常见陷阱](/zh/plugins/pitfalls)。

## 下一步

- [REST API](/zh/reference/rest-api)——确认你访问的是正确的 endpoint 并带有正确的鉴权。
- [SSE 事件](/zh/reference/sse-events)——流式帧的参考。
- [常见陷阱](/zh/plugins/pitfalls)——面向插件作者、以「问题/原因/修复」形式展开的陷阱。
