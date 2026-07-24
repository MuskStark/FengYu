---
title: 前端
description: Infinia 4.0.0 前端是一个 Vue 3.5.39 + TypeScript SPA——Pinia 状态、vue-router 4、vue-i18n 10，以及 Vuetify 3（MD3）——以微前端方式加载插件 UI，并在初始化完成前重定向到 /setup。
lang: zh-CN
---

# 前端

Infinia 前端是一个用 TypeScript 编写的 **Vue 3 单页应用**。它渲染宿主外壳，并在运行时以微前端方式加载插件 UI。同一份产物在浏览器标签页和 Electron BrowserWindow 中原封不动地运行。

## 技术栈

| 包 | 版本（主版本） | 角色 |
| --- | --- | --- |
| `vue` | 3.5.39 | UI 框架 |
| `vuetify` | 3 | 组件库，Material Design 3 |
| `pinia` | 2 | 状态管理 |
| `vue-router` | 4 | 路由 |
| `vue-i18n` | 10 | 国际化 |
| `vite` | 6 | 开发服务器 + 构建 |

MD3 调色板（Google 默认值，主色 `#6750A4`）和浅色/深色主题，通过宿主的 Vuetify 实例与插件微前端共享。见[设计系统](/zh/design-system)页面。

## Pinia store

应用状态被拆分到多个职责聚焦的 Pinia store 中：

- `aiSession`——当前 AI 对话 / 智能体状态
- `aiConfirmation`——对敏感智能体操作的确认
- `categories`——插件分类树
- `connection`——后端可达性 / 端口 / 令牌的连接配置
- `nav`——导航状态
- `plugins`——已安装的插件列表与描述符
- `settings`——用户设置
- `setup`——首次启动向导状态
- `theme`——MD3 主题与深色/浅色模式

## 微前端宿主

插件 UI 并不打包进 SPA。它们由位于 `frontend/src/mf/loader.ts` 的 MF 宿主按需加载——动态导入插件的 UI 入口，并把它挂载到一个目标元素上，同时传入宿主提供的上下文：

```ts
const mod = await import(uiEntry)
mod.default.mount(el, ctx)
```

微前端通过 `ctx` 复用宿主的 Vuetify 实例和主题，因此 MD3 token 在外壳和每一个插件之间保持一致。详见[插件系统](/zh/architecture/plugin-system)。

## 桌面端集成

当 SPA 在 Electron 外壳内运行时，`frontend/src/mf/desktop.ts` 作为 `window.fengyu` bridge 的外观（facade），暴露 `pickFile` 和 `pickDirectory`（底层通过 IPC 走 Electron 的原生对话框）。在普通浏览器中，这些会回退到浏览器等价实现。

Electron 外壳在页面加载前通过 preload 的 `contextBridge` 暴露 `window.fengyu`：

- `window.fengyu.apiBase()`——后端基址 URL，例如 `http://127.0.0.1:{port}`（只读快照）
- `window.fengyu.token()`——每次启动的 `X-FengYu-Token` 值（只读快照）
- `window.fengyu.desktop`——`true` 特性标志（取代旧的 `isTauri()` 探测）

`connection` store / `config.ts` 读取它们来配置每一次 API 调用。在普通浏览器中 `window.fengyu` 为 `undefined`，此时 `config.ts` 回退到环境变量；在开发模式（浏览器）下，Vite 代理把同样的 `/api` 和 `/plugin-runtime` 路径转发到 `localhost:24056`。

## 初始化守卫

一个 vue-router 导航守卫会在放用户通过向导之前检查 `getSetupStatus()`。如果后端报告尚未初始化，无论目标路由是什么，守卫都会重定向到 `/setup`。初始化完成后，用户即被放行进入主应用。

## 下一步

- [架构概述](/zh/architecture/overview)——SPA 如何夹在后端与外壳之间。
- [桌面端](/zh/architecture/desktop)——`window.fengyu` bridge 从何而来。
- [设计系统](/zh/design-system)——共享的 MD3 + Vuetify 主题模型。
