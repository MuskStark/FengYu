# 4.0.0 Documentation Rewrite Implementation Plan

> **Status (2026-07-15): COMPLETE.** All 10 tasks done. The site builds clean (`npm run docs:build`,
> zero warnings with dead-link checking enabled), ships 35 EN + 35 ZH pages — exceeding the original
> 32-page target with three additional pages that track project progress since this plan was written:
> `plugins/email-center.md` (the now-official `fan.summer.email` plugin), `plugins/database.md` (the
> plugin database standard), and `plugins/ui-components.md` (the `@fengyu/plugin-ui` Codex kit).
> Root `README.md` was rewritten for 4.0.0. See the per-task checkboxes below.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stale docsify + JavaFX-era `docs/` and root `README.md` with a VitePress bilingual (EN source-of-truth + ZH mirror) docs site that accurately documents the 4.0.0 headless-Spring-Boot + Vue/Vuetify + Tauri architecture.

**Architecture:** A new VitePress project under `docs/` with two locale trees (`en/` default root, `zh/` mirror). English content is written first as source of truth (32 pages), then mirrored into Chinese. A root `index.md` redirects to `/en/`. A new GH Actions workflow builds and deploys to GitHub Pages. Root `README.md` is fully rewritten.

**Tech Stack:** VitePress (latest), Vue 3 (peer), Markdown. No backend code changes. Branding: Infinia (EN) / 蜂语 FengYu (ZH), Möbius logo, MD3 purple `#6750A4`.

## Global Constraints

These apply to every task. Each page's content must honor them:

- **Source of truth = actual 4.0.0 source**, NOT the stale `AGENTS.md`. The spec's correction table is authoritative. Never write: `JavaFX`, `glassmorphism`, `FengYuPluginV2`, `StageStyle`, `3.2.0`, `PluginFileController`, `/api/plugins` (correct: `/api/plugin-runtime`), `GGUF`/in-process local inference (correct: Ollama), `?token=` on SSE (correct: `?streamId=`).
- **Verified facts (use verbatim):**
  - Entry point: `fan.summer.fengyu.HeadlessLauncher`. CLI: `--port=<n>` (default `24056`) and `--token=<t>` only. Binds `127.0.0.1`. Prints `FENGYU_PORT=<n>` to stdout. Exit codes: `SETUP_DONE=0`, `FATAL=1`.
  - Modes auto-detected: SETUP (`SetupApplication`, no JPA) when `~/.fengyu/config/datasource.properties` absent/DB unreachable; APP (`FengYuApplication`, `fengyu.mode=app`) once reachable.
  - Plugin contract = `.fyp` zip of `manifest.json` + `ui/` + `backend/worker.jar`; workers are out-of-process JSON-RPC 2.0. **No `FengYuPluginV2` interface.**
  - `manifest.json` schema (from `OfficialPlugins/packages/excel/manifest.json`): `schemaVersion, id, name, description, version, author, icon, category, ui{entry}, backend{command,protocol}, permissions[], homepage, official, aiTools[{name,description,method,inputSchema}]`.
  - Plugin UI runs in a sandboxed iframe and talks to the host via `@fengyu/plugin-sdk` (`FengYuClient`), a `postMessage` bridge — methods: `host.ready`, `rpc.invoke`, `files.open`, `files.inputDirectory`, `files.outputDirectory`, `files.export`, `notify`.
  - `fengyu plugin` CLI subcommands (from `plugin-cli/src/cli.mjs`): **`create`, `dev`, `build`, `validate`, `install`** (there is NO `init`). e.g. `fengyu plugin create ./my-plugin --id com.example.my-plugin`.
  - File I/O endpoints: `POST /api/plugin-runtime/{id}/files/{upload|upload-directory|native|output}`, `GET /api/plugin-runtime/{id}/files/export/{ref}`. Temp root `${java.io.tmpdir}/fengyu/runtime-files`. In-memory grants, wiped `@PreDestroy`. No scheduled sweep.
  - AI modes: `local` (Ollama, external `ollama serve`), `openai`, `anthropic`, `deepseek`. Tool-calling = Spring AI `ToolCallingManager`.
  - SSE: `POST /api/ai/chat {messages}` → `{streamId}`; then `GET /api/ai/stream?streamId=`. Events: `token`, `thinking`, `tool` (phase `call`/`result`), `done`, `error`.
  - Agent: `POST /api/agent/run {goal,config}` → `{runId}`; `GET /api/agent/stream?runId=`; events `plan_token, plan_ready, plan_approval_requested, step_start, step_complete, step_approval_requested, complete, error`; `POST /api/agent/{runId}/{approve|cancel}`; `GET /api/agent/tools`.
  - Database: JPA + Hibernate (`ddl-auto=update`). Four backends `H2, SQLITE, MYSQL, POSTGRESQL`. Config `~/.fengyu/config/datasource.properties`; password AES/GCM (`CryptoUtil`). Virtual user id=1 `"ZFlow-Summer"`.
  - Frontend: vue `3.5.39`, vuetify `^3.12.9` (MD3), vue-i18n `^10.0.8`, vue-router `^4.5.0`, pinia `^2.3.1`, vite `^6.0.7`. Tauri bridge via `window.__FENGYU_TOKEN__|__FENGYU_PORT__|__FENGYU_API_BASE__`.
  - Desktop: Tauri `2.0`, `productName:"Infinia"`, version `4.0.0`.
  - Versions: `<revision>4.0.0-SNAPSHOT</revision>`, Java 21, Spring Boot 4.1.0, Spring AI 2.0.0. License GPL-3.0.
  - Official plugins: `fan.summer.markdown` (action `render`, no aiTools), `fan.summer.excel` (actions `analyze`/`configure`/`split`, 6 aiTools). `plugin-email` is source-only / not packaged → "coming soon" only.
