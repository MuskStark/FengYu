# 4.0.0 UI Strangler — Phase 1: Vue + Tauri Walking Skeleton

**Date:** 2026-07-08
**Branch:** `4.0.0-ZhiFlow`
**Status:** Design (spec) — awaiting review before implementation plan
**Predecessor:** Phase 1 AI Strangler (Spring AI cutover, complete — see `2026-07-06-phase1-ai-strangler-spring-ai.md`)

---

## 1. Purpose

Strangle the JavaFX UI. In 4.0.0, ZhiFlow becomes a **web + desktop application**:

- The Java process is turned into a **headless local backend** (Spring Boot 4 web server) — no JavaFX in the running backend.
- The UI is rebuilt as a **Vue 3.5.39 + TypeScript** single-page app, byte-identical for browser and desktop.
- The desktop app is a **Tauri 2.0** shell that sidecar-launches the Java backend.

This spec covers **only Phase 1: a walking skeleton** — a thin end-to-end slice through every layer, proving the full pipe before any tool is ported wide. It follows the same strangler-fig instinct that drove the AI cutover: build the new path thin, prove it, then expand and delete the old.

### Non-goals (Phase 1)

- Porting the other built-in tools (Excel/PDF/email/browser/other dev tools) — later phases.
- Runtime marketplace download / plugin override / hot-reload — later phase.
- Multi-datasource first-run wizard (MySQL/SQLite/H2 selection) — later phase (**Phase G**).
- Deleting JavaFX — JavaFX stays in the tree, untouched and unbuilt, during Phase 1. Removed in a later phase once the skeleton is validated.
- Production desktop packaging (signed installers, per-platform bundled JRE via CI) — later phase; Phase 1 targets a working dev-mode Tauri window.
- Auth beyond a loopback bind + per-launch token.

---

## 2. Full 4.0.0 decomposition (context)

4.0.0 spans six interdependent sub-projects. Each gets its own spec → plan → implement cycle. Phase 1 (this spec) takes a **minimal vertical slice of every one**.

| Phase | Sub-project | Phase 1 slice |
|---|---|---|
| A | Backend → Spring Boot web server | minimal HTTP + one SSE stream |
| B | Plugin system v2 (Spring Boot 4 compatible) | loader skeleton, compile-time bundling only |
| C | Built-in tools → official plugins | exactly **one** dev tool extracted (Base64) |
| D | Vue 3.5 + TS main shell | sidebar / theme / settings / AI chat |
| E | Micro-frontend host (dynamic ESM) | load + mount that one plugin's UI |
| F | Tauri 2.0 desktop shell | dev-mode window + Java sidecar |

Named future phases (out of scope here, recorded so they are not lost):

- **Phase C-rest** — port Excel, PDF, email, email-archive, browser-automation, and remaining dev tools (hash/json/markdown/color) into official plugins.
- **Phase B-hotload** — runtime marketplace override: download a newer plugin version into the user data dir and load it over the bundled version (dynamic classloading into the running Spring context).
- **Phase G** — first-run deployment wizard: choose datasource (MySQL and other JDBC servers, or embedded H2/SQLite), write config, run schema init.
- **Phase F-prod** — signed installers + per-platform bundled JRE via GitHub Actions.
- **Phase X-delete** — remove JavaFX from the tree.

### Target end-state (all phases)

```
┌─ Tauri 2.0 desktop shell ─────────────────┐     ┌─ Browser ─┐
│  Vue 3.5 + TS frontend (main shell)        │     │  same Vue │
│   ├ sidebar / nav / theme / settings       │     │  frontend │
│   ├ AI chat view (core, never a plugin)    │     └─────┬─────┘
│   └ micro-frontend host (dynamic ESM)      │           │
└──────────────┬─────────────────────────────┘          │
       HTTP + SSE (127.0.0.1 loopback)  ◄─────────────────┘
┌──────────────┴─────────────────────────────┐
│  Java headless backend (Spring Boot 4)      │
│   ├ REST controllers + SSE endpoints        │
│   ├ Plugin system v2 (Spring bean based)    │
│   ├ official plugins (Maven modules)        │
│   ├ AI core (Spring AI — already done)      │
│   └ H2 / MyBatis (already done)             │
└─────────────────────────────────────────────┘
```

