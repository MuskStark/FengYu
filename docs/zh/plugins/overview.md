---
title: 插件概述
description: FengYu 插件是一个自包含的 .fyp 包——清单、沙箱化的 UI 微前端，以及一个进程外的 JSON-RPC 2.0 worker.jar——在不触碰宿主 Spring 上下文的前提下扩展能力。
lang: zh-CN
---

# 插件概述

FengYu 插件通过新的 UI 与后端能力扩展宿主，同时保持**严格隔离**：其代码永远不会运行在宿主 Spring 上下文之中，其 UI 也永远不会与宿主的 DOM 树共享。每个插件都以单个 `.fyp` 包的形式分发，宿主从安装到卸载全程监管其生命周期。

## 插件是什么

插件是一个 **`.fyp` 包**——一个 zip 归档，由三部分组成：

| 路径 | 内容 |
| --- | --- |
| `manifest.json` | 元数据、权限、AI 工具以及启动命令 |
| `ui/` | 微前端资源（入口 HTML + JS），通过 `/plugin-runtime/{id}/**` 提供 |
| `backend/worker.jar` | worker 可执行文件，作为独立的操作系统进程被启动 |

UI 运行在**沙箱化的 iframe** 中，通过 `@fengyu/plugin-sdk` 提供的 `postMessage` 桥与宿主通信。后端是一个**进程外的 worker**，通过 stdio 上的 JSON-RPC 2.0 通信。worker 崩溃或挂起都不会拖垮宿主，worker 也无法触及宿主的 bean 或 JPA 会话。

## 官方插件与第三方插件

插件有两个来源：

- **官方插件**——由 FengYu 团队构建并签名，清单中声明 `"official": true`，由 `OfficialPluginSeeder` 预置进每一次全新安装。随产品发布的官方插件集合是 `fan.summer.markdown`、`fan.summer.excel` 和 `fan.summer.email`。
- **第三方插件**——任何用户通过插件市场安装的 `.fyp` 归档。其 `source` 为 `THIRD_PARTY`。

描述符将这一点以 `source` 字段——`OFFICIAL` 或 `THIRD_PARTY`——暴露出来，该字段出现在 `GET /api/plugin-runtime` 返回的每一个 `InstalledPluginDescriptor` 上。

> 每个官方插件都有详细文档：[Markdown](/zh/plugins/official-markdown)、[Excel](/zh/plugins/official-excel)、[邮件中心](/zh/plugins/email-center)。

## 生命周期

插件在宿主的 `PluginProcessManager` 与 `PluginPackageService` 控制下依次经历以下状态：

```
install  ──►  enabled  ──►  invoked (UI + worker RPC)  ──►  disabled  ──►  uninstalled
   │            │                                            │
   └─ 通过市场上传 .fyp                                       └─ DELETE /api/plugin-market/{id}
```

1. **安装（Install）**——通过[插件市场](/zh/plugins/marketplace)上传 `.fyp`；解析其清单并存储。
2. **启用（Enable）**——`PATCH /api/plugin-market/{id}/enabled {enabled:true}`；worker 进程在首次调用时被惰性启动。
3. **调用（Invoke）**——UI 在其 iframe 中加载；对 `client.invoke(method, params)` 的调用会被宿主以 JSON-RPC 形式转发给 worker。参见 [Worker（JSON-RPC）](/zh/plugins/worker)。
4. **禁用（Disable）**——`PATCH .../enabled {enabled:false}`；宿主**立即停止 worker 进程**。
5. **卸载（Uninstall）**——`DELETE /api/plugin-market/{id}`；将该插件从目录中移除并停止其进程。

## `source` 字段

每个已安装的描述符都携带一个 `source` 鉴别字段，以便 UI 区分内置插件与用户安装的插件：

| 取值 | 含义 |
| --- | --- |
| `OFFICIAL` | 来自内置官方集合（清单中 `official: true`） |
| `THIRD_PARTY` | 由用户从 `.fyp` 归档安装 |

`source` 是只读的——它在安装时根据清单的 `official` 标志派生，启用/禁用周期不会改变它。

## 下一步

- [入门](/zh/plugins/getting-started)——用 `fengyu plugin create` 脚手架生成一个插件。
- [清单](/zh/plugins/manifest)——完整的 `manifest.json` 字段参考。
- [架构：插件系统](/zh/architecture/plugin-system)——宿主如何挂载并监管插件。