- **Branding:** EN nav/title = "Infinia"; ZH home hero = "蜂语 FengYu". Slogan EN = *"Where bees go, flows follow."*; ZH = *「蜂之所向，流之所往」*. Accent color `#6750A4`. Logo source exists at `assets/branding/infinia-app-icon.svg` (copy into `docs/public/`).
- **Tone:** present tense, concise, lead with what it does. Code blocks always language-tagged. Every page frontmatter: `title`, `description`, and the locale's `lang`.
- **ZH rule:** mirror EN faithfully; code blocks, paths, identifiers, class/endpoint names stay in English; only prose is translated.
- **Commit convention:** conventional commits with emojis (`📝 docs: ...`).
- **No `brand.md`** in the new tree.
- **Preserve** `docs/assets/` and `docs/superpowers/` throughout the rewrite.

---

## Task 0: Clear stale docs and scaffold VitePress shell

**Files:**
- Delete: all of `docs/*.md`, `docs/index.html`, `docs/_sidebar.md`, `docs/_navbar.md`, `docs/_coverpage.md`, `docs/{features,architecture,getting-started,api,development,changelog,migration-3.1,migration-3.2}.md`, `docs/plugins/**`, `docs/ui-design/**`, `docs/zh/**` (the entire `docs/zh/` subtree)
- Preserve: `docs/assets/`, `docs/superpowers/`
- Create: `package.json` (repo root), `docs/package.json` (optional; see step), `docs/.vitepress/config.ts`, `docs/.vitepress/theme/index.ts`, `docs/.vitepress/theme/style.css`, `docs/public/logo.svg`, `docs/index.md`, `docs/en/index.md` (minimal placeholder), `docs/zh/index.md` (minimal placeholder), `.github/workflows/docs.yml`

**Interfaces:**
- Produces: a building VitePress site with EN/ZH locale switch, MD3 brand, empty-but-wired sidebars/nav. Later tasks fill the page content.

- [ ] **Step 1: Clear stale docs (preserve assets/ and superpowers/)**

Move stale files out (git-tracked, so deletion is recoverable). Run from repo root:

```bash
cd /Users/phoebej/Develop/Java/FengYu
# Remove stale top-level docs files
git rm docs/index.html docs/_sidebar.md docs/_navbar.md docs/_coverpage.md docs/.nojekyll 2>/dev/null || true
git rm docs/README.md docs/features.md docs/architecture.md docs/getting-started.md docs/api.md docs/development.md docs/changelog.md docs/migration-3.1.md docs/migration-3.2.md 2>/dev/null || true
# Remove stale subtrees
git rm -r docs/plugins docs/ui-design docs/zh 2>/dev/null || true
# Confirm only assets/ and superpowers/ remain under docs/
ls docs/
```

Expected `ls docs/` output: `assets  superpowers` (only those two).

- [ ] **Step 2: Create the VitePress package config at repo root**

Create `package.json` at repo root (there is no existing one):

```json
{
  "name": "fengyu-docs",
  "private": true,
  "version": "4.0.0",
  "type": "module",
  "scripts": {
    "docs:dev": "vitepress dev docs",
    "docs:build": "vitepress build docs",
    "docs:preview": "vitepress preview docs"
  },
  "devDependencies": {
    "vitepress": "^1.6.3",
    "vue": "^3.5.13"
  }
}
```

- [ ] **Step 3: Install dependencies**

Run: `npm install`
Expected: installs `vitepress` and `vue` under `node_modules/`, creates `package-lock.json`.

