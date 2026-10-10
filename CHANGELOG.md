# Changelog

All notable changes to FengYu. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [4.0.1] — 2026-10-10

### 🐛 Fixed
- **Plugin database authorization works on H2 hosts.** The setup wizard creates the embedded H2
  database through a blank-username connection, so the database's only admin is the empty-name
  user and the wizard-prefilled admin username (`sa`) does not exist — every "Authorize database"
  action failed with 500 ("Wrong user name or password"). Provisioning now falls back to the main
  datasource credentials when the configured admin login is rejected on H2 (MySQL/PostgreSQL keep
  requiring explicit admin credentials), and an H2 host whose in-process TCP server failed to
  start takes the embedded file-level isolation path instead of a guaranteed file-lock failure.
- **Provisioned plugin workers survive a host restart on H2.** The H2 TCP server rebinds a new
  dynamic port every boot, and the worker JDBC URL persisted at authorization time pointed at the
  dead port after one restart. The runtime now rebuilds the worker URL from the live datasource
  configuration at worker spawn (MySQL/PostgreSQL rebuild to the identical URL), keeping the
  record's schema name as the durable namespace coordinate.
- **The bundled HTML sanitizer carries no known advisories.** DOMPurify moved from 3.4.14 to
  3.4.16, past two IN_PLACE-hook DOM-XSS advisories (GHSA-p98j-92pf-mc4p, GHSA-6688-9rhm-gjv2)
  that the release dependency-audit gate blocks on.

### ♻️ Changed
- **The dependency-boundary gate reads the app revision from the root pom.** The check that pins
  `.mvn/maven.config` to the reactor revision no longer hard-codes a version literal that every
  release bump had to remember to hand-edit (v4.0.1 was the first to trip it).

---

## [4.0.0] — 2026-09-26

### 🐛 Fixed
- **Official store connectivity follows the production domain-root API (4.1.0 backport).** The
  default store base is now `https://www.infinia.fyi` — production serves the store API at the
  domain root (`/api/v1/...`) and the former `/store` path prefix no longer exists, so the old
  bootstrap base could not reach the live store. Catalog aggregation now follows the full cursor
  chain at the store's 100-row page cap (30 pages ≈ 3000 rows of headroom over the 1300+ live
  listings); the previous 5×60 bound left everything past row 300 invisible. The Settings copy and
  configuration docs follow the same base, and the dev-context store host-version fallback now
  reports `4.0.0` on this line.
- **Plugin screen capture is manifest-gated instead of silently unavailable.** The security
  hardening that removed undeclared iframe media grants also removed `display-capture`, so plugin
  UIs such as FY-QRSync failed `getDisplayMedia` before Electron's screen-only handler could run.
  A new canonical `screen.capture` manifest permission now restores that capability only for
  plugins that declare it: the host adds `allow="display-capture"` to their iframe, while camera,
  microphone, window capture, and every undeclared plugin remain denied.
- **Windows portable updates now obey the signed-release gate.** The custom portable replace
  pipeline no longer downloads, extracts, or arms its updater script in an unsigned build; it
  falls back to the manual download page before any feed lookup or consent dialog.
- **Imported MCP servers require an informed enable step.** Servers contributed by installed
  agent-content plugins can no longer be switched on from the summary row. FengYu shows the plugin
  source, exact command or URL, and imported credential names, requires explicit confirmation,
  and rejects imported private/loopback/plain-HTTP targets through the shared SSRF policy.
- **Generated artifacts can only open passive document types.** The chat-artifact Open action is
  now an allowlist: text/data, images, media, browser markup, PDF, and macro-free modern office
  documents open with the default handler; unknown or missing extensions, source code,
  shortcut/launcher formats (`.lnk`/`.url`/`.desktop` and kin), macro-capable office formats, and
  archives reveal in the file manager instead of executing the command or program named inside.
- **The official store bootstrap base is consistent.** The production default is
  `https://www.infinia.fyi/store` in the application fallback, Settings copy, and configuration
  documentation, so thin/custom launches no longer target a different API path.
- **Outbound URL policy covers all IANA special-purpose IPv4 ranges.** CGNAT, benchmarking,
  documentation, reserved, and broadcast addresses are now rejected by the default SSRF posture.
- **Oversized skill and agent-content inputs are bounded.** Installed `SKILL.md` bodies are capped
  at 1 MB, legacy oversized bodies are not retained in discovery snapshots, and third-party
  agent-content clones/skill materialization have byte and file-count budgets.
