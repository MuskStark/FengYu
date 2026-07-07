# Changelog

All notable changes to SwissKitJ. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [3.2.0] — IDEA 2025 New UI Redesign

**v3.2.0** — 2026-07-07

This release re-skins the app from glassmorphism-dark to the JetBrains **IDEA 2025 New UI** look: a flat, token-based theme with switchable **dark / light** themes, a collapsible sidebar, and native OS window chrome. Theming is driven by JavaFX looked-up color tokens (`-sk-*`) declared per theme on the scene root, so a theme switch is just a root class swap — no stylesheet reload.

### ⚠️ Breaking Changes

- **`.glass-*` CSS utility classes renamed to `.sk-*`** (in `swisskit-common.css`). External plugins that call `getStyleClass().add("glass-...")` or reference `.glass-*` selectors must update. The full mapping:

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

  > The external plugin repo ([`MuskStark/SwissKiJ-Plugin`](https://github.com/MuskStark/SwissKiJ-Plugin)) is updated separately; flag this rename when migrating third-party plugins.

### 🎨 Theme (strict tokenization)

- **Token set expanded 14 → 19** — added `-sk-shadow`, `-sk-scrim`, `-sk-success-soft`, `-sk-warning-soft`, `-sk-danger-soft` (each under both `.theme-dark` and `.theme-light`). Custom themes/stylesheets that hardcoded the old 14 must add these 5 or popups/dialogs/cards will have undefined shadows and status soft-fills.
- **Fixed popups rendering as un-themed white** — `GlassNotification` (toast/notify/confirm) loaded the stylesheet but never stamped the theme class on its scene, so every `-sk-*` token was undefined and all popups fell back to JavaFX default white in both themes. Root-cause fix via `Themes.applyTo(scene)`.
- **Removed all hardcoded colors** from popups, dialogs, `StepWizard`, `ToggleSwitch`, status labels, and CSS drop-shadows. Everything now resolves through `-sk-*` tokens and adapts correctly to dark and 纯白 (light) themes. Notably `StepWizard` idle dots and `ToggleSwitch` off-track were invisible on the light theme.

### ✨ New

- **Dark / light theme system** — `fan.summer.api.theme.ThemeService` (API module, no DB dependency) holds the active `Theme.DARK`/`Theme.LIGHT`, stamps a `theme-dark`/`theme-light` class on every registered scene root, and fires `onChange` listeners. Switchable from the sidebar footer (☀/☾) and the Settings page; persisted in the `theme` setting (`dark` default).
- **Looked-up color tokens** (`-sk-bg`, `-sk-bg-elevated`, `-sk-text`, `-sk-accent`, `-sk-border`, …) declared per theme in `swisskit-common.css`; swapping the root class re-resolves every token with no stylesheet reload.
- **Collapsible sidebar** — `«`/`»` toggle between the label view and a 48px icon-strip; collapse state persisted via the `sidebar.collapsed` setting.
- **Native window chrome** — `StageStyle.DECORATED` gives the real OS title bar + close/min/max (macOS traffic lights), replacing the custom transparent window.
- `MarkdownRenderer.render(md, Theme)` / `renderPlain(md, Theme)` overloads (theme-aware dark/light CSS palettes); no-arg forms delegate via `ThemeService.current()`.

### ♻️ Changed

- `swisskit-common.css` rewritten: token definitions under `.theme-dark`/`.theme-light`, every component flattened to IDEA New UI style (neutral-gray selection with a left accent bar, slim 4–8px scrollbars, flat fields/buttons/tables/tabs/dialogs/notifications), all `.glass-*` → `.sk-*`.
- `shell.css` rewritten token-based for the New UI shell (`.app-root`, `.sidebar` + `.collapsed`, capsule `.search-bar`, flat `.tool-card`, `.detail-panel`, `.statusbar`, `.store-*`).
- `Themes.applyTo(scene)` now delegates to `ThemeService.registerScene(scene)` (loads the common stylesheet + stamps the theme class); the shared stylesheet load is factored into `Themes.loadCommonStylesheet(scene)` to keep the delegation non-recursive.
- `SwissKitJApp` reads the persisted theme on startup and registers the main scene with `ThemeService`.
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
- **`BuiltinAiToolRegistrar` removed** — plugins now self-register AI tools via `SwissKitJPlugin.aiTools()`; the central registrar and its startup call are gone.

### ✨ New

- **Plugins self-declare AI tools** via `SwissKitJPlugin.aiTools()` — the registry auto-registers/unregisters them on add/remove (including JAR hot-reload). No central registrar.
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
- **Unified `ChatBackend` interface** in `SwissKitJ-Api` — non-sealed (Java forbids cross-module sealed permits). Two known implementors: `CloudChatBackend`, `LocalChatBackend`. UI consumers use `instanceof` checks; the interface itself is treated as opaque.
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

**v3.0.0-rc.3** — 2026-06-10

- **Slash Commands**: Type `/` in AI chat to list available tools, get help on a specific tool, or invoke a tool directly without model inference — supports both direct execution and guided model parameter extraction
- **Plugin Resource Isolation**: Child-first `ClassLoader` for external plugins ensures plugin resources are resolved from the plugin JAR before the host; `PluginContext` provides TCCL switching on every plugin lifecycle call and event dispatch
- **Plugin Store Redesign**: Searchable, filterable card grid for the online plugin store with install state indicators and version comparison
- **AI Configuration Service**: Extracted `AiConfigService` centralizes AI configuration access, decoupling it from UI settings code
- **Email Archive**: New `email_archive` table, entity, and mapper for email archive storage
- Fix sidebar icons not displaying on Windows — switched from JavaFX `Font` icons to MDI webfont
- Fix email settings save always failing; now shows missing required field names
- Fix Excel complex split Phase 3 corrupting pre-existing output files — only merge into files created during the split operation
- Fix POI `NullPointerException` during cross-workbook cell style cloning when data format string is null
- Harden Excel Splitter progress callback with null guard
- Extract `StorePlugin` and `StorePluginLogic` from `OnlineStorePane` with unit tests
- Add GPLv3 license file to the repository
- Add JUnit 5 test dependency to `SwissKit` module

---

**v3.0.0-rc.2** — 2026-06-05

- **Tool Favorites**: Bookmark tools with a star toggle on tool cards and the detail panel; favorites persist across restarts via H2 database and are filterable from the sidebar "Favorites" category
- **Lazy AI Backend**: Local AI backend (native/Java) initialization is deferred until the AI tool is first opened, improving startup performance; Java/Native inference engine toggle in AI settings
- **Plugin Uninstall**: Uninstall external plugins from the detail panel with confirmation dialog; closes ClassLoader, removes JAR file, and cleans up from registry
- **Install Toast Notifications**: Success toast notification when a plugin is installed from the online store or local JAR
- **Token Batching**: AI token output is batched at 50ms intervals to reduce FX thread flooding during high-speed generation
- **Crash Rate Limiting**: Native worker auto-restart respects a time window (3 crashes within 5 min) to prevent restart storms
- **Settings Cache**: App settings are cached in memory with debounced DB writes (300ms) to reduce database load during rapid UI interaction
- Fix native library loading on hardened Linux distros (UOS/Deepin/Kylin) where `SecurityException` is thrown for unsigned `.so` files
- Fix email batch sending mutating shared recipient lists across iterations
- Fix online store plugin catalog parsing — replaced hand-rolled string slicing with Gson-based `JsonHelper`
- Fix `WindowResizeHelper` double-attachment causing duplicate event filters
- Thread-safety hardening across `PluginLoader`, `PluginRegistry`, and `MainWindow` (`ConcurrentHashMap`, `volatile`, `synchronizedSet`)
- Stagger limit for tool card entry animations (max 30) to avoid creating hundreds of `PauseTransition` instances
- Fix plugin JAR deletion on Windows — retry with `System.gc()` hint, fall back to `deleteOnExit()` if file is still locked
- Fix `onUnload()` lifecycle callback not fired when unloading plugin JARs
- Fix cached plugin view not cleared when uninstalling an inactive plugin, preventing GC of plugin classes
- Fix English locale (`Locale.ENGLISH`) returning Chinese strings on Chinese-locale systems — `ResourceBundle` no longer falls back to JVM default locale
- Fix Windows no-JRE release zip redundantly including the fat JAR alongside the Launch4j exe (which already embeds it)

---

**v3.0.0-rc.1** — 2026-06-04

- **Browser Automation**: AI-callable `browser_automate` tool that automates web browsers via natural language; uses Playwright with the system's installed Chrome/Edge/Chromium (no separate browser download); observe-think-act loop with page DOM snapshots, CSS selector targeting, and a planner LLM
- **Resizable Window**: Edge and corner drag resize for the undecorated `StageStyle.TRANSPARENT` window via `WindowResizeHelper`; uses screen coordinates for macOS compatibility
- **Responsive Layout**: Dynamic `FlowPane` wrap length bound to viewport width; `windowPane` and `ContentArea` properly fill parent with `setMaxWidth/Height(Double.MAX_VALUE)`
- **Pure Java PDF-to-DOCX**: `PdfBoxToDocxConverter` using PDFBox for extraction and Apache POI for DOCX generation — no external Office installation required; three-tier page strategy (text → extracted images → full-page render fallback)
- **Native Backend Health Tracking**: `NativeLoader.FailureReason` enum for structured failure diagnostics; degraded-mode banner in AI chat when native acceleration is unavailable
- Fix macOS window resize not working due to unreliable `stage.isMaximized()` with `StageStyle.TRANSPARENT`
- Fix tool grid layout not responsive to window width changes
- Fix Playwright runtime attempting to download browser driver unnecessarily
- Fix AI browser planner recursively invoking `browser_automate` tool via tool injection loop

---

**v3.0.0-beta.2** — 2026-05-26
