---
title: Features
description: What Infinia 4.0.0 orchestrates, at a glance.
lang: en
---

# Features

**Infinia** (蜂语 / FengYu) is an *AI-native orchestration platform*. A plan-and-execute
Agent turns natural-language goals into multi-step business workflows by orchestrating
three extension surfaces — `.fyp` plugins, `.fys` skills, and in-process AI tools — on
top of a headless Spring Boot backend, a Vue 3 + Vuetify 3 UI, and an Electron desktop shell.

## How orchestration works

The Agent is the spine of the platform. Every business flow follows the same loop:

1. **Describe the goal.** You state a business objective in natural language in the chat.
2. **Plan & call.** The Agent decomposes the goal into steps and, for each step, calls the
   best-fit extension surface — a `.fyp` plugin for a concrete capability, a `.fys` skill
   for domain procedure/knowledge, or an in-process AI tool.
3. **Confirm sensitive actions.** Steps that touch the outside world (sending email, writing
   files, mutating data) require your explicit approval before they run. Results flow back
   into the conversation, and the Agent re-plans on failure.

```
you → Agent plans → .fyp plugin / .fys skill / AI tool → (confirm if sensitive) → result
                  ↑──────────── re-plan on failure ────────────┘
```

## Three extension surfaces

The Agent orchestrates three distinct, intentionally separate surfaces:

| Surface | What it is | What the Agent uses it for |
| --- | --- | --- |
| [**Plugins**](/en/plugins/overview) (`.fyp`) | Isolated packages: a JSON-RPC worker + a micro-frontend UI. | Concrete capabilities — file processing, email, data tools — installed from the [marketplace](/en/plugins/marketplace). |
| [**Skills**](/en/skills/) (`.fys`) | Codex-style progressive-disclosure packages. | Domain knowledge and step-by-step procedures, loaded on demand so context stays small. |
| [**AI tools**](/en/plugins/ai-tools) | In-process Spring AI `ToolCallback` beans. | Lightweight operations the model can call directly during a chat. |

## Capability matrix

| Feature | What it does | Learn more |
| --- | --- | --- |
| **AI Agent** | Plan-and-execute agent that decomposes a goal into steps, with approvals for sensitive actions. | [AI Agent guide](/en/guide/ai-agent) |
| **AI Chat** | Multi-backend chat with streaming (SSE). Supports Ollama, OpenAI, Anthropic, and DeepSeek backends. | [AI Chat guide](/en/guide/ai-chat) |
| **Plugin Marketplace** | Browse, install, update, and manage `.fyp` plugin packages — JSON-RPC workers and micro-frontend UIs. | [Marketplace](/en/plugins/marketplace) |
| **Skills** | Progressive-disclosure `.fys` packages that feed the Agent on-demand domain procedures. | [Skills](/en/skills/) |
| **Excel Splitter** | Splits workbooks by sheet, by column value, or by complex rules. Ships as a plugin with six AI tools. | [Excel plugin](/en/plugins/official-excel) |
| **Email Center** | Multi-account confirmed SMTP sending, address books, manual IMAP collection, archives, and nine AI tools. | [Email Center](/en/plugins/email-center) |
| **Markdown Editor** | Edit and preview Markdown in the built-in editor. | [Markdown plugin](/en/plugins/official-markdown) |
| **Offline Python Builder** | Build air-gap-ready Python wheelhouses (full dependency resolution via `pip download`) as an async job, with verify and deploy. | [Offline Python](/en/plugins/official-offlinepython) |
| **Browser Agent** | Drive a real Chromium via Playwright — navigate, click, type, scrape, screenshot, and eval JS through nine AI tools. | [Browser Agent](/en/plugins/official-browser) |
| **Multi-Database** | First-launch wizard picks H2, SQLite, MySQL, or PostgreSQL. Passwords are AES-GCM encrypted. | [Database guide](/en/guide/database) |
| **Internationalization** | English-first docs and a localized Vue UI via `vue-i18n`. | [Design System](/en/design-system) |
| **Dark / Light theme** | Material Design 3 theming with dark and light modes, shared with plugin micro-frontends. | [Design System](/en/design-system) |

## Next steps

- [Quick Start](/en/quickstart) — build and run from source.
- [Architecture overview](/en/architecture/overview) — how the pieces connect.
- [AI Agent guide](/en/guide/ai-agent) — the plan-and-execute loop in detail.
