# Design: Full documentation rewrite for 4.0.0 (VitePress, bilingual)

- **Date:** 2026-07-14
- **Branch:** `4.0.0-FengYu`
- **Status:** Approved (all 4 design sections), pending user spec review
- **Author:** brainstorming session

## Goal

Completely rewrite the project documentation — `docs/` and root `README.md` — for the
4.0.0 architecture, replacing the stale docsify + JavaFX-era content. Structure mirrors the
flat, categorized information architecture of `https://learn.chatgpt.com/docs`. Bilingual
(English source-of-truth + Chinese mirror), English-first. Branding drawn from the brand
definition (Infinia / 蜂语 FengYu, slogan, Möbius logo).

## Decisions (locked during brainstorming)

| Decision | Choice |
|---|---|
| Legacy content handling | **Full rewrite, replace all** — delete entire existing `docs/` tree (incl. JavaFX `ui-design/` and migration pages); old content stays recoverable in git history |
| Doc platform | **VitePress** (Vue-based, matches the frontend stack; modern nav + search) |
| i18n organization | **Two parallel trees** — EN (default root) + ZH (`zh/`), language switcher in nav |
| Depth | **Comprehensive set (~32 content pages × 2 languages)** |
| JavaFX ui-design section | **Dropped** — new docs describe the 4.0.0 web UI (Vuetify MD3) only; single `design-system.md` page |
| Brand file | **No standalone `brand.md`** in the new tree — brand info lives inline (home hero, nav, footer) |
| Top-level IA grouping | **Approach A — replicate the reference IA** (flat categorized: top-level pages / `architecture/` / `plugins/` / `guide/` / `reference/`) |

## Critical: docs must follow the ACTUAL 4.0.0 source, not AGENTS.md

The existing `AGENTS.md` and `README.md` are **stale and inaccurate** about the 4.0.0
architecture. The new docs follow the actual source. The source exploration established these
corrections — every page must honor them:

| Stale claim (AGENTS.md / old README) | Actual 4.0.0 source |
|---|---|
| Plugin v2 = `FengYuPluginV2` Java interface (`descriptor()`/`invoke()`/`aiTools()`) | Plugins are **out-of-process JSON-RPC 2.0 workers** described by a JSON `manifest.json`; contract is a `.fyp` package |
| `PluginFileController` / `PluginWorkspaceService` / `WorkspaceSweepJob` | `PluginRuntimeFileController` / `PluginFileGrantService` (in-memory grants, `@PreDestroy` cleanup, **no sweep job**) |
| Local AI = bundled GGUF/JNI inference, Qwen3Adapter / ToolCallParser / ThinkingStreamSegmenter | Local = **Ollama** (`ollama serve`); tool-calling = **Spring AI `ToolCallingManager`** |
| Endpoints `/api/plugins`, `/plugin-ui/{id}/**` | `/api/plugin-runtime`, `/plugin-runtime/{id}/**` |
| SSE `?token=` | SSE `?streamId=`; `POST /api/ai/chat` returns `{streamId}` then `GET /api/ai/stream?streamId=` |
| AI modes local/openai/anthropic | local (Ollama) / **openai** / **anthropic** / **deepseek** (DeepSeek is OpenAI-compatible) |
| (not in AGENTS.md) | **Plan-and-Execute agent** (`/api/agent/*`), **conversation persistence** (`/api/ai/conversations`), **marketplace** (`/api/plugin-market`) |
| CLI `--mode` flag | **No `--mode` flag** — SETUP vs APP auto-detected from `datasource.properties` presence + DB reachability |
| `AiSpringContext.startWeb` | `HeadlessLauncher` uses `SpringApplicationBuilder` directly |

### Verified 4.0.0 facts (source of truth for all pages)

**Boot:** `fan.summer.fengyu.HeadlessLauncher`. CLI args (only): `--port=<n>` (default `24056`,
fallback to OS-assigned on conflict), `--token=<t>` (stored as system property
`fengyu.auth.token`, sent as `X-FengYu-Token`). Forces `server.address=127.0.0.1`. SETUP mode
(boots `SetupApplication`, no JPA) when `~/.fengyu/config/datasource.properties` absent or DB
unreachable; APP mode (boots `FengYuApplication`, `fengyu.mode=app`) once reachable. Prints
`FENGYU_PORT=<n>` to stdout. Exit codes: `SETUP_DONE=0`, `FATAL=1`.

