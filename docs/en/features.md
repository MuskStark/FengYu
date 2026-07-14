---
title: Features
description: What Infinia 4.0.0 does, at a glance.
lang: en
---

# Features

Infinia (蜂语 FengYu) is a modular web + desktop toolbox. The features below ship in 4.0.0 — a headless Spring Boot backend, a Vue 3 + Vuetify 3 UI, and a Tauri 2.0 desktop shell, extended by `.fyp` plugin packages.

## Capability matrix

| Feature | What it does | Learn more |
| --- | --- | --- |
| **AI Chat** | Multi-backend chat with streaming (SSE). Supports Ollama, OpenAI, Anthropic, and DeepSeek backends. | [AI Chat guide](/en/guide/ai-chat) |
| **AI Agent** | Plan-and-execute agent that decomposes a goal into steps, with approvals for sensitive actions. | [AI Chat guide](/en/guide/ai-chat) |
| **Excel Splitter** | Splits workbooks by sheet, by column value, or by complex rules. Ships as a plugin with six AI tools. | [Excel plugin](/en/plugins/official-excel) |
| **Markdown Editor** | Edit and preview Markdown in the built-in editor. | [Features home](/en/) |
| **Plugin Marketplace** | Browse, install, update, and manage `.fyp` plugin packages — JSON-RPC workers and micro-frontend UIs. | [Marketplace](/en/plugins/marketplace) |
| **Multi-Database** | First-launch wizard picks H2, SQLite, MySQL, or PostgreSQL. Passwords are AES-GCM encrypted. | [Database guide](/en/guide/database) |
| **Internationalization** | English-first docs and a localized Vue UI via `vue-i18n`. | [Features home](/en/) |
| **Dark / Light theme** | Material Design 3 theming with dark and light modes, shared with plugin micro-frontends. | [Design System](/en/design-system) |

## Plugin model

Plugins are self-contained `.fyp` packages: a `manifest.json`, a `ui/` micro-frontend, and a `backend/worker.jar` that speaks JSON-RPC 2.0 to the host. The marketplace installs, updates, and removes them. See the [Plugin Marketplace](/en/plugins/marketplace) page for details.

## Next steps

- [Quick Start](/en/quickstart) — build and run from source.
- [Architecture overview](/en/architecture/overview) — how the pieces connect.
