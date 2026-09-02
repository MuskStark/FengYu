---
title: 内置浏览器能力
description: Infinia 4.0.0 中的浏览器自动化是一项宿主内嵌能力，而非插件——内置在桌面应用中，由 Electron 原生 webContents 与基于回环 HTTP 桥的 CDP 驱动。支持隔离上下文、有状态标签页和多模态截图，无 Playwright。仅桌面端。25 个按副作用分类的 AI 工具。
lang: zh-CN
---

# 内置浏览器能力

Infinia 的浏览器自动化是一项**宿主内嵌能力**：它内置在桌面应用中，由后端 `BrowserTool` 暴露，**不是** `.fyp` 插件。一个 Agent 流程可以通过 25 个 AI 工具端到端地驱动真实网页标签页——导航与历史切换、管理隔离上下文、发现稳定 ref、检查、点击、悬停、滚动、输入、选择下拉选项、按键、截图、批处理、标签页管理、执行 JS 与关闭。检查类工具归为读取；导航、交互、执行 JavaScript 与关闭归为外部效应。

::: tip 变更说明
原先的官方插件 `plugin-browser`（`fan.summer.browser`，基于 Playwright）已被**移除**。浏览器自动化现在宿主内嵌：它复用 Electron 外壳的原生 webContents，以及基于回环 HTTP 桥的 Chrome DevTools Protocol（CDP）。**不依赖 Playwright**，也**不下载单独的 Chromium**。
:::

## 工作原理

- **宿主内嵌，非插件。** 该能力由后端 `BrowserTool`（一个 Spring AI `ToolCallback`）提供，它与桌面外壳通信——不存在 `manifest.json`、不存在进程外的 worker，也不存在 `.fyp` 包。
- **Electron 原生引擎。** 通过 Electron 原生 `webContents` 加上基于回环 HTTP 桥的 CDP 驱动一个真实的浏览器窗口。不打包 Playwright，也不下载或启动单独的 Chromium 可执行文件。
- **会话与标签页状态。** Java `BrowserSession` 会在每次调用中发送同一个逻辑会话/上下文/标签页 id，并按标签页缓存最新 URL、标题与 `ref → element` 标识。Electron 把这些 id 路由到隔离窗口；只有同一浏览器上下文内共享 Cookie。未知或过期 ref 会在 Java 侧直接失败，不会悄悄改点其他元素。
- **视觉模型收到真实像素。** 截图通过 bridge 返回 base64 PNG。后端会从文本工具信封中移除这些字节、保留紧凑附件元数据，并在下一轮模型消息中追加 Spring AI `Media(image/png)`。DOM snapshot 与可访问性树继续作为纯文本模型的回退。最大 20 MiB 的 PNG 会内联；更大的截图保留在 `imagePath`，并使用文本回退。
- **异步 bridge 传输。** Java 使用 `HttpClient.sendAsync` 与虚拟线程处理响应，只在 Spring AI 的同步工具回调边界等待。Electron 仍会串行执行输入操作，避免依赖鼠标和焦点的动作相互重叠。
- **仅桌面端。** 该能力需要 Electron 桌面外壳。在**纯 Web / 无头模式下不可用**（一个浏览器标签页无法驱动另一个浏览器），因此在没有桌面外壳运行时不会注册 `browser_*` 工具。
- **按副作用分类审批。** `find`、`snapshot`、标签页列表、文本/查询检查、截图与等待属于 `read`；导航/历史、标签页变更、批处理、点击/悬停/滚动/输入/选择/按键、执行 JS 与关闭属于 `external`。普通对话与「规划-执行」智能体使用同一审批策略。

## 25 个 AI 工具

`BrowserTool` 注册了 25 个 AI 工具。每个映射到一项宿主侧浏览器操作——没有插件 worker，也没有单独的 UI 流水线；AI 表面*就是*整个契约。