**REST/SSE surface** (all under `127.0.0.1`; token required except `/api/health`,
`/api/setup/*`, and `/plugin-runtime/{id}/**` static assets):
- `GET /api/health` → `{status:"ok"}`
- `GET /api/plugin-categories`
- `GET /api/plugin-runtime` → enabled `InstalledPluginDescriptor[]`
- `POST /api/plugin-runtime/{id}/invoke` → body `{method, params}`; JSON-RPC via `PluginProcessManager`
- `GET /plugin-runtime/{id}/**` → plugin UI assets (strict CSP); `uiEntry = /plugin-runtime/{id}/{manifest.ui.entry}`
- `POST /api/plugin-runtime/{id}/files/upload` (multipart `file`, perm `files.read`)
- `POST /api/plugin-runtime/{id}/files/upload-directory` (multipart `files`+`paths[]`, perm `files.read`)
- `POST /api/plugin-runtime/{id}/files/native` (body `{path,kind,access}`, perm `files.read|files.write`)
- `POST /api/plugin-runtime/{id}/files/output` (perm `files.write`)
- `GET /api/plugin-runtime/{id}/files/export/{ref}` → zip (perm `files.write`)
- `GET /api/plugin-market` · `POST /upload` · `POST /upload-native` · `POST /{id}/install` · `POST /{id}/update` · `PATCH /{id}/enabled` · `DELETE /{id}`
- `GET/PUT /api/settings` · `POST /api/settings/database/reset`
- `POST /api/ai/chat` (body `{messages}`) → `{streamId}` · `GET /api/ai/stream?streamId=` (SSE)
- `GET/PUT /api/ai/config` · `POST /api/ai/config/test`
- `GET/POST/PUT/DELETE /api/ai/conversations[/{id}]`
- `POST /api/agent/run` → `{runId}` · `GET /api/agent/stream?runId=` (SSE) · `POST /api/agent/{runId}/approve` · `POST /api/agent/{runId}/cancel` · `GET /api/agent/tools`
- `GET /api/setup/status` · `GET /api/setup/types` · `POST /api/setup/test-connection` · `POST /api/setup/initialize` · `DELETE /api/setup/config`

**Plugin package contract** — `.fyp` = zip of `manifest.json` + `ui/` + `backend/worker.jar`.
`PluginManifest` record fields: `schemaVersion, id, name, description, version, author, icon,
category, ui{entry}, backend{command,protocol}, permissions[], homepage, official, aiTools[]`.
Each `aiTool`: `name, description, inputSchema (JSON Schema), method`. Host-facing
`InstalledPluginDescriptor` adds `enabled, iconStyle (hardcoded "BLUE"), supportsAi
(aiTools non-empty), source (OFFICIAL|THIRD_PARTY)`. **Workers are out-of-process JSON-RPC 2.0;
they never live in the host Spring context.** Host rewrites `ref_*` FileRefs to absolute paths
before dispatch.

**File I/O** — temp root `${java.io.tmpdir}/fengyu/runtime-files/{pluginId}/{uuid}/...`.
In-memory grants (ConcurrentHashMap); whole tree wiped in `@PreDestroy`. **No scheduled sweep.**

**Database** — JPA + Hibernate (`ddl-auto=update`). Four backends via `DbType`: `H2, SQLITE,
MYSQL, POSTGRESQL` (H2/SQLite embedded). Config at `<userDir>/.fengyu/config/datasource.properties`;
password AES/GCM via `CryptoUtil` (key = SHA-256 of `"FengYu-4.0-Phase4-SetupKey:" + machineUUID`
at `~/.fengyu/config/.machineid`). Virtual user id=1 `"ZFlow-Summer"` (admin/local).
`sys_user`/`sys_session` reserved for future login.

**Frontend** — vue `3.5.39`, vuetify `^3.12.9` (MD3), vue-i18n `^10.0.8`, vue-router `^4.5.0`,
pinia `^2.3.1`, vite `^6.0.7`. MF host `loader.ts` → `import(uiEntry)` → `default.mount(el, ctx)`.
Vue shared via import map. Tauri bridge via `window.__FENGYU_TOKEN__|__FENGYU_PORT__|__FENGYU_API_BASE__`.

