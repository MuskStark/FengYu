---
title: 蜂语 FengYu
description: 一个 AI 原生的流程编排平台 ——「规划-执行」智能体把自然语言目标拆解为多步业务流程。
lang: zh-CN
layout: home
hero:
  name: 蜂语 FengYu
  text: AI 原生的流程编排平台
  tagline: 「蜂之所向，流之所往」
  image: /logo.svg
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/quickstart
    - theme: alt
      text: 功能特性
      link: /zh/features
features:
  - icon: 🤖
    title: AI 智能体
    details: 「规划-执行」智能体把目标拆解为多个步骤，支持 Ollama、OpenAI、Anthropic、DeepSeek 等多后端，敏感操作需人工确认。
    link: /zh/guide/ai-agent
  - icon: 🧩
    title: 插件（.fyp）
    details: 隔离的 .fyp 插件包 —— 一个 JSON-RPC Worker 加一个微前端 UI，可从市场安装，作为智能体可调用的能力。
    link: /zh/plugins/marketplace
  - icon: 📜
    title: 技能（.fys）
    details: Codex 风格的渐进式技能（.fys），为智能体按业务场景提供随需加载的领域知识与流程。
    link: /zh/skills/
  - icon: 🖥️
    title: 跨平台
    details: 同一套 Vue UI 可在浏览器或 Electron 桌面窗口中运行，覆盖 Windows、macOS 与 Linux。无头后端仅绑定环回地址 —— 数据始终留在你的机器上。
    link: /zh/architecture/overview
  - icon: 💾
    title: 多数据库
    details: 首次启动向导可选择 H2、SQLite、MySQL 或 PostgreSQL，密码采用 AES-GCM 加密、绑定本机。
    link: /zh/guide/database
  - icon: 🌍
    title: 面向所有人
    details: 以英文为主的文档、经 `vue-i18n` 本地化的 Vue UI，以及基于 Vuetify 3 的 Material Design 3 主题（深色与浅色）—— 与插件微前端共享。
    link: /zh/design-system
---

## 从目标到流程

**蜂语 FengYu**（Infinia）是一个 *AI 原生的流程编排平台*。你用自然语言描述业务目标，「规划-执行」智能体把它拆解为多个步骤，并统一调度三类扩展面 —— `.fyp` 插件、`.fys` 技能、进程内 AI 工具 —— 来完成它。它以无头（headless）Spring Boot 后端、Vue 3 + Vuetify 3 界面，以及可选的 Electron 桌面外壳运行。

::: info 4.0.0-alpha
Infinia 4.0.0 是**未签名的 Alpha 版本**。参见[快速开始](/zh/quickstart)从源码构建运行，或查阅[功能特性](/zh/features)了解智能体当前可编排的能力。
:::