---

## 3. Component A′ — Backend: JavaFX-headless → Spring Boot web server

### What it does

Turns the Java process into a headless local API server. No JavaFX in the running backend.

### Key move

The project already embeds a Spring Boot context (`AiSpringContext`, `WebApplicationType.NONE`) from the AI cutover. Phase 1 flips it to `WebApplicationType.SERVLET` on embedded Tomcat, bound to **`127.0.0.1:<port>` (loopback only — never `0.0.0.0`)**. The entry point changes from `ZhiFlowApp extends javafx.application.Application` to a plain `main()` that boots Spring Boot and blocks. JavaFX startup code is bypassed, not deleted.

The backend accepts `--port=<n>` (0 = pick a free port, printed to stdout for the sidecar to read) and `--token=<t>` (per-launch auth token).

### API surface (Phase 1 — deliberately tiny)

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/health` | GET | sidecar readiness probe (Tauri waits on this) |
| `/api/plugins` | GET | list registered plugins (id, name, category, icon, `uiEntry` URL) |
| `/api/settings` | GET / PUT | theme, language, sidebar-collapsed — H2-backed, existing settings service |
| `/api/ai/chat` | POST | start an AI chat turn (returns a stream id / opens the SSE) |
| `/api/ai/stream` | GET (SSE) | AI streaming: tokens + `<think>` reasoning over Server-Sent Events |
| `/api/plugins/{id}/invoke` | POST | generic plugin backend call (JSON in / JSON out) |
| `/plugin-ui/{id}/*` | GET | serves a plugin's built micro-frontend ESM bundle |

### Transport decision

REST for request/response; **SSE for AI streaming** (not raw WebSocket). SSE is simpler, maps directly onto the existing `AiStreamCallback.onToken` / `onThinking` model, and auto-reconnects in the browser. The AI callback is bridged to a Spring `SseEmitter`. Raw WebSocket is reserved for a later phase if bidirectional plugin needs arise.

### Reused unchanged

H2 / MyBatis, `AiServiceProvider`, `SpringAiCloudBackend` / `OllamaLocalBackend`, the settings service. **AI chat stays a core built-in** — served at `/api/ai/*`, never routed through the plugin `invoke` path.

### Security

Loopback-only bind plus a **per-launch random token**: the Tauri sidecar generates/receives it and passes it to the frontend, which sends it as a header (`X-ZhiFlow-Token`) on every request. A random browser tab on the machine cannot hit the backend without the token. Browser-dev mode reads the token from a local config/env the same way (isolated in `src/api/config.ts`).

---

## 4. Component B′ — Plugin system v2 (Spring Boot 4 compatible)

### The problem being solved

Today's model (`ZhiFlowPlugin.createView()` → JavaFX `Node`, loaded via `URLClassLoader` + `META-INF/services` SPI, isolated by `PluginContext`) is tied to JavaFX and a non-Spring classloader. A headless Spring backend needs a new contract: **backend logic in Java, UI as a separately-served micro-frontend bundle.**

### New plugin contract (v2, minimal)

```java
public interface ZhiFlowPlugin {          // v2, headless
    PluginDescriptor descriptor();        // id, name, category, icon, version, uiEntry
    Object invoke(String action, Map<String, Object> args);  // JSON-in / JSON-out backend calls
    List<AiTool> aiTools();               // unchanged — reused as-is
}
```

The old `createView()` / `onActivate` / `onDeactivate` / theme / JavaFX surface is gone from the contract. UI is delivered out-of-band (Component E′): each plugin ships a built ESM bundle the backend serves at `GET /plugin-ui/{id}/*`, and `PluginDescriptor.uiEntry` points to its entry (`/plugin-ui/{id}/index.js`).

`AiTool` / `AiToolParam` / `AiToolResult` are **reused verbatim** — the AI tool contract does not change.

### Loading model — Phase 1 (compile-time bundling only)

Official plugins are Maven **modules in the main reactor**, compiled and bundled into the main app automatically (like `ZhiFlow-Api` / `ZhiFlow` today). Each official plugin is a Spring `@Component` discovered in the app context on boot. `PluginRegistryService` collects them and drives `/api/plugins` + `/api/plugins/{id}/invoke`.

**Hybrid distribution is the target** (compile-time bundle = floor; runtime marketplace download = override that wins if present and newer). Phase 1 implements only the compile-time floor. The loader **reserves the interface seam** for a runtime-override directory (`~/.zhiflow/plugin/<id>/`) — an internal resolution hook that always resolves to the bundled bean in Phase 1 — so **Phase B-hotload** can slot in without reshaping the registry. No dynamic classloading in Phase 1.

### What is reused

`AiServiceProvider` auto-registration of each plugin's `aiTools()` carries over conceptually — the registry registers/unregisters AI tools as plugins are added, exactly as today, but plugins are now Spring beans rather than SPI-loaded JARs.

---

## 5. Component C′ — One built-in extracted into an official plugin

### Scope

Exactly **one** tool: **Base64** (`dev/Base64Plugin`). Chosen because its logic is pure and dependency-free — the focus of Phase 1 is proving the *pipe* (Java bean → REST → Vue MF host), not porting tool complexity.

### Shape

A new Maven module (e.g. `plugin-base64/`) in the reactor:

- **Backend:** a `@Component` implementing v2 `ZhiFlowPlugin`. `invoke("encode", {text})` → `{"success":true,"summary":"...","result":"<base64>"}`; `invoke("decode", {text})` likewise. Follows the existing tool-return JSON contract (`success` / `summary` / payload).
- **Frontend:** a tiny Vue micro-frontend bundle (its own Vite config, Vue marked `external`) — an input textarea, encode/decode toggle, output area. Built to `resources` and served by the backend at `/plugin-ui/base64/*`.

**AI chat is explicitly NOT extracted** — it remains a permanent main-project built-in (Component A′ `/api/ai/*`).

---

## 6. Component D′ — Vue 3.5 + TS main shell

### What it is

A single Vue 3.5.39 + TypeScript SPA (Vite build), byte-identical for browser and Tauri. It adopts the existing `docs/ui-design/` language directly — the design tokens, dark/light themes, IDEA 2025 New UI layout, sidebar categories, and component specs are already documented (~5400 lines); the Vue components re-express those same tokens as CSS custom properties. **Port tokens, do not redesign.**

### Structure

```
frontend/
 ├ src/
 │  ├ shell/       AppShell, Sidebar (collapsible), StatusBar, DetailPanel
 │  ├ views/       ToolGrid, AiChat, Settings, About
 │  ├ theme/       token CSS (--sk-* vars, .theme-dark / .theme-light) ported from zhiflow-common.css
 │  ├ api/         typed HTTP client + SSE client (health, plugins, settings, ai, invoke) + config.ts (backend URL + token)
 │  ├ mf/          micro-frontend host (Component E′)
 │  └ stores/      Pinia: theme, settings, plugins, aiSession
 └ vite.config.ts
```

- **State:** Pinia.
- **Routing:** Vue Router. Sidebar category → route; each plugin gets a route that mounts its micro-frontend.
- **Styling:** the `--sk-*` token set ported verbatim so light/dark parity and the IDEA New UI look carry over with no visual redesign.
- **AI chat:** a first-class core view (not a plugin). Streams from `GET /api/ai/stream` (SSE), renders markdown via a JS markdown library (replacing the JavaFX `WebView` + `MarkdownRenderer`), and renders `<think>` reasoning as collapsible cards — same UX as today, re-expressed in Vue.

---

## 7. Component E′ — Micro-frontend host

### What it does

Lets the Vue shell load a plugin's UI at runtime as a dynamically-imported ES module, mounted into a route. This keeps the shell decoupled from any individual plugin's UI.

### Contract — a plugin UI bundle is an ESM that default-exports a mount function

```ts
export default {
  mount(el: HTMLElement, ctx: PluginUiContext): () => void  // returns unmount
}
// ctx provides: api client scoped to /api/plugins/{id}/invoke,
// current theme + theme-change subscription, i18n, notifications
```

### Loading flow

1. Shell calls `GET /api/plugins` → each descriptor has a `uiEntry` URL (`/plugin-ui/{id}/index.js`).
2. User opens the plugin → shell does `const mod = await import(/* @vite-ignore */ uiEntry)`.
3. `mod.default.mount(routeEl, ctx)` renders the plugin UI into the shell's content area.
4. On navigation away, the returned unmount function runs.

### Dependency sharing

Vue is shared via an **import map**: the shell exposes its Vue instance; plugin bundles mark Vue `external`. Plugins do not each ship their own Vue, and components render in the same reactive context. The `--sk-*` design tokens are inherited through the DOM automatically — a plugin's UI sits inside the themed shell, so it is on-spec by construction.

### Phase 1 proof

The one extracted Base64 plugin ships a tiny Vue MF bundle built by its own Vite config (Vue external), served by the backend from the plugin module's resources; the shell loads and mounts it. This validates the entire MF path end-to-end without any tool complexity.

---

## 8. Component F′ — Tauri 2.0 desktop shell

### What it does

Wraps the identical Vue build in a native window and launches/manages the Java backend as a sidecar.

### Structure

```
desktop/                    (Tauri project)
 ├ src-tauri/
 │  ├ tauri.conf.json       window config, sidecar binary, allowlist
 │  ├ src/main.rs           spawn Java sidecar, wait on /api/health, then load frontend
 │  └ binaries/             the ZhiFlow.jar (+ later, a bundled JRE) as sidecar
 └ (frontend build output loaded as the webview content)