| 工具 | 用途 |
| --- | --- |
| `browser_navigate` | 打开一个 URL；返回最终 URL 和页面标题。可选 `waitUntil`（`load` \| `domcontentloaded` \| `networkidle`）。 |
| `browser_history` | 在活动标签页中后退、前进或刷新；成功后使旧 ref 失效。 |
| `browser_find` | 把 CSS 选择器解析为可供后续调用复用的稳定 ref。 |
| `browser_snapshot` | 返回页面可见结构与带稳定 ref 的可交互元素。 |
| `browser_contexts` | 列出隔离上下文及其活动标签页；上下文之间不共享 Cookie/本地存储。 |
| `browser_new_context` | 新建并选中一个全新的隔离上下文。 |
| `browser_select_context` | 切换上下文，并恢复其活动标签页/ref 缓存。 |
| `browser_close_context` | 关闭一个上下文中的全部标签页，并选中另一个上下文。 |
| `browser_tabs` | 列出当前上下文中的标签页 id、URL、标题与激活状态。 |
| `browser_new_tab` | 新建并选中标签页，可选立即导航。 |
| `browser_select_tab` | 切换到已有标签页，并恢复该标签页缓存的 ref/状态。 |
| `browser_close_tab` | 关闭一个标签页并选中另一个剩余标签页。 |
| `browser_click` | 点击由 CSS 选择器匹配的元素。 |
| `browser_hover` | 通过真实 CDP 指针事件悬停在可见且稳定的元素上。 |
| `browser_scroll` | 向页面或 selector/ref 目标发送有界 CDP 滚轮事件，支持嵌套滚动区。 |
| `browser_type` | 清空后向选择器填入文本（默认先清空）。 |
| `browser_press` | 向选择器/ref 目标或当前活动页面发送按键或快捷键。 |
| `browser_select` | 按精确 value/标签选择原生 `<select>` 选项，并验证选择已保持。 |
| `browser_get_text` | 读取某选择器的文本（省略时为整页），截断到 64K。 |
| `browser_query` | 统计选择器匹配数并返回最多 5 条 innerText 样本。 |
| `browser_screenshot` | 把视口/整页/元素截成 PNG；向支持视觉的模型附加像素，并返回路径、尺寸、DOM snapshot 与可访问性文本。 |
| `browser_wait_for` | 等待元素达到 `attached` / `detached` / `visible` / `hidden` 状态。 |
| `browser_batch` | 在同一个串行 bridge 请求中先抓取 snapshot，再立即点击、输入或按键。 |
| `browser_eval_js` | 在页面中执行一个 JS 表达式并返回序列化后的结果。 |
| `browser_close` | 关闭浏览器窗口并释放资源；下一次 `browser_*` 调用会重新打开。 |

每个工具都返回标准的 `{ success, summary, ... }` 信封。参见 [AI 工具](/zh/plugins/ai-tools)。

> **视觉能力取决于模型。** 支持视觉的 provider 会以 image part 收到 PNG；纯文本模型仍会收到可操作的 DOM snapshot 与可访问性树，因此截图调用在没有多模态能力时仍然有用。

## 为什么不是插件

驱动一个真实的浏览器窗口需要只有桌面外壳才具备的能力（原生 `webContents`、CDP 访问、窗口生命周期）。沙箱化的插件 worker 无法触及外壳，因此之前的基于 Playwright 的插件要自带 Chromium 下载和额外的进程树。把能力内嵌到宿主中，去除了那次下载、Playwright 依赖以及 worker 生命周期，同时保留并扩展了浏览器 AI 表面。

## 可用性

| 目标 | 浏览器能力 |
| --- | --- |
| 桌面端（Electron 外壳） | 可用——注册 25 个 AI 工具。 |
| Web / 无头（无 Electron 外壳） | **不可用**——不注册 `browser_*` 工具。 |

## 下一步

- [AI 工具](/zh/plugins/ai-tools)——内置工具与插件工具如何聚合成 Spring AI `ToolCallback[]`。
- [桌面端架构](/zh/architecture/desktop)——提供 `webContents` + CDP 的 Electron 外壳。
- [插件概述](/zh/plugins/overview)——随产品发布的官方插件（浏览器自动化不在其中）。
