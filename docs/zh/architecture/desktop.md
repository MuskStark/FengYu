---
title: 桌面端
description: Infinia 4.0.0 桌面外壳是一个 Electron 43.x 应用（productName Infinia，版本 4.0.0，TypeScript 主进程），在环回地址上拉起 Java 后端，监管 SETUP 到 APP 的切换，通过 contextBridge 向渲染器暴露 API，并掌管窗口、托盘、日志与自动更新器。
lang: zh-CN
---

# 桌面端

Infinia 桌面外壳是一个用 TypeScript 编写（主进程）的 **Electron 43.x** 应用。它的职责是进程监管：拉起 Java 后端、发现它的端口、驱动它从 SETUP 进入 APP 模式、把 UI 所需的凭据交给它，并在用户退出时把一切拆除。产品名为 **Infinia**，版本 **4.0.0**（见 `desktop/electron/package.json`，`productName: "Infinia"`）。后端生命周期与之前的 Tauri 外壳**保持不变**——被替换的只是实现它的外壳本身。

## 开发版与发布版

外壳的行为取决于是否已打包：

| Profile | 后端 | 窗口 |
| --- | --- | --- |
| **Dev**（`!app.isPackaged`） | 外部——由 `FENGYU_JAR` 指向某个 jar，或已运行在 `:24056` 的后端（通过 Vite 代理访问） | 立即打开，加载 `localhost:5173` |
| **Release**（`app.isPackaged`） | 由外壳以 jar sidecar 方式拉起 | 在后端健康后打开，加载内嵌的 SPA |

在开发模式下，开发者分别启动后端和 Vite 开发服务器；外壳只负责承载窗口。在发布模式下，外壳端到端地掌管后端进程。

## 后端拉起（发布版）

发布版以一种固定的命令形式拉起打包好的 jar（从旧的 Rust 实现逐字移植）：

```bash
java -Dfengyu.plugins.official-directory=<plugins-dir> \
     -cp <jar> \
     fan.summer.fengyu.HeadlessLauncher \
     --port=24056 \
     --token=<t>
```

外壳读取子进程的 stdout 寻找 `FENGYU_PORT=<n>` 这一行，期限为 **30 秒**（可取消，因此缓慢启动期间关闭窗口不会挂起）。如果该行在期限内没有出现，启动即告失败。后端的 stdout/stderr 行会同步写入 `~/.fengyu/logs/backend-stdout.log`。

Java 在运行时解析：**带 JRE** 版本优先使用 `<resourcesPath>/jre/bin/java`；**不带 JRE** 版本使用 `PATH` 中的 `java`。若找不到 `java`，外壳会弹出一个原生错误对话框并退出。

## 健康检查与初始化编排

一旦端口已知，外壳会驱动后端经过三个阶段：

1. **`wait_for_health`**——以 **300 毫秒**为间隔、**每次请求 2 秒超时**、**总体 30 秒**为期限，带上 `X-FengYu-Token` 头轮询 `GET /api/health`。只有 HTTP 200 才算就绪。使用 Node 24.17 内置的 `fetch` + `AbortController`。
2. **`check_setup_mode`**——探测 `GET /api/setup/status`，以判断后端启动进入了 SETUP 还是 APP 模式（响应体含 `"initialized":false` → SETUP）。
3. **`run_backend_until_app_mode`**——把整个循环串起来：拉起 → 等待健康 → 检查初始化模式。如果后端处于 SETUP 模式，外壳会等待该进程以退出码 `0`（`SETUP_DONE`）退出，然后**重新拉起**后端，此时它会带着已生效的数据源以 APP 模式重新启动。重新拉起后，外壳会校验端口未改变、且后端已进入 APP 模式；任一不满足即视为致命错误。

## 前端 bridge（contextBridge）

外壳的 preload 脚本在页面加载前，通过 `contextBridge` 在 `window.fengyu` 上暴露一个受控的 API：

```js
window.fengyu.apiBase()        // 'http://127.0.0.1:<port>'——只读快照
window.fengyu.token()          // 每次启动的 X-FengYu-Token——只读快照
window.fengyu.desktop          // true——特性标志
window.fengyu.pickFile(filters)   // → 原生打开对话框（IPC）
window.fengyu.pickDirectory()     // → 原生打开对话框（IPC）
```