**Desktop** — Tauri `2.0`. Release build spawns Java sidecar (`HeadlessLauncher --port=24056
--token`), reads `FENGYU_PORT`, polls `/api/health`, probes SETUP, injects token/api-base,
restarts into APP on `SETUP_DONE`. `tauri.conf.json productName:"Infinia"`, version `4.0.0`.

**AI** — cloud `SpringAiCloudBackend` (OpenAI/Anthropic/DeepSeek via Spring AI models); local
`OllamaLocalBackend` (external `ollama serve`; no in-process GGUF). Tool calling = Spring AI
`ToolCallingManager`. SSE events: `token{text}`, `thinking{text}`, `tool{phase:call|result,...}`,
`done{text,tokens,tps}`, `error{message}`. Agent SSE: `plan_token`, `plan_ready`,
`plan_approval_requested`, `step_start`, `step_complete`, `step_approval_requested`, `complete`,
`error`.

**Official plugins** — `plugin-markdown` (id `fan.summer.markdown`, category `text`, action
`render`, no aiTools) and `plugin-excel` (id `fan.summer.excel`, category `file`, actions
`analyze`/`configure`/`split`, 6 aiTools, perms `files.read`/`files.write`). `plugin-email` is
source-only, **not packaged** — documented only as "coming soon".

**Versions** — `<revision>4.0.0-SNAPSHOT</revision>`, Java 21, Spring Boot 4.1.0, Spring AI
2.0.0. Modules: `FengYu-Api`, `FengYu-Plugin-Sdk`, `OfficialPlugins`, `FengYu`.

---

## Section 1 — Information Architecture (file tree)

VitePress, two parallel language trees. English is the default root; Chinese under `zh/`.
~32 content pages × 2 languages = 64 files.

```
docs/                                  ← new VitePress project root
├─ .vitepress/
│  ├─ config.ts                        ← site config: theme, search, i18n locales
│  └─ theme/                           ← custom theme slots (hero, brand)
├─ public/
│  ├─ logo.svg                         ← Möbius strip logo
│  └─ screenshots/                     ← app + plugin screenshots (TODO placeholders ok)
├─ en/                                 ← EN (default root via /  →  /en/)
│  ├─ index.md                         ← Home / landing
│  ├─ quickstart.md
│  ├─ features.md
│  ├─ design-system.md                 ← single MD3/Vuetify page (no JavaFX ui-design)
│  ├─ architecture/
│  │  ├─ overview.md
│  │  ├─ backend.md
│  │  ├─ frontend.md
│  │  ├─ desktop.md
│  │  └─ plugin-system.md
│  ├─ plugins/
│  │  ├─ overview.md
│  │  ├─ getting-started.md
│  │  ├─ manifest.md
│  │  ├─ worker.md
│  │  ├─ ui-microfrontend.md
│  │  ├─ file-io.md
│  │  ├─ ai-tools.md
│  │  ├─ sdk-cli.md
│  │  ├─ marketplace.md
│  │  ├─ i18n.md
│  │  ├─ build-deploy.md
│  │  ├─ official-markdown.md
│  │  ├─ official-excel.md
│  │  └─ pitfalls.md
│  ├─ guide/
│  │  ├─ ai-chat.md
│  │  ├─ ai-agent.md
│  │  ├─ database.md
│  │  └─ configuration.md
│  └─ reference/
│     ├─ rest-api.md
│     ├─ sse-events.md
│     ├─ troubleshooting.md
│     ├─ changelog.md
│     └─ glossary.md
├─ zh/                                 ← ZH (mirror, identical structure, translated)
│  └─ ... (same tree)
└─ index.md                            ← root redirect → /en/ (or locale detect)
```

**Top nav:** `Home | Quickstart | Architecture | Plugins | Guide | Reference` + language dropdown.
**Left sidebar:** section-grouped per the tree. **Search:** VitePress built-in local search, per-locale.

---

## Section 2 — Per-page content outline

### Top-level pages
- **`index.md` (Home)** — brand hero (Infinia / 蜂语, slogan, Möbius logo), feature highlights
  grid, "Get started" CTA, architecture diagram (backend ↔ frontend ↔ desktop). What it is
  (modular web+desktop toolbox).
