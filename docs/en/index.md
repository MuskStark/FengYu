---
title: Infinia
description: An AI-native orchestration platform — a plan-and-execute Agent turns natural-language goals into multi-step business workflows.
lang: en
layout: home
hero:
  name: Infinia
  text: AI-native Workflow Orchestration
  tagline: Where bees go, flows follow.
  image: /logo.svg
  actions:
    - theme: brand
      text: Quick Start
      link: /en/quickstart
    - theme: alt
      text: Features
      link: /en/features
features:
  - icon: 🤖
    title: AI Agent
    details: A plan-and-execute agent decomposes goals into steps across Ollama, OpenAI, Anthropic, and DeepSeek — with approvals for sensitive actions.
    link: /en/guide/ai-agent
  - icon: 🧩
    title: Plugins (.fyp)
    details: Isolated .fyp packages — a JSON-RPC worker plus a micro-frontend UI — installable from the marketplace as capabilities the Agent can call.
    link: /en/plugins/marketplace
  - icon: 📜
    title: Skills (.fys)
    details: Codex-style progressive-disclosure skills (.fys) that give the Agent on-demand domain knowledge and procedures for each business scenario.
    link: /en/skills/
  - icon: 🖥️
    title: Cross-Platform
    details: The same Vue UI runs in a browser or an Electron desktop window on Windows, macOS, and Linux. The headless backend binds loopback only — your data stays on your machine.
    link: /en/architecture/overview
  - icon: 💾
    title: Multi-Database
    details: First-launch wizard picks H2, SQLite, MySQL, or PostgreSQL. Passwords AES-GCM encrypted, machine-bound.
    link: /en/guide/database
  - icon: 🌍
    title: Built for Everyone
    details: English-first docs, a localized Vue UI (vue-i18n), and a Material Design 3 theme (Vuetify 3) with dark and light modes — shared with plugin micro-frontends.
    link: /en/design-system
---

## From goal to workflow

**Infinia** (蜂语 / FengYu) is an *AI-native orchestration platform*. You describe a
business goal in natural language; a plan-and-execute Agent decomposes it into steps
and orchestrates three extension surfaces — `.fyp` plugins, `.fys` skills, and
in-process AI tools — to carry it out. It runs as a headless Spring Boot backend, a
Vue 3 + Vuetify 3 UI, and an optional Electron desktop shell.

::: info 4.0.0-alpha
Infinia 4.0.0 is an **unsigned Alpha**. See the [Quick Start](/en/quickstart) to build
and run from source, or the [Features](/en/features) page for what the Agent can orchestrate today.
:::