`apiBase`/`token` 是在启动时捕获的**只读快照**。SPA 直接通过环回地址与后端通信——AI 对话的 SSE 流、文件上传、插件微前端宿主都需要原生的 `fetch`/`EventSource`/`FormData`，而 IPC 无法承载这些，因此令牌以快照形式暴露，而非隐藏在完整的 IPC 代理背后。该令牌每次启动重新生成、仅限环回地址，且后端无论如何都强制执行 endpoint ACL。这取代了旧的 Tauri `window.__FENGYU_*` 全局变量。Vue SPA 通过 `connection` store / `config.ts` 读取它们来配置每一次 API 调用。在普通浏览器中 `window.fengyu` 为 `undefined`，因此 Web 模式会回退到环境变量。见[前端](/zh/architecture/frontend)。

**BrowserWindow 安全姿态：** `contextIsolation: true`、`nodeIntegration: false`、`sandbox: true`、`webSecurity: true`（默认）——标准的 Electron 安全姿态。CSP 由后端的 SPA 响应头治理，主进程中不会设为 `null`。

## 桌面增强能力

旧的 Tauri 外壳所不具备的四项能力：

- **单实例锁**——`app.requestSingleInstanceLock()`。再次启动会显示并聚焦已有窗口（也会从托盘恢复）。
- **系统托盘**——图标从旧外壳迁移而来；菜单：显示 / 隐藏 / 退出。驱动下文的关闭语义。
- **文件日志**——`electron-log` 把主进程日志写入 `~/.fengyu/logs/desktop.log`（与后端日志同目录）；后端的 stdout/stderr 同步写入 `~/.fengyu/logs/backend-stdout.log`。内置按大小/日期滚动。
- **自动更新**——`electron-updater`，源为 GitHub Releases（`latest*.yml` 由 electron-builder 生成）。在 `app.whenReady()` 之后做非阻塞检查；发现有更新时 → 原生对话框确认 → 下载并安装。Alpha 未签名：Windows 用 NSIS，macOS 需用户在 Gatekeeper 放行。

## 关停语义（已变更——重要）

由于引入了托盘，后端的生命周期现在绑定到**应用退出**，而非窗口关闭：

| 动作 | Tauri（旧） | Electron（新） |
| --- | --- | --- |
| 窗口关闭按钮 | 杀死后端并退出 | **隐藏到托盘**，后端保持存活 |
| 托盘「退出」/ Cmd+Q / Alt+F4 | 不适用 | 杀死后端（SIGTERM，兜底 SIGKILL）并退出 |

主进程在 `before-quit` 事件（而非 `window.on('close')`）时杀死后端。close 处理器在应用并非真正退出时会调用 `preventDefault()` + `window.hide()`。

## 窗口与对话框集成

- **窗口尺寸：** `1280 × 820`，最小 `960 × 640`（与之前的外壳一致）。
- **原生对话框：** `pickFile` / `pickDirectory` 通过 IPC 走 Electron 的原生对话框，并暴露在 `window.fengyu` 上；前端通过 `desktop.ts` 外观来访问它们。

## 打包

打包由 **electron-builder** 处理（`desktop/electron/electron-builder.yml`）。每个平台发布两种安装包变体，由 CI 的 `--config` 覆盖从同一份基础配置构建：

| 平台 | 不带 JRE（lite） | 带 JRE（自包含） |
| --- | --- | --- |
| macOS（arm64 + x64） | `Infinia-<ver>-mac.dmg` | `Infinia-<ver>-mac-jre.dmg` |
| Windows（x64） | `Infinia-<ver>-win.exe`（NSIS） | `Infinia-<ver>-win-jre.exe` |
| Linux（x64） | `Infinia-<ver>.AppImage` | `Infinia-<ver>-jre.AppImage` |

带 JRE 的变体在 `<resources>/jre/` 下内嵌一个 **jlink 最小化的** JRE（由 CI 从 JDK 21 通过 `jdeps` + `jlink --strip-debug` 生成）。Alpha 构建为**未签名**。

## 下一步

- [后端](/zh/architecture/backend)——sidecar 实际在运行什么，以及外壳所驱动的 SETUP/APP 模式。
- [前端](/zh/architecture/frontend)——SPA 如何消费 `window.fengyu` bridge。
- [快速开始](/zh/quickstart)——`cd desktop/electron && npm run dev` 与 `npm run build`。
