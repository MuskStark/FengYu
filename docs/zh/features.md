---
title: 功能特性
description: Infinia 4.0.0 可编排的能力一览。
lang: zh-CN
---

# 功能特性

**蜂语 FengYu**（Infinia）是一个 *AI 原生的流程编排平台*。「规划-执行」智能体把自然语言目标拆解为多步业务流程，并统一调度三类扩展面 —— `.fyp` 插件、`.fys` 技能、进程内 AI 工具 —— 之上是无头（headless）Spring Boot 后端、Vue 3 + Vuetify 3 界面与 Tauri 2.0 桌面外壳。

## 编排如何运作

智能体是平台的脊柱。每一个业务流程都遵循同一个循环：

1. **描述目标。** 你在对话中用自然语言陈述一个业务目标。
2. **规划并调用。** 智能体把目标拆解为多个步骤，并为每个步骤调用最合适的扩展面 —— 用 `.fyp` 插件处理具体能力、用 `.fys` 技能获取领域流程/知识、或调用进程内 AI 工具。
3. **确认敏感操作。** 触及外部世界的步骤（发送邮件、写入文件、变更数据）在执行前需要你明确确认。结果回流到对话，失败时智能体会重新规划。

```
你 → 智能体规划 → .fyp 插件 / .fys 技能 / AI 工具 → （敏感则确认）→ 结果
                  ↑──────────── 失败时重新规划 ────────────┘
```

## 三类扩展面

智能体编排三类各自独立、刻意分离的扩展面：

| 扩展面 | 是什么 | 智能体用它做什么 |
| --- | --- | --- |
| [**插件**](/zh/plugins/overview)（`.fyp`） | 隔离的插件包：一个 JSON-RPC Worker + 一个微前端 UI。 | 具体能力 —— 文件处理、邮件、数据工具 —— 从[市场](/zh/plugins/marketplace)安装。 |
| [**技能**](/zh/skills/)（`.fys`） | Codex 风格的渐进式插件包。 | 领域知识与分步流程，按需加载以保持上下文精简。 |
| [**AI 工具**](/zh/plugins/ai-tools) | 进程内的 Spring AI `ToolCallback` bean。 | 模型在对话中可直接调用的轻量操作。 |

## 能力矩阵

| 功能 | 做什么 | 了解更多 |
| --- | --- | --- |
| **AI 智能体** | 「规划-执行」智能体，把目标拆解为多个步骤，敏感操作需人工确认。 | [智能体指南](/zh/guide/ai-agent) |
| **AI 对话** | 多后端对话，支持流式输出（SSE）。支持 Ollama、OpenAI、Anthropic、DeepSeek 等后端。 | [对话指南](/zh/guide/ai-chat) |
| **插件市场** | 浏览、安装、更新与管理 `.fyp` 插件包 —— JSON-RPC Worker 与微前端 UI。 | [市场](/zh/plugins/marketplace) |
| **技能** | 渐进式 `.fys` 插件包，为智能体按需提供领域流程。 | [技能](/zh/skills/) |
| **Excel 拆分** | 按工作表、列值或复杂规则拆分工作簿。以插件形式提供并附带六个 AI 工具。 | [Excel 插件](/zh/plugins/official-excel) |
| **邮件中心** | 多账户 SMTP 确认式发送、通讯录、手动 IMAP 收取、归档，以及七个 AI 工具。 | [邮件中心](/zh/plugins/email-center) |
| **Markdown 编辑器** | 在内置编辑器中编辑与预览 Markdown。 | [Markdown 插件](/zh/plugins/official-markdown) |
| **多数据库** | 首次启动向导可选择 H2、SQLite、MySQL 或 PostgreSQL，密码采用 AES-GCM 加密。 | [数据库指南](/zh/guide/database) |
| **国际化** | 以英文为主的文档，以及通过 `vue-i18n` 本地化的 Vue UI。 | [设计系统](/zh/design-system) |
| **深色 / 浅色主题** | Material Design 3 主题，支持深色与浅色模式，与插件微前端共享。 | [设计系统](/zh/design-system) |

## 后续步骤

- [快速开始](/zh/quickstart) —— 从源码构建并运行。
- [架构概览](/zh/architecture/overview) —— 各部分如何连接。
- [智能体指南](/zh/guide/ai-agent) —— 「规划-执行」循环的详解。