- **`quickstart.md`** — prerequisites (JDK 21), clone, build order (install API → build app →
  run jar with `--token`), dev frontend (`cd frontend && npm run dev`), smoke test
  (`scripts/e2e-smoke.sh`). Desktop counterpart (`cargo tauri dev`).
- **`features.md`** — capability matrix: AI chat (multi-backend), AI agent (plan-and-execute),
  Excel splitter, Markdown editor, plugin marketplace, multi-database, i18n, dark/light.
- **`design-system.md`** — 4.0.0 design language only: Vuetify 3 Material Design 3 baseline,
  theme via `useThemeStore` singleton, MD3 palette in `md3-themes.ts`, MF plugins share host
  Vuetify via `PluginContext.vuetify`. No JavaFX content.

### `architecture/` — the running 4.0.0 app
- **`overview.md`** — three-layer diagram (headless Spring Boot ↔ Vue SPA ↔ Tauri shell),
  request flow, loopback-only bind, per-launch token auth.
- **`backend.md`** — `HeadlessLauncher`, CLI flags (`--port`/`--token` only, default 24056,
  fallback), SETUP vs APP via `datasource.properties` probe, `FENGYU_PORT` announcement, exit
  codes, Spring Boot 4.1 + Spring AI 2.0.
- **`frontend.md`** — Vue 3.5.39 + TS + Pinia + vue-router + vue-i18n, Vuetify 3 (MD3), MF host
  `loader.ts` (`import(uiEntry)` → `default.mount(el, ctx)`), stores, Tauri bridge.
- **`desktop.md`** — Tauri 2.0 sidecar lifecycle: spawn jar → read `FENGYU_PORT` → poll health
  → probe SETUP → inject token/api-base → restart into APP on `SETUP_DONE`. Dev vs release.
- **`plugin-system.md`** — `.fyp` package concept, `manifest.json` (schemaVersion, UI entry,
  Backend command+protocol, permissions, aiTools), JSON-RPC 2.0 worker process (isolated),
  UI sandboxed iframe + host bridge.

### `plugins/` — plugin author guide
- **`overview.md`** — what a plugin is, `.fyp` layout, official vs third-party, lifecycle.
- **`getting-started.md`** — `fengyu plugin init` scaffold, directory layout, dev-mode run.
  *(Implementation note: verify the actual `fengyu plugin` CLI subcommands against
  `plugin-cli/` source before writing; document only commands that actually exist.)*
- **`manifest.md`** — full `PluginManifest` schema reference (fields, types, defaults),
  UI/Backend/AiTool sub-records.
- **`worker.md`** — JSON-RPC 2.0 protocol: method → params → result, FileRef resolution
  (host rewrites `ref_*` to absolute path before dispatch), Worker SDK Java API, error codes.
- **`ui-microfrontend.md`** — MF bundle contract (`default.mount(el, ctx)`), `PluginContext`
  (vuetify, desktop, i18n, api), import-map Vue sharing, CSP.
- **`file-io.md`** — grant model: `upload`, `upload-directory`, `native` (Tauri dialog → path),
  `output` + `export/{ref}` (zip), permissions `files.read`/`files.write`, temp dir layout,
  `@PreDestroy` cleanup (no scheduled sweep).
- **`ai-tools.md`** — declaring `aiTools` in manifest (name, description, JSON-Schema
  `inputSchema`, method), host aggregation via Spring AI `ToolCallingManager`, `supportsAi` flag,
  tool SSE events.
- **`sdk-cli.md`** — `@fengyu/plugin-sdk` (TS), Java Worker SDK, `fengyu plugin` CLI commands.
- **`marketplace.md`** — marketplace catalog format, install flow (upload/upload-native/install
  by id), update/enable/disable/uninstall, `catalog-url` override.
- **`i18n.md`** — plugin localization, integration with host vue-i18n.
- **`build-deploy.md`** — `build-packages.sh`, `.fyp` assembly, shade worker jars, signing/versioning.
- **`official-markdown.md`** — walkthrough: `invoke("render")`, commonmark backend, MF editor.
- **`official-excel.md`** — walkthrough: 3 modes (analyze/configure/split), 6 AI tools, file I/O,
  four-step wizard.
