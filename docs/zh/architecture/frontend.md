---
title: 前端
description: Infinia 前端是一个 React 19 + TypeScript SPA——zustand 状态、react-router、react-i18next，以及构建在 zai.css 令牌体系上的 Tailwind 4——以微前端方式加载插件 UI，并在初始化完成前重定向到 /setup。
lang: zh-CN
---

# 前端

Infinia 前端是一个用 TypeScript 编写的 **React 19 单页应用**。它渲染宿主外壳，并在运行时以微前端方式加载插件 UI。同一份产物在浏览器标签页和 Electron BrowserWindow 中原封不动地运行。

## 技术栈

| 包 | 版本（主版本） | 角色 |
| --- | --- | --- |
| `react` / `react-dom` | 19 | UI 框架 |
| `react-router-dom` | 7 | 路由 |
| `zustand` | 5 | 状态管理 |
| `i18next` / `react-i18next` | 25 / 16 | 国际化 |
| `tailwindcss` + `zai.css` | 4 | 样式与设计令牌 |
| `@xyflow/react` | 12 | FengyuFlow 画布 |
| `vite` | 7 | 开发服务器 + 构建 |

## 两层架构

源码树拆分为框架无关的核心与很薄的 UI 边缘层：

- `src/platform/`——环境抽象：`window.fengyu` 桌面外观、URL/配置解析、浏览器回退。这里不做任何渲染。
- `src/services/`——所有 API 调用都经过的类型化服务层（结构化错误 code 映射到 i18n 消息）。
- `src/stores/`——职责聚焦的 zustand store：`aiSession`、`backgroundTasks`、`connection`、`notifications`、`pluginBackgroundJobs`、`plugins`、`settings`、`skills`、`toasts`、`update`。

## 微前端宿主

插件 UI 并不打包进 SPA。`PluginPage.tsx` 会把每个插件的 `uiEntry` 加载进沙箱化 iframe。iframe 与宿主先协商共享的 `@infinia/plugin-sdk/protocol` 版本，再通过 `postMessage` 交换类型化的请求、响应、取消和环境消息。插件无法直接触达宿主的 React 树；它在隔离边界内使用 `@infinia/plugin-ui`（插件本地的 Vue/Vuetify 实例）渲染。详见[插件系统](/zh/architecture/plugin-system)。

## 桌面端集成

当 SPA 在 Electron 外壳内运行时，`frontend/src/platform/desktop.ts` 作为 `window.fengyu` bridge 的外观（facade），暴露 `pickFile` 和 `pickDirectory`（底层通过 IPC 走 Electron 的原生对话框）。在普通浏览器中，这些会回退到浏览器等价实现。

Electron 外壳在页面加载前通过 preload 的 `contextBridge` 暴露 `window.fengyu`：

- `window.fengyu.apiBase()`——后端基址 URL，例如 `http://127.0.0.1:{port}`（只读快照）
- `window.fengyu.token()`——每次启动的 `X-FengYu-Token` 值（只读快照）
- `window.fengyu.desktop`——`true` 特性标志

`connection` store 与平台层读取它们来配置每一次 API 调用。在普通浏览器中 `window.fengyu` 为 `undefined`，此时平台层回退到环境变量；在开发模式（浏览器）下，Vite 代理把同样的 `/api` 和 `/plugin-runtime` 路径转发到 `localhost:24056`。

## 初始化守卫

应用级守卫（`App.tsx`，位于 `shell/BootGate.tsx` 之内）会在放用户通过向导之前检查 `services.system.setupStatus()`。如果后端报告尚未初始化，无论目标路由是什么，守卫都会重定向到 `/setup`。初始化完成后，用户即被放行进入主应用。

## 下一步

- [架构概述](/zh/architecture/overview)——SPA 如何夹在后端与外壳之间。
- [桌面端](/zh/architecture/desktop)——`window.fengyu` bridge 从何而来。
- [设计系统](/zh/design-system)——Zai 令牌模型，以及插件 UI 如何保持主题一致。
