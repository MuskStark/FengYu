---
title: 内置浏览器能力
description: Infinia 4.0.0 中的浏览器自动化是一项宿主内嵌能力，而非插件——内置在桌面应用中，由 Electron 原生 webContents 与基于回环 HTTP 桥的 CDP 驱动。无 Playwright、无单独 Chromium 下载、非插件。仅桌面端。九个需确认的 AI 工具。
lang: zh-CN
---

# 内置浏览器能力

Infinia 的浏览器自动化是一项**宿主内嵌能力**：它内置在桌面应用中，由后端 `BrowserTool` 暴露，**不是** `.fyp` 插件。一个 Agent 流程可以通过九个 AI 工具端到端地驱动一个真实网页——导航、点击、输入、读取文本、查询元素、截图、等待、执行 JS、关闭——每个工具都经过宿主对外部效应操作的确认流程。

::: tip 变更说明
原先的官方插件 `plugin-browser`（`fan.summer.browser`，基于 Playwright）已被**移除**。浏览器自动化现在宿主内嵌：它复用 Electron 外壳的原生 webContents，以及基于回环 HTTP 桥的 Chrome DevTools Protocol（CDP）。**不依赖 Playwright**，也**不下载单独的 Chromium**。
:::

## 工作原理

- **宿主内嵌，非插件。** 该能力由后端 `BrowserTool`（一个 Spring AI `ToolCallback`）提供，它与桌面外壳通信——不存在 `manifest.json`、不存在进程外的 worker，也不存在 `.fyp` 包。
- **Electron 原生引擎。** 通过 Electron 原生 `webContents` 加上基于回环 HTTP 桥的 CDP 驱动一个真实的浏览器窗口。不打包 Playwright，也不下载或启动单独的 Chromium 可执行文件。
- **仅桌面端。** 该能力需要 Electron 桌面外壳。在**纯 Web / 无头模式下不可用**（一个浏览器标签页无法驱动另一个浏览器），因此在没有桌面外壳运行时不会注册 `browser_*` 工具。
- **需确认。** 每个工具都是外部效应操作；宿主确认门会在每次调用执行前确认，普通对话与「规划-执行」智能体中皆然。

## 九个 AI 工具

`BrowserTool` 注册了九个 AI 工具。每个与一项宿主侧的浏览器操作一一对应——没有插件 worker，也没有单独的 UI 流水线；AI 表面*就是*整个契约。

| 工具 | 用途 |
| --- | --- |
| `browser_navigate` | 打开一个 URL；返回最终 URL 和页面标题。可选 `waitUntil`（`load` \| `domcontentloaded` \| `networkidle`）。 |
| `browser_click` | 点击由 CSS 选择器匹配的元素。 |
| `browser_type` | 清空后向选择器填入文本（默认先清空）。 |
| `browser_get_text` | 读取某选择器的文本（省略时为整页），截断到 64K。 |
| `browser_query` | 统计选择器匹配数并返回最多 5 条 innerText 样本。 |
| `browser_screenshot` | 把视口/整页/元素截成 PNG；返回路径、尺寸，以及页面的可访问性树文本。 |
| `browser_wait_for` | 等待元素达到 `attached` / `detached` / `visible` / `hidden` 状态。 |
| `browser_eval_js` | 在页面中执行一个 JS 表达式并返回序列化后的结果。 |
| `browser_close` | 关闭浏览器窗口并释放资源；下一次 `browser_*` 调用会重新打开。 |

每个工具都返回标准的 `{ success, summary, ... }` 信封。参见 [AI 工具](/zh/plugins/ai-tools)。

> **截图是文本，不是像素。** `browser_screenshot` 会保存 PNG，*并*附带页面可访问性树的 YAML 文本，因为模型看不到图像——它通过阅读 a11y 树来理解页面。

## 为什么不是插件

驱动一个真实的浏览器窗口需要只有桌面外壳才具备的能力（原生 `webContents`、CDP 访问、窗口生命周期）。沙箱化的插件 worker 无法触及外壳，因此之前的基于 Playwright 的插件要自带 Chromium 下载和额外的进程树。把能力内嵌到宿主中，去除了那次下载、Playwright 依赖以及 worker 生命周期，同时保留了同样的九工具 AI 表面。

## 可用性

| 目标 | 浏览器能力 |
| --- | --- |
| 桌面端（Electron 外壳） | 可用——注册九个 AI 工具。 |
| Web / 无头（无 Electron 外壳） | **不可用**——不注册 `browser_*` 工具。 |

## 下一步

- [AI 工具](/zh/plugins/ai-tools)——九个 `browser_*` 工具如何聚合成 Spring AI `ToolCallback[]`。
- [桌面端架构](/zh/architecture/desktop)——提供 `webContents` + CDP 的 Electron 外壳。
- [插件概述](/zh/plugins/overview)——随产品发布的官方插件（浏览器自动化不在其中）。