- **`pitfalls.md`** — common traps (CSP, FileRef resolution timing, MF Vue dedupe, permission gating).

### `guide/` — using the app
- **`ai-chat.md`** — chat UI, modes (local=Ollama / OpenAI / Anthropic / DeepSeek), SSE streaming,
  thinking cards, tool-call display, conversation persistence.
- **`ai-agent.md`** — Plan-and-Execute agent: goal → plan → approval → steps → complete,
  approval gates, cancel, `/api/agent/*` events.
- **`database.md`** — first-launch setup wizard, 4 backends (H2/SQLite/MySQL/PostgreSQL),
  AES-GCM password encryption, virtual user (id=1 "ZFlow-Summer"), reconfigure path.
- **`configuration.md`** — settings (`GET/PUT /api/settings`), AI config (`/api/ai/config`,
  hot-swap), `datasource.properties` layout, theme/language/sidebar prefs.

### `reference/`
- **`rest-api.md`** — full endpoint catalog by controller, each as method/path/auth/request/response.
- **`sse-events.md`** — chat SSE events + agent SSE events with payload shapes.
- **`troubleshooting.md`** — port conflict, DB connection failures, token mismatch, plugin worker
  crashes, MF load errors.
- **`changelog.md`** — links root `CHANGELOG.md`.
- **`glossary.md`** — FileRef, MF, SETUP/APP mode, sidecar, virtual user, JSON-RPC worker, etc.

**Totals:** 4 top-level + 5 architecture + 14 plugins + 4 guide + 5 reference = **32 content
pages** × 2 languages = 64 files.

---

## Section 3 — Branding, tone, VitePress config & CI

### Branding application
- **Product name:** `Infinia` in EN nav/site title/footer; `蜂语 FengYu` on the ZH home hero.
  Repo/project stays "FengYu" internally; user-facing English uses **Infinia**.
- **Slogan:** EN home hero subtitle = *"Where bees go, flows follow."*; ZH = *「蜂之所向，流之所往」*.
- **Logo:** Möbius strip `logo.svg` in `public/`, used as `logo` in VitePress `themeConfig` +
  home hero image + favicon.
