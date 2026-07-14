---
title: 功能特性
description: 一览 Infinia 4.0.0 能做什么。
lang: zh-CN
---

# 功能特性

Infinia（蜂语 FengYu）是一个模块化的 Web + 桌面工具箱。下列功能随 4.0.0 一起发布——一个无头 Spring Boot 后端、一个 Vue 3 + Vuetify 3 UI，以及一个 Tauri 2.0 桌面外壳，并通过 `.fyp` 插件包进行扩展。

## 能力矩阵

| 功能 | 作用 | 了解更多 |
| --- | --- | --- |
| **AI 对话** | 多后端流式对话（SSE）。支持 Ollama、OpenAI、Anthropic 和 DeepSeek 后端。 | [AI 对话指南](/zh/guide/ai-chat) |
| **AI 智能体** | 「规划-执行」智能体，把目标分解为多个步骤，并对敏感操作执行确认。 | [AI 对话指南](/zh/guide/ai-chat) |
| **Excel 拆分** | 按工作表、按列值或按复杂规则拆分工作簿。以插件形式提供，附带六个 AI 工具。 | [Excel 插件](/zh/plugins/official-excel) |
| **邮件中心** | 多账户确认式 SMTP 发送、地址簿、手动 IMAP 收取、归档，以及七个 AI 工具。 | [邮件中心](/zh/plugins/email-center) |
| **Markdown 编辑器** | 在内置编辑器中编辑并预览 Markdown。 | [功能首页](/zh/) |
| **插件市场** | 浏览、安装、更新与管理 `.fyp` 插件包——JSON-RPC Worker 与微前端 UI。 | [插件市场](/zh/plugins/marketplace) |
| **多数据库** | 首次启动向导可选择 H2、SQLite、MySQL 或 PostgreSQL，密码采用 AES-GCM 加密。 | [数据库指南](/zh/guide/database) |
| **国际化** | 以英文为主的文档，以及通过 `vue-i18n` 本地化的 Vue UI。 | [功能首页](/zh/) |
| **深色 / 浅色主题** | Material Design 3 主题，支持深色与浅色模式，并与插件微前端共享。 | [设计系统](/zh/design-system) |

## 插件模型

插件是自包含的 `.fyp` 包：一个 `manifest.json`、一个 `ui/` 微前端，以及一个通过 JSON-RPC 2.0 与宿主通信的 `backend/worker.jar`。插件市场负责它们的安装、更新与卸载。详见[插件市场](/zh/plugins/marketplace)页面。

## 下一步

- [快速开始](/zh/quickstart)——从源码构建并运行。
- [架构概述](/zh/architecture/overview)——各部分如何连接。