- **AI command permission rules are safer and match their documented grammar.** `Effect(command)`
  now really covers command calls, while interpreters, package runners, and common credential-file
  readers always fall back to human approval under broad command allows.
- **Browser full-page screenshots have raster-size limits.** Hostile pages declaring enormous
  layouts are rejected before Chromium allocates a screenshot buffer.

### ♻️ Changed
- **Application Java builds now use `--release 21`.** Building with a newer local JDK cannot
  silently link post-21 APIs into a Java 21 runtime.
- **CI gates every backend change and audits all JavaScript dependencies.** Backend pushes/PRs run
  the full Maven suite and boot-level smoke outside release, frontend PRs run the production Vite
  build, and release audits include development-only packaging/runtime dependencies such as
  Electron.
- **e2e-smoke polls for all four official plugins before snapshotting the runtime list.** The
  Excel/Email listing assertions reused a snapshot captured as soon as Markdown/OfflinePython had
  registered, racing the async official seed pass — two of three local runs failed on a probe CI
  had never executed (the check landed after the last tagged run).
- **The frontend Node spec suite terminates on every runner.** Two middleware-mode Vite specs
  could leave `yarn test` hanging until the job timed out: the dependency scanner died crawling
  `index.html` on CI, the dev-server warmup list leaked in and spawned a sass-embedded child
  that `vite.close()` never reaped, and both servers raced each other for the default HMR
  websocket port. The specs are now hermetic (`appType: 'custom'`, warmup cleared, `ws: false`)
  and the suite runs with a two-minute per-test timeout so any future hang fails fast instead
  of eating a job.
- **Backend CI and Plugin Tooling run green again.** Backend CI installs bubblewrap and relaxes
  Ubuntu 24.04's AppArmor userns restriction before the Maven suite — without a native process
  sandbox the plugin-runtime tests hit the deliberate fail-closed path on every push. Plugin
  Tooling installs the frontend dependencies before typechecking `@infinia/plugin-ui`, whose
  theme-equality spec resolves `vuetify` types through the frontend's node_modules.

---

## [3.2.0] — IDEA 2025 New UI Redesign

**v3.2.0** — 2026-07-07

This release re-skins the app from glassmorphism-dark to the JetBrains **IDEA 2025 New UI** look: a flat, token-based theme with switchable **dark / light** themes, a collapsible sidebar, and native OS window chrome. Theming is driven by JavaFX looked-up color tokens (`-sk-*`) declared per theme on the scene root, so a theme switch is just a root class swap — no stylesheet reload.

### ⚠️ Breaking Changes