```

### Lifecycle

1. Tauri starts → `main.rs` spawns the Java sidecar (`java -jar ZhiFlow.jar --port=0 --token=<generated>`), reading the chosen loopback port from the sidecar's stdout.
2. Rust polls `GET /api/health` until ready (timeout → fatal-error window showing the backend log path).
3. Webview loads the Vue frontend, pointed at `127.0.0.1:<port>` with the token injected.
4. On window close → Rust kills the sidecar process cleanly.

### Web vs desktop parity

The frontend is one build. In the browser it talks to a manually-started backend; in Tauri the sidecar is automatic. The only difference is how the backend URL + token reach the frontend — isolated in `src/api/config.ts`.

### Phase 1 scope

A working dev-mode Tauri window that sidecar-launches the jar and shows the Vue shell + the one plugin. Production bundling (signed installers, per-platform bundled JRE via GitHub Actions) is **Phase F-prod**.

---

## 9. Testing & error handling

### Testing

- **Backend:** Spring Boot `@WebMvcTest` / `MockMvc` for the REST endpoints; an SSE-emitter test for the AI stream; a loader test proving the one plugin registers as a bean and `/api/plugins` lists it. Reuses the existing JUnit 5 setup (67 tests already green).
- **Frontend:** Vitest for the API client + Pinia stores; a component test that the MF host mounts and unmounts a fake plugin module.
- **Integration (walking-skeleton acceptance test):** start backend, start frontend, open the Base64 plugin, run encode, assert the result. This single end-to-end smoke is the definition of Phase 1 done.

### Error handling

- Backend down → frontend shows a reconnect banner; SSE auto-retries.
- Plugin MF `import()` failure → error card in the content area; the shell stays alive.
- Sidecar fails the health check → Tauri shows a fatal-error window with the backend log path.
- Plugin `invoke` error → standard `{"success":false,"error":"..."}` contract; frontend surfaces it in the plugin's UI via the scoped api client.

---

## 10. Definition of done (Phase 1)

1. `java -jar ZhiFlow.jar --port=0` boots a headless Spring Boot server on loopback, no JavaFX window, prints its port.
2. `/api/health`, `/api/plugins`, `/api/settings`, `/api/ai/*`, `/api/plugins/base64/invoke`, `/plugin-ui/base64/*` all respond correctly.
3. The Base64 official plugin exists as a reactor Maven module, registers as a Spring bean, and appears in `/api/plugins`.
4. The Vue shell renders the sidebar / theme (dark + light) / settings / AI chat, adopting the `docs/ui-design/` tokens.
5. AI chat streams tokens + collapsible thinking over SSE.
6. Opening Base64 in the shell dynamically imports and mounts its micro-frontend, and encode/decode works against the backend.
7. A Tauri dev window sidecar-launches the jar, waits on health, and shows the whole thing.
8. Backend unit/integration tests green; frontend Vitest green; the end-to-end smoke passes.
9. JavaFX remains in the tree, excluded from the Phase-1 build, untouched.