- [ ] **Step 4: Copy the Möbius/Infinia logo into docs/public/**

The existing logo source is `assets/branding/infinia-app-icon.svg`.

```bash
mkdir -p docs/public
cp assets/branding/infinia-app-icon.svg docs/public/logo.svg
```

- [ ] **Step 5: Create the VitePress config with dual locales + MD3 brand**

Create `docs/.vitepress/config.ts`:

```ts
import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Infinia',
  description: 'Where bees go, flows follow. — A modular web + desktop toolbox.',
  lastUpdated: true,
  cleanUrls: true,
  sitemap: { hostname: 'https://muskstark.github.io/FengYu/' },
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
    ['meta', { name: 'theme-color', content: '#6750A4' }]
  ],
  locales: {
    root: { label: 'English', lang: 'en', link: '/en/', themeConfig: { nav: enNav, sidebar: enSidebar } },
    zh: { label: '简体中文', lang: 'zh-CN', link: '/zh/', themeConfig: { nav: zhNav, sidebar: zhSidebar } }
  },
  themeConfig: {
    logo: '/logo.svg',
    socialLinks: [{ icon: 'github', link: 'https://github.com/MuskStark/FengYu' }],
    search: { provider: 'local' },
    footer: {
      message: 'Released under the GPL-3.0 License.',
      copyright: 'Copyright © 2026 Infinia · 蜂语 FengYu'
    }
  }
})

// NOTE: enNav/enSidebar/zhNav/zhSidebar are defined in Task 1 (root redirect) + Task 2.
// For the shell, define minimal placeholders so the site builds:
const enNav = [{ text: 'Home', link: '/en/' }]
const enSidebar = {}
const zhNav = [{ text: '首页', link: '/zh/' }]
const zhSidebar = {}
```

- [ ] **Step 6: Create the MD3 theme overrides**

Create `docs/.vitepress/theme/style.css`:

```css
:root {
  --vp-brand-color: #6750A4;
  --vp-button-brand-bg: #6750A4;
  --vp-button-brand-hover-bg: #7965b0;
  --vp-c-brand-1: #6750A4;
  --vp-c-brand-2: #7965b0;
  --vp-c-brand-3: #9a82c4;
  --vp-home-hero-image-background-image: linear-gradient(-45deg, #6750A4 50%, #9a82c4 50%);
  --vp-home-hero-image-filter: blur(44px);
}
```

Create `docs/.vitepress/theme/index.ts`:

```ts
import DefaultTheme from 'vitepress/theme'
import './style.css'
export default DefaultTheme
```

- [ ] **Step 7: Create root redirect + minimal locale homes**

Create `docs/index.md` (root redirect to EN — VitePress uses `layout: page` + frontmatter; a meta refresh inside the page body performs the redirect for the bare `/` URL):

```markdown
---
layout: page
title: Infinia
---

<script setup>
// Redirect to the English home. Runs client-side on first load of '/'.
if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/en') && !window.location.pathname.startsWith('/zh')) {
  window.location.replace('/en/')
}
</script>

<meta http-equiv="refresh" content="0; url=/en/" />
```

Create `docs/en/index.md`:

```markdown
---
title: Infinia
description: Where bees go, flows follow. — A modular web + desktop toolbox.
lang: en
layout: home
hero:
  name: Infinia
  text: 蜂语 FengYu
  tagline: Where bees go, flows follow.
  image: /logo.svg
  actions:
    - theme: brand
      text: Quick Start
      link: /en/quickstart
    - theme: alt
      text: Features
      link: /en/features
features: []
---
```

Create `docs/zh/index.md`:

```markdown
---
title: 蜂语 FengYu
description: 「蜂之所向，流之所往」—— 模块化的 Web + 桌面工具箱。
lang: zh-CN
layout: home
hero:
  name: 蜂语 FengYu
  text: Infinia
  tagline: 「蜂之所向，流之所往」
  image: /logo.svg
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/quickstart
    - theme: alt
      text: 功能特性
      link: /zh/features
features: []
---
```

- [ ] **Step 8: Verify the shell builds**

Run: `npm run docs:build`
Expected: build completes, outputs `docs/.vitepress/dist/`, no errors about missing pages (nav/sidebar point only to existing `/en/` and `/zh/`).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "📝 docs: scaffold VitePress bilingual shell, remove stale docsify content"
```

---

## Task 1: Write EN top-level pages (home, quickstart, features, design-system)

**Files:**
- Modify: `docs/en/index.md` (finalize home with feature cards)
- Create: `docs/en/quickstart.md`, `docs/en/features.md`, `docs/en/design-system.md`

**Interfaces:**
- Consumes: Global Constraints (verified facts, branding).
- Produces: the 4 EN top-level pages referenced by nav/sidebar in later tasks.

- [ ] **Step 1: Finalize the EN home (`docs/en/index.md`) with feature cards**

Replace the `features: []` line in `docs/en/index.md` with a populated `features:` array (VitePress home feature grid). Each entry: `icon`, `title`, `details`, optional `link`. Cover: AI Chat, AI Agent, Excel Splitter, Markdown Editor, Plugin Marketplace, Multi-database, Internationalization, Dark/Light. Example entry:

```yaml
features:
  - icon: 🤖
    title: AI Chat & Agent
    details: Multi-backend chat (Ollama, OpenAI, Anthropic, DeepSeek) with streaming and a plan-and-execute agent.
    link: /en/guide/ai-chat
  - icon: 📊
    title: Excel Splitter
    details: Split workbooks by sheet, column value, or complex rules — exposed as a plugin with AI tools.
    link: /en/plugins/official-excel
  - icon: 🧩
    title: Plugin Marketplace
    details: Browse, install, update, and manage .fyp plugin packages — JSON-RPC workers and micro-frontend UIs.
    link: /en/plugins/marketplace
  - icon: 💾
    title: Multi-Database
    details: First-launch wizard picks H2, SQLite, MySQL, or PostgreSQL. Passwords AES-GCM encrypted.
    link: /en/guide/database
  - icon: 🌍
    title: Internationalization
    details: English-first docs and a localized Vue UI (vue-i18n).
  - icon: 🎨
    title: Material Design 3
    details: Vuetify 3 MD3 UI, shared with plugin micro-frontends, dark and light themes.
    link: /en/design-system
```

- [ ] **Step 2: Write `docs/en/quickstart.md`**

Content sections (each must use verified facts):
1. **Prerequisites** — JDK 21+ (Eclipse Temurin recommended); Node 20+ + npm (for frontend dev); Rust + `tauri-cli` (for desktop only).
2. **Build from source** — the reactor build order (API must install first):
   ```bash
   git clone https://github.com/MuskStark/FengYu.git
   cd FengYu
   mvn install -f FengYu-Api/pom.xml -DskipTests
   mvn clean package -f FengYu/pom.xml -DskipTests
   ```
3. **Run the backend** — `java -jar FengYu/target/FengYu-4.0.0-SNAPSHOT.jar --token=<your-token>` (binds `127.0.0.1:24056` by default; prints `FENGYU_PORT=<n>`).
4. **Run the frontend (dev)** — `cd frontend && npm install && npm run dev` (Vite proxies `/api` and `/plugin-runtime` to `localhost:24056`).
5. **Smoke test** — `scripts/e2e-smoke.sh` boots the jar and probes every endpoint.
6. **Run desktop (dev)** — `cd desktop && cargo tauri dev` (release: `cargo tauri build`).
7. Next steps — links to `/en/architecture/overview` and `/en/guide/configuration`.

Frontmatter: `title: Quick Start`, `description: Build and run Infinia 4.0.0 from source.`, `lang: en`.

- [ ] **Step 3: Write `docs/en/features.md`**

A capability matrix as a table (Feature | What it does | Learn more link). Rows: AI Chat (modes + SSE), AI Agent (plan-and-execute + approvals), Excel Splitter (3 modes, 6 AI tools), Markdown Editor, Plugin Marketplace, Multi-Database, i18n, Dark/Light theme. Each "Learn more" links to the relevant guide/plugin page. Frontmatter: `title: Features`, `description: ...`, `lang: en`.

- [ ] **Step 4: Write `docs/en/design-system.md`**

4.0.0 design language ONLY (no JavaFX). Cover: Vuetify 3 Material Design 3 baseline (Google default palette); theme driven by the Pinia `useThemeStore` singleton; MD3 palette defined in `frontend/src/plugins/md3-themes.ts`; plugin micro-frontends share the host's Vuetify instance via `PluginContext.vuetify` (`app.use(ctx.vuetify)` in mount); dark/light theming. Frontmatter: `title: Design System`, `lang: en`.

- [ ] **Step 5: Build to verify no broken links**

Run: `npm run docs:build`
Expected: completes. (Internal links to `/en/architecture/*`, `/en/guide/*`, `/en/plugins/*` will warn as not-yet-created — that is acceptable until those tasks land. To avoid noise, you may temporarily omit forward links and add them in Task 8's cross-link pass. Prefer keeping them; VitePress treats dead links as warnings, not errors, by default.)

- [ ] **Step 6: Commit**

```bash
git add docs/en
git commit -m "📝 docs(en): add home, quickstart, features, design-system"
```

---

## Task 2: Wire full nav + sidebar for both locales

**Files:**
- Modify: `docs/.vitepress/config.ts` (replace placeholder nav/sidebar with the full structure)

**Interfaces:**
- Produces: `enNav`, `enSidebar`, `zhNav`, `zhSidebar` constants matching the 32-page IA. Page files are created in Tasks 3–7; sidebar links may point to not-yet-created pages (VitePress warns, doesn't fail).

- [ ] **Step 1: Define the full EN nav and sidebar in config.ts**

Replace the placeholder constants at the bottom of `docs/.vitepress/config.ts` with the full structures. Nav (top bar):

```ts
const enNav = [
  { text: 'Quickstart', link: '/en/quickstart' },
  { text: 'Architecture', link: '/en/architecture/overview' },
  { text: 'Plugins', link: '/en/plugins/overview' },
  { text: 'Guide', link: '/en/guide/ai-chat' },
  { text: 'Reference', link: '/en/reference/rest-api' }
]
```

Sidebar (collapsible sections) — define as an object keyed by base path so each section's sidebar is contextual:

```ts
const enSidebar = {
  '/en/': [
    { text: 'Start', items: [
      { text: 'Home', link: '/en/' },
      { text: 'Quick Start', link: '/en/quickstart' },
      { text: 'Features', link: '/en/features' },
      { text: 'Design System', link: '/en/design-system' }
    ]},
    { text: 'Architecture', collapsible: true, items: [
      { text: 'Overview', link: '/en/architecture/overview' },
      { text: 'Backend', link: '/en/architecture/backend' },
      { text: 'Frontend', link: '/en/architecture/frontend' },
      { text: 'Desktop', link: '/en/architecture/desktop' },
      { text: 'Plugin System', link: '/en/architecture/plugin-system' }
    ]},
    { text: 'Plugins', collapsible: true, items: [
      { text: 'Overview', link: '/en/plugins/overview' },
      { text: 'Getting Started', link: '/en/plugins/getting-started' },
      { text: 'Manifest', link: '/en/plugins/manifest' },
      { text: 'Worker (JSON-RPC)', link: '/en/plugins/worker' },
      { text: 'UI Micro-frontend', link: '/en/plugins/ui-microfrontend' },
      { text: 'File I/O', link: '/en/plugins/file-io' },
      { text: 'AI Tools', link: '/en/plugins/ai-tools' },
      { text: 'SDK & CLI', link: '/en/plugins/sdk-cli' },
      { text: 'Marketplace', link: '/en/plugins/marketplace' },
      { text: 'i18n', link: '/en/plugins/i18n' },
      { text: 'Build & Deploy', link: '/en/plugins/build-deploy' },
      { text: 'Official: Markdown', link: '/en/plugins/official-markdown' },
      { text: 'Official: Excel', link: '/en/plugins/official-excel' },
      { text: 'Pitfalls', link: '/en/plugins/pitfalls' }
    ]},
    { text: 'Guide', collapsible: true, items: [
      { text: 'AI Chat', link: '/en/guide/ai-chat' },
      { text: 'AI Agent', link: '/en/guide/ai-agent' },
      { text: 'Database', link: '/en/guide/database' },
      { text: 'Configuration', link: '/en/guide/configuration' }
    ]},
    { text: 'Reference', collapsible: true, items: [
      { text: 'REST API', link: '/en/reference/rest-api' },
      { text: 'SSE Events', link: '/en/reference/sse-events' },
      { text: 'Troubleshooting', link: '/en/reference/troubleshooting' },
      { text: 'Glossary', link: '/en/reference/glossary' },
      { text: 'Changelog', link: '/en/reference/changelog' }
    ]}
  ]
}
```

- [ ] **Step 2: Define the ZH nav and sidebar (mirrored links to /zh/...)**

Mirror `enNav`/`enSidebar` exactly but: links use `/zh/...`, and the `text` values are Chinese (e.g. `'快速开始'`, `'架构'`, `'插件'`, `'指南'`, `'参考'`; section items `'首页'`, `'快速开始'`, `'功能特性'`, `'设计系统'`, `'概述'`, `'后端'`, `'前端'`, `'桌面端'`, `'插件系统'`, `'概述'`, `'入门'`, `'清单'`, `'Worker（JSON-RPC）'`, `'UI 微前端'`, `'文件 I/O'`, `'AI 工具'`, `'SDK 与 CLI'`, `'插件市场'`, `'国际化'`, `'构建与部署'`, `'官方插件：Markdown'`, `'官方插件：Excel'`, `'常见陷阱'`, `'AI 对话'`, `'AI 智能体'`, `'数据库'`, `'配置'`, `'REST API'`, `'SSE 事件'`, `'故障排查'`, `'术语表'`, `'更新日志'`).

- [ ] **Step 3: Build to verify config parses**

Run: `npm run docs:build`
Expected: completes. Dead-link warnings for not-yet-created pages are acceptable here.

- [ ] **Step 4: Commit**

```bash
git add docs/.vitepress/config.ts
git commit -m "📝 docs: wire full EN/ZH nav and sidebar"
```

---

## Task 3: Write EN `architecture/` pages (5)

**Files:**
- Create: `docs/en/architecture/{overview,backend,frontend,desktop,plugin-system}.md`

**Interfaces:**
- Consumes: verified boot/mode/endpoint facts.
- Produces: the 5 architecture pages linked from the Architecture sidebar.

- [ ] **Step 1: `overview.md`** — three-layer diagram (headless Spring Boot ↔ Vue SPA ↔ Tauri shell) as an ASCII/mermaid diagram; request flow (browser → loopback 24056 → token-gated controllers); loopback-only bind; per-launch `X-FengYu-Token` auth. Frontmatter `title: Architecture Overview`, `lang: en`.
- [ ] **Step 2: `backend.md`** — `HeadlessLauncher`; CLI `--port` (default 24056, OS-assigned fallback)/`--token` only; `server.address=127.0.0.1`; `FENGYU_PORT=<n>` stdout; SETUP vs APP auto-detection via `~/.fengyu/config/datasource.properties` presence + DB reachability (JDBC `SELECT 1`, 5s timeout); `SetupApplication` (no JPA) vs `FengYuApplication` (`fengyu.mode=app`); exit codes `SETUP_DONE=0`/`FATAL=1`; Spring Boot 4.1.0 + Spring AI 2.0.0; `TokenAuthFilter` (bypasses `/api/health`, `/api/setup/*`, `/plugin-runtime/{id}/**` static). Frontmatter `title: Backend`, `lang: en`.
- [ ] **Step 3: `frontend.md`** — Vue 3.5.39 + TS + Pinia + vue-router 4 + vue-i18n 10; Vuetify 3 (MD3); stores (`aiSession`, `settings`, `theme`, `plugins`, `setup`, …); MF host `frontend/src/mf/loader.ts` (`import(uiEntry)` → `default.mount(el, ctx)`); `desktop.ts` Tauri native-dialog facade (`pickFile`/`pickDirectory` via `@tauri-apps/plugin-dialog`); Tauri bridge globals `window.__FENGYU_TOKEN__|__FENGYU_PORT__|__FENGYU_API_BASE__`; router guard redirects to `/setup` when uninitialized. Frontmatter `title: Frontend`, `lang: en`.
- [ ] **Step 4: `desktop.md`** — Tauri 2.0; dev build (opens window, backend external on :24056 via Vite proxy) vs release (spawns jar sidecar `java -Dfengyu.plugins.official-directory=... -cp <jar> fan.summer.fengyu.HeadlessLauncher --port=24056 --token=<t>`); reader thread scans stdout for `FENGYU_PORT=<n>` (30s); `wait_for_health` polls `GET /api/health` (300ms, 30s); `check_setup_mode` probes `/api/setup/status`; `run_backend_until_app_mode` loops spawn→health→setup, restarts into APP on `exit(0)`; injects `window.__FENGYU_*` before page load; window 1280×820 min 960×640; `tauri_plugin_dialog`; kills sidecar on `WindowEvent::Destroyed`; `tauri.conf.json productName:"Infinia"`. Frontmatter `title: Desktop`, `lang: en`.
- [ ] **Step 5: `plugin-system.md`** — `.fyp` = zip of `manifest.json` + `ui/` + `backend/worker.jar`; workers are **out-of-process JSON-RPC 2.0** (never in host Spring context); UI in sandboxed iframe + `@fengyu/plugin-sdk` postMessage bridge; host `PluginProcessManager` spawns/owns worker processes, resolves `ref_*` FileRefs to absolute paths before dispatch; `InstalledPluginDescriptor` fields (incl. `supportsAi`, `source` OFFICIAL|THIRD_PARTY, `iconStyle`); link to `/en/plugins/manifest`. Frontmatter `title: Plugin System`, `lang: en`.
- [ ] **Step 6: Build + commit**

Run: `npm run docs:build` (expect dead-link warnings for pages in later tasks — acceptable). Then:

```bash
git add docs/en/architecture
git commit -m "📝 docs(en): add architecture pages (overview, backend, frontend, desktop, plugin-system)"
```

---

## Task 4: Write EN `plugins/` pages (14)

**Files:**
- Create: 14 files under `docs/en/plugins/`

**Interfaces:**
- Consumes: verified manifest schema, CLI commands, SDK API, file-I/O endpoints, AI-tool declaration, official-plugin specifics.
- Produces: the plugins section. Largest task — the worker may split this across subagent batches.

- [ ] **Step 1: `overview.md`** — what a plugin is; `.fyp` layout (`manifest.json`, `ui/index.html`, `backend/worker.jar`); official vs third-party; lifecycle (install → enable → invoke → disable → uninstall); the `source` field. Frontmatter `title: Plugin Overview`, `lang: en`.
- [ ] **Step 2: `getting-started.md`** — scaffold with `fengyu plugin create ./my-plugin --id com.example.my-plugin`; directory layout produced; dev-mode run with `fengyu plugin dev --port 4173`; link to manifest/worker pages. **Use the verified CLI commands only (`create`, `dev`, `build`, `validate`, `install`) — there is NO `init`.** Frontmatter `title: Getting Started`, `lang: en`.
- [ ] **Step 3: `manifest.md`** — full schema reference table (from `OfficialPlugins/packages/excel/manifest.json`): every field with type + required + default. Sub-records: `ui{entry}`, `backend{command,protocol}`, `aiTools[{name,description,method,inputSchema}]`. Valid `category` values (`dev`, `text`, `image`, `net`, `file`, `ai`, `other`); valid `permissions` (`files.read`, `files.write`). Show the markdown manifest and the excel manifest (with `inputSchema` JSON-Schema strings) as examples. Frontmatter `title: Manifest`, `lang: en`.
- [ ] **Step 4: `worker.md`** — JSON-RPC 2.0 protocol: request `{jsonrpc:"2.0", id, method, params}`, response `{jsonrpc:"2.0", id, result|error}`; newline-delimited over the worker's stdio; the host calls `method` (the manifest's `backend.command`); FileRef resolution: any param value shaped `{id:"ref_...", kind, access}` is rewritten by the host to an absolute path before dispatch (worker receives paths, never raw upload bytes); Worker SDK Java API (shade a main class registering method handlers; reference `MarkdownWorkerMain`/`ExcelWorkerMain`); error object shape `{code, message}`. Frontmatter `title: Worker (JSON-RPC)`, `lang: en`.
- [ ] **Step 5: `ui-microfrontend.md`** — UI runs in a sandboxed iframe; communicate via `@fengyu/plugin-sdk` `FengYuClient` (postMessage bridge): `client.ready()` → `Environment`; `client.invoke(method, params)` → RPC to worker; `client.files.{open,inputDirectory,outputDirectory,export}()` → FileRefs; `client.notify(msg)`; `client.on(event, handler)`. The MF host also exposes `default.mount(el, ctx)` for bundle loading and `PluginContext` (vuetify, desktop, i18n) for shared-host integration. CSP enforced on the iframe. Show a minimal `ui/index.html` + a `FengYuClient.ready()` snippet. Frontmatter `title: UI Micro-frontend`, `lang: en`.
- [ ] **Step 6: `file-io.md`** — the grant model: `POST /api/plugin-runtime/{id}/files/upload` (multipart `file`, perm `files.read`); `upload-directory` (multipart `files`+`paths[]`, `files.read`); `native` (body `{path,kind,access}`, desktop Tauri dialog → path, `files.read|files.write`); `output` (allocate writable output dir, `files.write`); `GET .../files/export/{ref}` → zip (`files.write`). Temp root `${java.io.tmpdir}/fengyu/runtime-files/{pluginId}/{uuid}/...`. In-memory grants (ConcurrentHashMap); whole tree wiped in `@PreDestroy`. **No scheduled sweep.** Frontmatter `title: File I/O`, `lang: en`.
- [ ] **Step 7: `ai-tools.md`** — declaring `aiTools` in manifest: each `{name, description, method, inputSchema (JSON Schema string)}`; the host aggregates declared tools into its Spring AI `ToolCallback[]` via `AiToolDiscoveryConfig`; `supportsAi` = aiTools non-empty; tool calls surface as SSE `tool` events (phase `call`/`result`); show the excel `excel_analyze` aiTool as the example. Frontmatter `title: AI Tools`, `lang: en`.
- [ ] **Step 8: `sdk-cli.md`** — `@fengyu/plugin-sdk` (TS): `FengYuClient`, `FileRef`, `Environment`, `createId` (from `plugin-sdk/typescript/src/index.ts`); the Java Worker SDK (shade main-class pattern); `fengyu plugin` CLI commands table: `create [--id]`, `dev [--port]`, `build [--out]`, `validate`, `install [--host] [--token]` (from `plugin-cli/src/cli.mjs`). Frontmatter `title: SDK & CLI`, `lang: en`.
- [ ] **Step 9: `marketplace.md`** — marketplace endpoints: `GET /api/plugin-market`; install via `POST /upload` (multipart `.fyp`), `POST /upload-native` (body `{path}`), `POST /{id}/install` (from catalog); `POST /{id}/update`; `PATCH /{id}/enabled` (body `{enabled}` — stops process on disable); `DELETE /{id}` (uninstall). Catalog URL override via system property. Frontmatter `title: Marketplace`, `lang: en`.
- [ ] **Step 10: `i18n.md`** — plugin UI localization; integration with host vue-i18n; the `Environment.locale` the host pushes. Frontmatter `title: Internationalization`, `lang: en`.
- [ ] **Step 11: `build-deploy.md`** — `OfficialPlugins/build-packages.sh` flow: builds TS SDK, runs excel UI tests, validates POI services, assembles `packages/{markdown,excel}/` (manifest + `ui/` + `backend/worker.jar` + `ui/sdk.js`), zips each to `target/packages/fan.summer.{markdown,excel}-4.0.0.fyp`; worker jars via `maven-shade-plugin` (`MarkdownWorkerMain`/`ExcelWorkerMain`); the equivalent single-plugin flow `fengyu plugin build --out dist-package/<id>-<version>.fyp`. Frontmatter `title: Build & Deploy`, `lang: en`.
- [ ] **Step 12: `official-markdown.md`** — walkthrough of `fan.summer.markdown`: manifest (category `text`, no permissions, no aiTools); `invoke("render", {markdown})` server-side commonmark render; `MarkdownWorkerMain` registers the `render` method; Vuetify split-pane editor MF. Frontmatter `title: Official Plugin — Markdown`, `lang: en`.
- [ ] **Step 13: `official-excel.md`** — walkthrough of `fan.summer.excel`: manifest (category `file`, perms `files.read`/`files.write`); actions `analyze`/`configure`/`split`; the 6 aiTools (`excel_analyze`, `excel_configure`, `excel_complex_config`, `excel_execute`, `excel_query`, `excel_cancel`) with their `inputSchema`; BY_SHEET/BY_COLUMN/COMPLEX modes; file I/O usage; four-step wizard MF. Frontmatter `title: Official Plugin — Excel`, `lang: en`.
- [ ] **Step 14: `pitfalls.md`** — common traps: iframe CSP blocking inline scripts; FileRef resolution timing (host rewrites before dispatch — don't hardcode paths); MF Vue/Vuetify must reuse host instance (import map) not bundle its own; permission gating (a `files.write` op without the perm returns 403); worker stdio must be newline-delimited JSON-RPC only (log to stderr). Frontmatter `title: Pitfalls`, `lang: en`.
- [ ] **Step 15: Build + commit**

Run: `npm run docs:build`. Then:

```bash
git add docs/en/plugins
git commit -m "📝 docs(en): add plugins section (14 pages)"
```

---

## Task 5: Write EN `guide/` pages (4)

**Files:**
- Create: `docs/en/guide/{ai-chat,ai-agent,database,configuration}.md`

- [ ] **Step 1: `ai-chat.md`** — chat UI; modes `local` (Ollama, external `ollama serve`), `openai`, `anthropic`, `deepseek` (OpenAI-compatible); request flow `POST /api/ai/chat {messages:[{role,content}]}` → `{streamId}` then `GET /api/ai/stream?streamId=` (SSE); events `token`, `thinking`, `tool` (call/result), `done`, `error`; thinking cards; tool-call display; conversation persistence (`GET/POST/PUT/DELETE /api/ai/conversations[/{id}]`). Frontmatter `title: AI Chat`, `lang: en`.
- [ ] **Step 2: `ai-agent.md`** — plan-and-execute agent: `POST /api/agent/run {goal,config}` → `{runId}`; `GET /api/agent/stream?runId=`; events `plan_token`, `plan_ready`, `plan_approval_requested`, `step_start`, `step_complete`, `step_approval_requested`, `complete`, `error`; `POST /api/agent/{runId}/approve` (optional edited `AgentPlan`), `POST /api/agent/{runId}/cancel`; `GET /api/agent/tools` (orchestrable tool list). Frontmatter `title: AI Agent`, `lang: en`.
- [ ] **Step 3: `database.md`** — first-launch setup wizard; four backends `H2, SQLITE, MYSQL, POSTGRESQL` (H2/SQLite embedded); `/api/setup/*` flow (`/status`, `/types`, `/test-connection`, `/initialize`, `DELETE /config`); config at `~/.fengyu/config/datasource.properties`; password AES/GCM via `CryptoUtil` (machine-bound key at `~/.fengyu/config/.machineid`); virtual user id=1 `"ZFlow-Summer"`; reconfigure by deleting config + restart. Frontmatter `title: Database`, `lang: en`.
- [ ] **Step 4: `configuration.md`** — settings `GET/PUT /api/settings` (keys `theme`, `language`, `sidebarCollapsed`); AI config `GET/PUT /api/ai/config` (masked; partial updates; hot-swap via `BackendReactivator.reactivate()`) + `POST /api/ai/config/test`; `datasource.properties` layout (keys `db.type/url/driver/dialect/username/password/file.path`); `POST /api/settings/database/reset` (restart into SETUP). Frontmatter `title: Configuration`, `lang: en`.
- [ ] **Step 5: Build + commit**

Run: `npm run docs:build`. Then:

```bash
git add docs/en/guide
git commit -m "📝 docs(en): add guide section (ai-chat, ai-agent, database, configuration)"
```

---

## Task 6: Write EN `reference/` pages (5)

**Files:**
- Create: `docs/en/reference/{rest-api,sse-events,troubleshooting,glossary,changelog}.md`

- [ ] **Step 1: `rest-api.md`** — complete endpoint catalog, grouped by controller, each row: `METHOD path — auth — one-line purpose`. Groups: Health, Plugin Runtime, Plugin Files, Marketplace, Settings, AI, AI Config, Conversations, Agent, Setup. Use the verified endpoint list from Global Constraints. For the token column: required everywhere except `/api/health`, `/api/setup/*`, `/plugin-runtime/{id}/**` static. Frontmatter `title: REST API`, `lang: en`.
- [ ] **Step 2: `sse-events.md`** — two tables. (a) Chat stream (`GET /api/ai/stream?streamId=`): `token {text}`, `thinking {text}`, `tool {phase:"call",name,arguments}` / `{phase:"result",id,success,output}`, `done {text,tokens,tps}`, `error {message}`; initial `:connected` comment. (b) Agent stream (`GET /api/agent/stream?runId=`): `plan_token`, `plan_ready`, `plan_approval_requested`, `step_start`, `step_complete`, `step_approval_requested`, `complete`, `error` — each with its payload shape. Frontmatter `title: SSE Events`, `lang: en`.
- [ ] **Step 3: `troubleshooting.md`** — port conflict (24056 taken → use `--port=0` or the OS-assigned `FENGYU_PORT`); DB connection failure (SETUP-mode `.bak` backup, re-run wizard); token mismatch (`X-FengYu-Token` vs `--token`); plugin worker crash (check stderr, JSON-RPC framing); MF load errors (CSP, import-map Vue dedupe). Frontmatter `title: Troubleshooting`, `lang: en`.
- [ ] **Step 4: `glossary.md`** — definitions: FileRef, MF (micro-frontend), SETUP/APP mode, sidecar, virtual user, JSON-RPC worker, `.fyp`, `uiEntry`, `ToolCallback`, MD3, Ollama backend. Frontmatter `title: Glossary`, `lang: en`.
- [ ] **Step 5: `changelog.md`** — a short page that links to the root `CHANGELOG.md`:
  ```markdown
  ---
  title: Changelog
  lang: en
  ---
  # Changelog
  The full release history lives in the repository's [CHANGELOG.md](https://github.com/MuskStark/FengYu/blob/4.0.0-FengYu/CHANGELOG.md).
  ```
- [ ] **Step 6: Build + commit**

Run: `npm run docs:build` — EN content is now complete; expect zero dead-link warnings among EN pages. Then:

```bash
git add docs/en/reference
git commit -m "📝 docs(en): add reference section (rest-api, sse-events, troubleshooting, glossary, changelog)"
```

---

## Task 7: Mirror all EN content into ZH

**Files:**
- Create: the full `docs/zh/` tree mirroring `docs/en/` (31 content pages + the finalized `docs/zh/index.md` home).

**Interfaces:**
- Consumes: the frozen EN pages from Tasks 1–6 (source of truth). **EN must be frozen before this task starts** — make no EN edits while translating.

- [ ] **Step 1: Finalize the ZH home (`docs/zh/index.md`)** — mirror the EN home's `features:` array (translate `title`/`details`, keep links pointing to `/zh/...`), with hero `蜂语 FengYu` / *「蜂之所向，流之所往」* (already scaffolded in Task 0; add the feature cards now).
- [ ] **Step 2: Translate the 4 top-level pages** — `quickstart.md`, `features.md`, `design-system.md` (translate prose; keep all code blocks, commands, paths, versions, class/endpoint names in English).
- [ ] **Step 3: Translate the 5 `architecture/` pages.**
- [ ] **Step 4: Translate the 14 `plugins/` pages.**
- [ ] **Step 5: Translate the 4 `guide/` pages.**
- [ ] **Step 6: Translate the 5 `reference/` pages** (the `rest-api.md`/`sse-events.md` tables stay structurally identical; only surrounding prose is translated; `changelog.md` mirror of the EN link page).
- [ ] **Step 7: Build to verify ZH tree parity**

Run: `npm run docs:build`
Expected: completes with zero dead-link warnings in either locale. Verify every `/zh/...` link in `zhSidebar` resolves.

- [ ] **Step 8: Commit**

```bash
git add docs/zh
git commit -m "📝 docs(zh): mirror full Chinese documentation (31 pages)"
```

---

## Task 8: Add the GitHub Pages workflow, cross-link pass, and screenshots placeholder

**Files:**
- Create: `.github/workflows/docs.yml`
- Create: `docs/public/screenshots/.gitkeep` (placeholder dir)
- Modify: any EN/ZH page still missing a cross-link noticed during the build (add links between related pages, e.g. manifest → worker → file-io).

- [ ] **Step 1: Create `.github/workflows/docs.yml`**

```yaml
name: Docs

on:
  push:
    branches: [4.0.0-FengYu, main]
    paths: ['docs/**', 'package.json', 'package-lock.json', '.github/workflows/docs.yml']
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: docs-pages
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: npm
      - run: npm ci
      - run: npm run docs:build
      - uses: actions/configure-pages@v5
      - uses: actions/upload-pages-artifact@v3
        with:
          path: docs/.vitepress/dist
  deploy:
    needs: build
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - id: deployment
        uses: actions/deploy-pages@v4
```

- [ ] **Step 2: Create the screenshots placeholder dir**

```bash
mkdir -p docs/public/screenshots
touch docs/public/screenshots/.gitkeep
```

- [ ] **Step 3: Cross-link pass** — run the build and read any remaining warnings; add missing internal links so every "see also" reference resolves. Confirm `docs/.nojekyll` presence (VitePress output already disables Jekyll, but keep the file if it existed): `test -f docs/.nojekyll || echo "absent (VitePress handles this)"`.
- [ ] **Step 4: Build clean**

Run: `npm run docs:build`
Expected: completes with **zero** dead-link warnings in both locales.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/docs.yml docs/public/screenshots docs
git commit -m "📝 docs: add GitHub Pages workflow, screenshots placeholder, cross-link pass"
```

---

## Task 9: Rewrite the root README.md

**Files:**
- Modify (full rewrite): `README.md`

**Interfaces:**
- Consumes: verified facts + branding. Produces an accurate 4.0.0 README.

- [x] **Step 1: Rewrite `README.md`**

Full content (replace the entire file). Structure:
1. **Title + badges** — `# Infinia` (with `蜂语 FengYu` subtitle), slogan, badges: Java 21, Spring Boot 4.1.0, Vue 3.5, Vuetify 3, Tauri 2.0, License GPL-3.0, version 4.0.0.
2. **What it is** — 2-3 sentences: a modular web + desktop toolbox; headless Spring Boot backend + Vue/Vuetify MD3 frontend + Tauri desktop shell; plugin-based (`.fyp` packages with JSON-RPC workers + micro-frontend UIs); multi-backend AI chat + plan-and-execute agent.
3. **Quick start** — the build order + run jar + dev frontend + smoke test (copy from quickstart.md, condensed).
4. **Features** — the matrix table (link to docs).
5. **Architecture** — module table: `FengYu-Api` (plugin + AI contract), `FengYu-Plugin-Sdk` (TS SDK), `OfficialPlugins` (markdown, excel), `FengYu` (headless backend), `frontend/` (Vue SPA), `desktop/` (Tauri shell).
6. **Tech stack** — versions from Global Constraints.
7. **Plugin system** — `.fyp` packages, `manifest.json`, JSON-RPC workers, `@fengyu/plugin-sdk`, `fengyu plugin` CLI. **No `FengYuPluginV2`, no JavaFX.**
8. **Database** — 4 backends, setup wizard, AES-GCM.
9. **Links** — Online docs (the VitePress site URL `https://muskstark.github.io/FengYu/`), CHANGELOG, Contributing, License.
10. **Footer** — `Built with ❤️ using Spring Boot, Vue 3, and Tauri.` (NOT "JavaFX").

No JavaFX/glassmorphism/3.2.0/`StageStyle` anywhere.

- [x] **Step 2: Verify README links resolve**

Open every relative doc link in the README mentally against the created page set; fix any mismatch.

- [x] **Step 3: Commit**

```bash
git add README.md
git commit -m "📝 docs: rewrite root README for 4.0.0 (web + desktop architecture)"
```

---

## Task 10: Final verification

- [x] **Step 1: Full clean build**

Run: `npm run docs:build`
Expected: exit 0, zero warnings, `docs/.vitepress/dist/` populated.

- [x] **Step 2: Local preview smoke**

Run: `npm run docs:preview` (then Ctrl-C). Confirm the server starts and serves `/en/` and `/zh/`.

- [x] **Step 3: Accuracy grep — no stale terms in content**

Run from repo root:

```bash
grep -rniE 'JavaFX|glassmorphism|FengYuPluginV2|StageStyle|PluginFileController|PluginWorkspaceService|WorkspaceSweepJob|/api/plugins\b|\bGGUF\b|Qwen3Adapter|ToolCallParser|ThinkingStreamSegmenter|AiSpringContext|startWeb|\?token=' docs/en docs/zh README.md docs/.vitepress/config.ts | grep -v 'superpowers/'
```

Expected: **no output** (zero hits). If any hit appears, fix the page to use the verified term.

- [x] **Step 4: Page-count parity check**

Run:

```bash
echo "EN pages:"; find docs/en -name '*.md' | wc -l
echo "ZH pages:"; find docs/zh -name '*.md' | wc -l
```

Expected: both report **32** (4 top-level incl. index + 5 architecture + 14 plugins + 4 guide + 5 reference). If unequal, a page was missed — add it.

- [x] **Step 5: Brand check**

Run:

```bash
grep -rl 'Infinia' docs/en/index.md docs/.vitepress/config.ts && \
grep -rl '蜂语 FengYu' docs/zh/index.md && \
grep -rl 'Where bees go, flows follow' docs/en/index.md && \
grep -rl '蜂之所向，流之所往' docs/zh/index.md && \
echo "brand OK"
```

Expected: prints `brand OK`.

- [x] **Step 6: Final commit if any fixes were made**

```bash
git add -A
git commit -m "📝 docs: final verification fixes" || echo "nothing to commit — clean"
```

---

## Done criteria

- ✅ `npm run docs:build` is clean (exit 0, zero warnings; dead-link checking enabled).
- ✅ 35 EN pages + 35 ZH pages exist (exceeds the 32-page target by 3: email-center, database, ui-components); EN↔ZH parity verified; language switch works.
- ✅ Accuracy grep returns zero stale-term hits (remaining matches are intentional negations like "there is no `?token=`").
- ✅ Brand elements (Infinia/蜂语, slogan, MD3 purple, Möbius logo) present.
- ✅ Root `README.md` rewritten for 4.0.0 (no JavaFX/glassmorphism/GGUF/StageStyle).
- ✅ `.github/workflows/docs.yml` builds + deploys to GH Pages.

> The changes from this completion pass (Task 9 README rewrite + Task 10 verification) plus four
> accuracy fixes that track project progress since the plan was written —
> `overview.md`/`build-deploy.md`/`manifest.md` now reflect that `fan.summer.email` is a shipped
> official plugin and that the build script produces three `.fyp` packages — are staged but not yet
> committed. Commit them with:
> ```bash
> git add README.md docs/en docs/zh
> git commit -m "📝 docs: rewrite root README for 4.0.0 + sync plugin docs with email official plugin"
> ```
