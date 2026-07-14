---
title: 桌面端
description: Infinia 4.0.0 桌面外壳是一个 Tauri 2.0 应用（productName Infinia，版本 4.0.0），在环回地址上拉起 Java sidecar，监管 SETUP 到 APP 的切换，注入 token/port/api-base bridge，并在窗口关闭时杀死 sidecar。
lang: zh-CN
---

# 桌面端

Infinia 桌面外壳是一个 **Tauri 2.0** 应用。它的职责是进程监管：拉起 Java 后端、发现它的端口、驱动它从 SETUP 进入 APP 模式、把 UI 所需的凭据交给它，并在用户关闭窗口时把一切拆除。产品名为 **Infinia**，版本 **4.0.0**（见 `tauri.conf.json`，`productName: "Infinia"`）。

## 开发版与发布版

外壳的行为因构建 profile 而异：

| Profile | 后端 | 窗口 |
| --- | --- | --- |
| **Dev**（`cfg(debug_assertions)`） | 外部——假定已运行在 `:24056`，通过 Vite 代理访问 | 立即打开 |
| **Release** | 由外壳以 jar sidecar 方式拉起 | 在后端健康后打开 |

在开发模式下，开发者分别启动后端和 Vite 开发服务器；外壳只负责承载窗口。在发布模式下，外壳端到端地掌管后端进程。

## Sidecar 拉起（发布版）

发布版以一种固定的命令形式拉起打包好的 jar：

```bash
java -Dfengyu.plugins.official-directory=... \
     -cp <jar> \
     fan.summer.fengyu.HeadlessLauncher \
     --port=24056 \
     --token=<t>
```

一个读取线程扫描子进程的 stdout 寻找 `FENGYU_PORT=<n>` 这一行，期限为 **30 秒**。如果该行在期限内没有出现，启动即告失败。

## 健康检查与初始化编排

一旦端口已知，外壳会驱动后端经过三个阶段：

1. **`wait_for_health`**——以 **300 毫秒**为间隔、**30 秒**为期限，带上 `X-FengYu-Token` 头轮询 `GET /api/health`。在后端作出应答之前，它都不算就绪。
2. **`check_setup_mode`**——探测 `GET /api/setup/status`，以判断后端启动进入了 SETUP 还是 APP 模式。
3. **`run_backend_until_app_mode`**——把整个循环串起来：拉起 → 等待健康 → 检查初始化模式。如果后端处于 SETUP 模式，外壳会等待该进程以退出码 `0`（`SETUP_DONE`）退出，然后**重新拉起** sidecar，此时它会带着已生效的数据源以 APP 模式重新启动。

## Bridge 注入

页面加载之前，外壳向 WebView 的 `window` 注入三个全局变量：

```js
window.__FENGYU_TOKEN__ = '<t>'
window.__FENGYU_PORT__  = '<n>'
window.__FENGYU_API_BASE__ = 'http://127.0.0.1:{port}'
```

Vue SPA 通过 `connection` store 读取它们来配置每一次 API 调用。见[前端](/zh/architecture/frontend)。

## 窗口与对话框集成

- **窗口尺寸：** `1280 × 820`，最小 `960 × 640`。
- **原生对话框：** `tauri_plugin_dialog` 暴露原生文件/目录选择器；前端通过 `desktop.ts` 外观（`pickFile` / `pickDirectory`）来访问它们。

## 生命周期

外壳在 `WindowEvent::Destroyed` 时杀死 sidecar 进程——关闭窗口即拆除后端。这里没有守护进程：后端存活的时间与窗口完全一致。

## 下一步

- [后端](/zh/architecture/backend)——sidecar 实际在运行什么，以及外壳所驱动的 SETUP/APP 模式。
- [前端](/zh/architecture/frontend)——SPA 如何消费被注入的 bridge 全局变量。
- [快速开始](/zh/quickstart)——`cargo tauri dev` 与 `cargo tauri build`。