- **Theme accent color:** MD3 primary `#6750A4` (Google M3 default baseline purple, matching the
  app's `md3-themes.ts`), so docs visually match the product.

### Tone & writing rules
- English is source of truth; ZH mirrors faithfully (not a paraphrase). Code/paths/identifiers
  stay in English in ZH.
- Present tense, concise; lead with what the thing *does*, then how.
- Code blocks always carry a language tag; commands are copy-pasteable.
- Accurate to **source** (the correction table above) — never copy stale AGENTS.md claims.
- Page frontmatter: `title`, `description` (SEO/social), per-page `lang`.
- Cross-links between related pages.

### VitePress config (`.vitepress/config.ts`)
- **Locales:** `root: { lang: 'en' }` serves `/en/*`; `zh: { lang: 'zh-CN' }` serves `/zh/*`.
  Top-nav language switcher via VitePress built-in. Root `index.md` redirects to `/en/`.
  Fallback if the "EN default root" pattern needs adjustment: EN at root `/`, ZH at `/zh/`
  (same semantic outcome; matches current docsify structure).
- **Theme:** default VitePress theme with brand overrides (logo, MD3 purple CSS vars, hero).
  Search = built-in local search, per-locale.
- **Sidebar:** per-locale, grouped by Section-1 sections.
- **Nav:** `Home | Quickstart | Architecture | Plugins | Guide | Reference` + language dropdown.
- **Last-updated:** enabled (git-based). **Sitemap:** VitePress `sitemap` plugin.
- **Head:** Open Graph tags, favicon, theme-color (MD3 purple), `Infinia` default title.

### What gets deleted (full rewrite)
Entire current `docs/` content is replaced — `_sidebar.md`, `_navbar.md`, `_coverpage.md`,
`index.html` (docsify), all `.md` pages incl. `ui-design/` JavaFX section and `migration-*.md`,
for both EN and ZH. **Preserved:** `docs/assets/` (icon-source SVGs) and `docs/superpowers/`
(planning artifacts, not shipped docs).

### GitHub Pages / CI
- New `.github/workflows/docs.yml`: on push to `4.0.0-FengYu` (+`main`), install deps,
  `vitepress build docs`, deploy `docs/.vitepress/dist` to GH Pages.
- Replaces docsify's "serve raw markdown" model with "build + deploy HTML".
- `.nojekyll` kept.

### `README.md` (repo root) rewrite
Fully rewritten to: brand (Infinia / 蜂语) + slogan + badges (Java 21, Spring Boot 4.1, Vue 3.5,
Vuetify 3, Tauri 2.0, GPL-3.0, 4.0.0); what it is (web+desktop toolbox); quick start (build
order, run jar, dev frontend, smoke test); features matrix (links to docs); architecture
overview table (modules `FengYu-Api`, `FengYu-Plugin-Sdk`, `OfficialPlugins`, `FengYu`,
`frontend`, `desktop`); tech stack (real versions); plugin system (`.fyp` packages, manifest,
JSON-RPC workers — **not** stale `FengYuPluginV2`); database (4 backends, setup wizard); links
(online docs, CHANGELOG, Contributing, License). **No JavaFX/glassmorphism, no 3.2.0 strings,
no `StageStyle`.**

---

## Section 4 — Implementation sequencing, risks & verification

### Phases (tasks for the implementation plan)
- **Phase 0 — VitePress scaffold:** clear current `docs/` content (preserve `assets/` +
  `superpowers/`); init VitePress (`docs/.vitepress/config.ts` with dual locales + MD3 brand +
  sidebars/nav); `public/logo.svg` (Möbius); root `index.md` redirect → `/en/`; add
  `.github/workflows/docs.yml`. **Milestone:** `npm run docs:dev` serves a working empty shell
  with EN/ZH switch.
- **Phase 1 — EN content (source of truth):** write all 32 EN pages per Section 2, verified
  against the correction table. Clusters: top-level → architecture → plugins (largest) →
  guide → reference. **Milestone:** EN site complete, internally linked, source-accurate.
- **Phase 2 — ZH content (mirror):** translate each EN page; keep code/paths/identifiers in
  English; ZH home uses 蜂语 brand. **Milestone:** ZH tree mirrors EN 1:1; switch works.
- **Phase 3 — README & polish:** rewrite root `README.md`; screenshot placeholders in
  `public/screenshots/`; final cross-link + dead-link scan (`vitepress build` warns on bad links).
  **Milestone:** `vitepress build` clean, zero warnings; site deployable.

### Verification
- **Build cleanliness:** `npm run docs:build` exits 0, no dead/broken-link warnings.
- **Local preview:** `npm run docs:preview` — every EN/ZH page renders, language switch works,
  search works.
- **Accuracy audit:** every endpoint path, class name, CLI flag, version, file path verified
  against actual source (correction table is the baseline).
- **Completeness:** all 32 × 2 pages present; no "TBD/TODO" body (only screenshots may be TODO).
- **Brand check:** Infinia/蜂语, slogan, Möbius logo, MD3 purple present on home + nav.
- **No stale content:** grep for `JavaFX`, `glassmorphism`, `FengYuPluginV2`, `StageStyle`,
  `3.2.0`, `PluginFileController`, `/api/plugins`, `GGUF` returns zero hits in content.

### Risks & mitigations
| Risk | Mitigation |
|---|---|
| **Volume** — ~64 files | Write EN in clusters; dispatch parallel agents on independent clusters (architecture vs guide vs reference) for throughput; ZH only after EN is frozen. |
| **Source accuracy** — correction table must win over AGENTS.md | Each page verified against actual source at write time; verification phase adds greps. Plugin/reference pages highest-risk — backed by the explored map. |
| **VitePress "default root = EN"** pattern | Validate the redirect mechanism in Phase 0 immediately; fallback is EN at root `/`, ZH at `/zh/` (same semantics as current docsify). |
| **`plugin-email` source-only, not packaged** | Docs cover only shipped official plugins (markdown, excel). Email listed as "coming soon" in features; no email deep-dive page. |
| **Clobbering existing GH Pages docsify site** | New workflow replaces the old site; docsify `_sidebar.md` etc. deleted. Brief content swap during push-to-deploy — acceptable (4.0.0 itself is a preview). |