- **`.glass-*` CSS utility classes renamed to `.sk-*`** (in `fengyu-common.css`). External plugins that call `getStyleClass().add("glass-...")` or reference `.glass-*` selectors must update. The full mapping:

  | old | new |
  |---|---|
  | `glass-dialog` | `sk-dialog` |
  | `glass-field` / `glass-field-label` | `sk-field` / `sk-field-label` |
  | `glass-tab-pane` | `sk-tab-pane` |
  | `glass-combo` | `sk-combo` |
  | `glass-table` | `sk-table` |
  | `glass-checkbox` | `sk-checkbox` |
  | `glass-btn-primary` / `glass-btn-secondary` | `sk-btn-primary` / `sk-btn-secondary` |
  | `glass-notif-*` | `sk-notif-*` |

  > The external plugin repo ([`MuskStark/FengYu-Plugin`](https://github.com/MuskStark/FengYu-Plugin)) is updated separately; flag this rename when migrating third-party plugins.

### 🎨 Theme (strict tokenization)

- **Token set expanded 14 → 19** — added `-sk-shadow`, `-sk-scrim`, `-sk-success-soft`, `-sk-warning-soft`, `-sk-danger-soft` (each under both `.theme-dark` and `.theme-light`). Custom themes/stylesheets that hardcoded the old 14 must add these 5 or popups/dialogs/cards will have undefined shadows and status soft-fills.
- **Fixed popups rendering as un-themed white** — `GlassNotification` (toast/notify/confirm) loaded the stylesheet but never stamped the theme class on its scene, so every `-sk-*` token was undefined and all popups fell back to JavaFX default white in both themes. Root-cause fix via `Themes.applyTo(scene)`.
- **Removed all hardcoded colors** from popups, dialogs, `StepWizard`, `ToggleSwitch`, status labels, and CSS drop-shadows. Everything now resolves through `-sk-*` tokens and adapts correctly to dark and light themes. Notably `StepWizard` idle dots and `ToggleSwitch` off-track were invisible on the light theme.

### ✨ New

- **Dark / light theme system** — `fan.summer.api.theme.ThemeService` (API module, no DB dependency) holds the active `Theme.DARK`/`Theme.LIGHT`, stamps a `theme-dark`/`theme-light` class on every registered scene root, and fires `onChange` listeners. Switchable from the sidebar footer (☀/☾) and the Settings page; persisted in the `theme` setting (`dark` default).
- **Looked-up color tokens** (`-sk-bg`, `-sk-bg-elevated`, `-sk-text`, `-sk-accent`, `-sk-border`, …) declared per theme in `fengyu-common.css`; swapping the root class re-resolves every token with no stylesheet reload.
- **Collapsible sidebar** — `«`/`»` toggle between the label view and a 48px icon-strip; collapse state persisted via the `sidebar.collapsed` setting.
- **Native window chrome** — `StageStyle.DECORATED` gives the real OS title bar + close/min/max (macOS traffic lights), replacing the custom transparent window.
- `MarkdownRenderer.render(md, Theme)` / `renderPlain(md, Theme)` overloads (theme-aware dark/light CSS palettes); no-arg forms delegate via `ThemeService.current()`.

### ♻️ Changed

- `fengyu-common.css` rewritten: token definitions under `.theme-dark`/`.theme-light`, every component flattened to IDEA New UI style (neutral-gray selection with a left accent bar, slim 4–8px scrollbars, flat fields/buttons/tables/tabs/dialogs/notifications), all `.glass-*` → `.sk-*`.
- `shell.css` rewritten token-based for the New UI shell (`.app-root`, `.sidebar` + `.collapsed`, capsule `.search-bar`, flat `.tool-card`, `.detail-panel`, `.statusbar`, `.store-*`).
- `Themes.applyTo(scene)` now delegates to `ThemeService.registerScene(scene)` (loads the common stylesheet + stamps the theme class); the shared stylesheet load is factored into `Themes.loadCommonStylesheet(scene)` to keep the delegation non-recursive.
- `FengYuApp` reads the persisted theme on startup and registers the main scene with `ThemeService`.
- `AiChatPlugin` derives its WebView background from the active theme and re-renders the conversation live on theme change.
- Inline `#5b8cf7` accent literals replaced with `#3574F0` / the dark palette across the sidebar and Markdown link CSS.

### 🔥 Removed

- `fan.summer.ui.titlebar.TitleBar` — replaced by native OS window chrome.
- `fan.summer.ui.util.WindowResizeHelper` — native `DECORATED` resize/drag/maximize replaces it; the macOS `isMaximized()`-on-`TRANSPARENT` bug is gone with it.

---

## [3.1.0] — LangChain4j ChatBackend + Plugin-Owned AI Tools

**v3.1.0** — 2026-06-25

This release rebuilds the AI subsystem on LangChain4j and unifies the two cloud providers (OpenAI + Anthropic) into a single `CloudChatBackend` class behind a new `ChatBackend` interface. Plugins can now self-declare their own AI tools. The local tool-calling model is Qwen3-4B (Hermes `<tool_call>` + streamed `<think>` reasoning), running in a hardened out-of-process worker.

### ⚠️ Breaking Changes

- **`AiService` interface removed** — replaced by `ChatBackend`. External plugins calling `AiServiceProvider.getService()` must change the return type from `AiService` to `ChatBackend`. See [`docs/migration-3.1.md`](migration-3.1.md) for the migration guide.
- **`OpenAiService` and `AnthropicService` concrete classes removed** — replaced by a single `CloudChatBackend` class with `openAi(...)` / `anthropic(...)` static factories. One unified class serves both providers.
- **`CloudAiConfigProvider` and standalone `StreamingResponseHandlerBridge` removed** — their logic moved into `CloudChatBackend` (config accessors are public methods on the class; the stream bridge is a private inner class).
- **`AiServiceImpl` renamed to `LocalChatBackend`** — pure rename, no behavior change.
- **`BuiltinAiToolRegistrar` removed** — plugins now self-register AI tools via `FengYuPlugin.aiTools()`; the central registrar and its startup call are gone.

### ✨ New

- **Plugins self-declare AI tools** via `FengYuPlugin.aiTools()` — the registry auto-registers/unregisters them on add/remove (including JAR hot-reload). No central registrar.
- `AiTool` interface declares per-mode visibility (`supportsLocal` / `supportsCloud`) and dual descriptions (`getDescription` / `getLocalDescription`); `AiServiceProvider.getTools()` filters by the active backend mode.
- `AiToolDescriptions` helper centralises cloud-rich / local-concise description templates.
- **Qwen3-4B local tool-calling** — Hermes `<tool_call>` parsing (`ToolCallParser`), `ThinkingStreamSegmenter` (THINK / CONTENT / tool-call stream splitting), `Qwen3Adapter` (Hermes system prompt + `/no_think` toggle), and a collapsible thinking card in the chat UI.
- New `ChatBackend` interface (`fan.summer.api.ai.ChatBackend`).
- New `CloudChatBackend` class with `openAi(...)` / `anthropic(...)` factories.
- `LocalChatBackend` (renamed from `AiServiceImpl`).
- `AiToolCall.of(id, name, arguments)` overload to preserve server-issued tool-call IDs when bridging from LangChain4j.
- Tests: `CloudChatBackendTest` (11) + adapter tests for `ChatMessageMapper` / `AiToolToToolSpecification`; `ThinkingStreamSegmenterTest` (11) + `LocalChatBackendMaxTokensTest` (3).
- Migration guide at [`docs/migration-3.1.md`](migration-3.1.md) (EN + ZH).

### ♻️ Changed

- All 16 builtin AI tools return standardized JSON `{success, summary, ...payload}`; tool descriptions follow a cloud-rich / local-concise dual template.
- `BuiltinToolRegistrar.register()` routes through `PluginRegistry.addPlugins` to auto-register plugin AI tools in one pass.
- **Unified `ChatBackend` interface** in `FengYu-Api` — non-sealed (Java forbids cross-module sealed permits). Two known implementors: `CloudChatBackend`, `LocalChatBackend`. UI consumers use `instanceof` checks; the interface itself is treated as opaque.
- **`CloudChatBackend` unifies OpenAI + Anthropic** in one class (~450 LOC). HTTP/SSE, tool-loop plumbing, and stream bridging are delegated to LangChain4j's streaming models; provider differences isolated to a `buildStreamingModel(...)` switch on an internal `Provider` enum.
- `SynchronousChatHelper` (browser planner) rewritten to use LC4j's synchronous `OpenAiChatModel` directly via `CloudChatBackend` config accessors.
- `AiServiceProvider` exposes `ChatBackend` everywhere (method names unchanged).
- Sampling parameters (temperature / topP / maxTokens) are honoured per-call — settings changes take effect on the next message without restarting the chat.
- Default `maxTokens` raised 512 → 2048 (the Qwen3 thinking-model floor), enforced once at the `chat()` entry so both the native and Java backends benefit.

### 🐛 Fixes

- **Qwen3 silent empty answer** — a thinking model truncated mid-`<think>` produced an empty answer because `stripThink` wiped the unclosed block. The `maxTokens` budget is now floored to `QWEN3_MIN_MAX_TOKENS` (2048) at the unified `chat()` entry, with a diagnostic warning when output survives only as a think block.
- **Qwen3 on the Java backend** leaked raw `<think>` tags into the answer — now routed through `ThinkingStreamSegmenter` (thinking → collapsible card) and stripped from the final answer/history, matching the native path.
- **`AiConfigService.getAiMaxTokens()` default** synced to 2048 (was a stale 512 that disagreed with the settings UI).
- **AI worker IPC** — the child process pins a dedicated `logback-worker.xml` (no `ConsoleAppender`) so worker logs no longer corrupt the line-delimited JSON pipe on stdout; stderr is drained on its own thread into the shared log.
- **AI worker native load** — the child JVM loads the llama.cpp library at startup (`NativeLoader.load()`) so `LlamaContext` construction no longer throws "Native library not loaded".
- **AI worker crash recovery** — `handleChildExit` waits for a real exit code instead of throwing `IllegalThreadStateException` on stdout EOF, so pending callbacks are released and auto-restart runs reliably.
- **Qwen3.5 hybrid-model warning** — filenames matching `qwen3.5` / `qwen35` now warn that the native worker is known to SIGABRT on multi-turn (use Qwen3-4B).
- **Cloud `testConnection()` null-message bug on macOS** — `ConnectException` with a `null` message now falls back to `e.getClass().getSimpleName() + ": " + e`.
- **Anthropic multi-round tool calling** — server-issued `tool_use_id` preserved through the `AiToolCall → LangChain4j → AiToolCall` round-trip (previously caused HTTP 400 on round 2).
- **Multi-turn conversation continuity** — the assistant's final reply is appended to `history` before the service returns.
- **OpenAI tool-round message ordering** — the assistant-with-tools message is appended before `ToolExecutor.executeAndFeed`.
- `pdf_merge.filePaths` parameter type fixed (`"array"` → `"string[]"`); enums declared for `base64.mode`, `hash_calculate.algorithm`, `color_convert.from/to`.
- `ToolExecutor` error output is always JSON `{success:false,error:...}`; `ExcelConfigureTool` success returns `success:true`.
- `testConnection()` `HttpClient` wrapped in try-with-resources; thread-safety hardening on the cloud stream handler.

### 🔥 Removed

- `BuiltinAiToolRegistrar` — superseded by plugin-owned `aiTools()`.
- FunctionGemma adapter and `OfflineNlNormalizer` — replaced by the Qwen3 path.

### ⬆️ Dependencies

- `dev.langchain4j:langchain4j-open-ai:1.2.0`
- `dev.langchain4j:langchain4j-anthropic:1.2.0`
- (1.0.1 was originally pinned but `langchain4j-anthropic` was never published at that version; bumped to the lowest GA where both modules co-exist)

### ⚠️ Known Behavior Changes

- `cancelGeneration()` on cloud backends is best-effort (LangChain4j 1.x does not expose mid-stream cancellation on streaming models); the in-progress flag is still cleared. Local mode is unaffected.
- Mid-stream SSE errors now surface via `callback.onError` on the JavaFX Application Thread.
- The local tool-calling model is Qwen3-4B; the native worker requests full GPU offload automatically on builds that ship a GPU backend.

### 📉 Net Code Change

- Deleted: `AiService` (117 LOC), `OpenAiService` (244 LOC), `AnthropicService` (283 LOC), `CloudAiConfigProvider` (22 LOC), `StreamingResponseHandlerBridge` (120 LOC), `StreamingResponseHandlerBridgeTest` (214 LOC), `BuiltinAiToolRegistrar`, FunctionGemma adapter + `OfflineNlNormalizer` ≈ **1000+ LOC removed**.
- Added: `ChatBackend` (86 LOC), `CloudChatBackend` (450 LOC), the Qwen3 toolchain (`ThinkingStreamSegmenter`, `Qwen3Adapter`, `ToolCallParser`), worker hardening, tests, migration guides ≈ **1100+ LOC added**.
- Net: roughly even on LOC, but cloud code is one unified class and local AI has a dedicated tool-calling model + isolated worker.

---

## [3.0.1] — FunctionGemma Offline Adaptation

**v3.0.1** — 2026-06-21

### ✨ New Features

- **FunctionGemma Multi-Round Tool Loop**: Host-driven `analyze → configure → execute` loop for the FunctionGemma-270m-it local model; tool-call tokens are suppressed during call rounds and only the final response is forwarded to the UI
- **Offline CN→EN Keyword Normalizer**: `OfflineNlNormalizer` rewrites Chinese tool-name keywords to English before local-model parsing, no network required (resource-backed `nl-normalizer.properties`)
- **Enum-Schema Tool Parameters**: `AiToolParam` gains an `enumValues` field; tool declarations now emit `enum:[...]` constraints to FunctionGemma, OpenAI, and Anthropic backends — materially improves small-model parameter reliability
- Enriched Excel AI tool descriptions and added enum constraints on `mode`/`action` parameters

### 🐛 Fixes

- Harden `FunctionGemmaAdapter` parser: 🪙 (U+1FA99) string delimiter correctly handles values containing commas, braces, and multiple tool calls in a single response
- Release `GGUFModel` mmap on unload via best-effort `unmap`
- Harden `GGUFReader` against malformed or truncated model files
- Serialise `PluginLoader` JAR load/unload on a single-thread scheduler
- Complete `LlamaRunner` generation cleanly when cancelled during prefill
- Drive `TokenBatcher` flushes off the FX thread
- Let the native AI worker exit gracefully before force-killing it
- Close target POI `Workbook` in `ExcelUtil` even when copy/write throws
- Low-priority stability cleanup (MDI font log, daemon UI threads)

---

## [3.0.0] — JavaFX Migration

**v3.0.0** — 2026-06-12

- Update app icons for v3.0.0 release
- Resolve static analysis warnings across codebase (Qodana)
