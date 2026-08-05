---
title: 官方插件——浏览器代理
description: fan.summer.browser（v4.0.0-alpha.8）讲解——一个 automation 类别插件，带有 network/files.write 权限和九个 aiTools，通过 Playwright（Java）驱动真实的 Chromium——导航、点击、输入、抓取、截图、执行 JS。默认有头模式，Chromium 在首次使用时自动下载到插件的 data dir。
lang: zh-CN
---

# 官方插件——浏览器代理

`fan.summer.browser` 是官方的浏览器自动化插件。它启动一个真实的 Chromium 并暴露九个 AI 工具——导航、点击、输入、读取文本、查询元素、截图、等待、执行 JS、关闭——使一个 Agent 流程可以端到端地驱动一个真实网页。它是一个结合了**外部效应**引擎（浏览器）、**network** 权限和进程外 Playwright worker 的权威示例。

## 它做什么

- 以独立的进程树启动一个持久化的 Chromium（Playwright，Java），在连续的工具调用之间复用同一个浏览会话。
- 让 AI 能够导航、点击、输入、读取并查询 DOM、截图、等待状态以及执行任意 JavaScript。
- 在插件的 data dir 下保留一个真实的用户配置（cookie、登录状态），使会话在 worker 重启后依然存活。
- 首次使用时自动将 Chromium 下载到插件的 data dir，或复用用户配置的系统 Chrome/Edge。
- 默认以**有头模式**运行，人类可以观看 AI 驱动一个可见的浏览器。

## 清单

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.browser",
  "name": "Browser Agent",
  "description": "AI-driven browser automation: navigate, click, type, scrape, screenshot, eval JS",
  "version": "4.0.0-alpha.8",
  "author": "FengYu",
  "icon": "browser",
  "category": "automation",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0", "callTimeoutSeconds": 120 },
  "permissions": ["network", "files.write"],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": [ /* 九个工具——见下文 */ ]
}
```

要点：

- **`category: "automation"`**——一个自动化/驱动真实应用的插件。
- **`permissions: ["network", "files.write"]`**——`network` 是必需的，因为每个工具都在驱动一个会打开 URL 的实时浏览器；`files.write` 是必需的，因为 `browser_screenshot` 会把 PNG 写入插件的 data dir。
- **`backend.command: "java -jar backend/worker.jar"`** 搭配 **`protocol: "json-rpc-2.0"`** 和 **`callTimeoutSeconds: 120`**——该 worker 被允许拥有比默认值更长的单次 RPC 预算，因为导航/加载可能很慢。
- **`aiTools`** 有九个条目，所以 `supportsAi` 为 `true`。每个 `{name, method, effect: "external", ...}` 把一个面向模型的工具映射到一个 worker JSON-RPC 方法；每个工具都标记为 `external`，因为它会改变外部世界。

每个字段见 [清单](/zh/plugins/manifest)。

## 九个 AI 工具

worker（`BrowserWorkerMain`）注册了九个 JSON-RPC 方法。每个都与一个 `aiTools` 条目一一对应——没有额外的 UI 专用动作；AI 表面*就是*整个后端契约。

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
| `browser_close` | 关闭浏览器并释放资源；下一次 `browser_*` 调用会重新启动。 |

每个工具都返回标准的 `{ success, summary, ... }` 信封。这些 AI 工具与 UI 会调用的方法是同一批——不存在单独的 UI 侧流水线。参见 [AI 工具](/zh/plugins/ai-tools) 与 [Worker（JSON-RPC）](/zh/plugins/worker)。

> **截图是文本，不是像素。** `browser_screenshot` 会把 PNG 保存到 data dir，*并*附带页面可访问性树的 YAML 文本，因为模型看不到图像——它通过阅读 a11y 树来理解页面。

## Playwright 引擎

worker 是一个 Playwright（Java）进程。`BrowserSession` 拥有完整的生命周期：

- 一个 `Playwright` 实例（它衍生出 Playwright 自带的 Node driver 子进程）、一个持久化的 `BrowserContext`，以及一个 `Page`——在首次工具调用时惰性启动并复用，使 AI 的 `navigate → click → type` 序列共享同一个浏览会话。
- 通过 `launchPersistentContext(userDataDir, ...)` 启动，使配置（cookie、登录）在 worker 重启后依然存活。关闭 context 会终止 Chromium 进程树；关闭 `Playwright` 实例会终止自带的 Node driver 子进程。
- `browser_close` 和一个 JVM 关闭钩子都会回收整棵三级进程树（Chromium 子进程 → Chromium → Node driver），所以被杀掉的 worker 不会泄漏浏览器。

### Chromium 解析（三级）

`ChromiumResolver` 按优先级选取可执行文件：

1. **用户配置的路径**（系统的 Chrome/Edge），当其可执行时。
2. **已下载的 Chromium**，位于 `<dataDir>/chromium/` 下。
3. **自动下载**到 `<dataDir>/chromium/`，通过 `com.microsoft.playwright.CLI install chromium`。

如果三级都失败，则回退到 Playwright 自带/已安装的浏览器。触及网络的安装在接缝（seam）之后，所以解析逻辑无需网络即可单元测试。

### 配置（环境变量）

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `FENGYU_PLUGIN_DATA_DIR` | `~/.fengyu/plugins/fan.summer.browser` | 根 data dir：配置、截图、已下载的 Chromium。 |
| `FENGYU_BROWSER_HEADLESS` | `false` | `true`/`false`；默认**有头**，人类可以观看。 |

## Windows：unsandboxed 插件

Chromium 的原生沙箱依赖于一些 OS 能力，而插件 worker 在 Windows 上于 FengYu 沙箱之下无法使用这些能力。因此在 Windows 上，该插件需要宿主的 **`unsandboxedPlugins`** 开关（设置 → 运行时与安全）；没有它，worker 无法启动 Chromium。在 macOS/Linux 上，沙箱化的 worker 照常运行。

## UI

UI 是一个极简的微前端（一个落地/配置面板）——真正的能力在于那九个 AI 工具，而非丰富的 UI。它加载在 `/plugin-runtime/fan.summer.browser/**` 下的沙箱化 iframe 中，并通过 `@infinia/plugin-sdk` 与宿主桥接。参见 [UI 微前端](/zh/plugins/ui-microfrontend)。

## 下一步

- [清单](/zh/plugins/manifest)——完整的 schema，包括 `aiTools` 和 `permissions`。
- [AI 工具](/zh/plugins/ai-tools)——九个 `browser_*` 工具如何聚合成 Spring AI `ToolCallback[]`。
- [Worker（JSON-RPC）](/zh/plugins/worker)——`browser_*` 注册模式。
- [官方插件——Excel](/zh/plugins/official-excel)——一个带有向导式 UI 和文件 I/O 的兄弟插件。
