# Changelog

All notable changes to ZhiFlow. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [3.1.0] — LangChain4j + ChatBackend Unification

**v3.1.0** — 2026-06-21

This release rebuilds the AI subsystem on LangChain4j and unifies the two cloud providers (OpenAI + Anthropic) into a single `CloudChatBackend` class behind a new `ChatBackend` interface. Local mode (in-process GGUF) is renamed to `LocalChatBackend` but otherwise unchanged.

### ⚠️ Breaking Changes

- **`AiService` interface removed** — replaced by `ChatBackend`. External plugins calling `AiServiceProvider.getService()` must change the return type from `AiService` to `ChatBackend`. See [migration-3.1.md](migration-3.1.md) for the migration guide.
- **`OpenAiService` and `AnthropicService` concrete classes removed** — replaced by a single `CloudChatBackend` class with `openAi(...)` / `anthropic(...)` static factories.
- **`CloudAiConfigProvider` and standalone `StreamingResponseHandlerBridge` removed** — their logic moved into `CloudChatBackend`.
- **`AiServiceImpl` renamed to `LocalChatBackend`** — pure rename, no behavior change.

### ♻️ Changed

- **Unified `ChatBackend` interface** in `ZhiFlow-Api` — non-sealed (Java forbids cross-module sealed permits). Two known implementors: `CloudChatBackend`, `LocalChatBackend`. UI consumers use `instanceof` for backend-specific behavior.
- **`CloudChatBackend` unifies OpenAI + Anthropic** in one class (~450 LOC). HTTP/SSE, tool-loop plumbing, and stream bridging entirely delegated to LangChain4j's `OpenAiStreamingChatModel` / `AnthropicStreamingChatModel`. Provider differences isolated to a `buildStreamingModel(...)` switch on an internal `Provider` enum.
- `SynchronousChatHelper` (browser planner) rewritten to use LC4j's synchronous `OpenAiChatModel` directly via `CloudChatBackend` config accessors.
- `AiServiceProvider` exposes `ChatBackend` everywhere. Method names unchanged.
- Sampling parameters (temperature / topP / maxTokens) honoured per-call instead of baked into a cached model.

### ✨ New

- New `ChatBackend` interface
- New `CloudChatBackend` class with `openAi(...)` / `anthropic(...)` factories
- `LocalChatBackend` (renamed from `AiServiceImpl`)
- `AiToolCall.of(id, name, arguments)` overload to preserve server-issued tool-call IDs
- `CloudChatBackendTest` (11 tests) + adapter tests for `ChatMessageMapper` / `AiToolToToolSpecification`
- Migration guide at [migration-3.1.md](migration-3.1.md) (EN + ZH)

### 🐛 Fixes

- **`testConnection()` null-message bug on macOS**: `ConnectException` from an unreachable endpoint carried a `null` message on macOS JDKs, causing `testConnection()` to return `null` (interpreted as success by the Settings UI). Now falls back to `e.getClass().getSimpleName() + ": " + e`.
- **Anthropic multi-round tool calling**: server-issued `tool_use_id` is now preserved through the `AiToolCall → LangChain4j → AiToolCall` round-trip — previously a fabricated local ID caused HTTP 400 on tool round 2.
- **Multi-turn conversation continuity**: the assistant's final reply is now appended to `history` before the service returns.
- **OpenAI tool-round message ordering**: the assistant-with-tools message is now appended before `ToolExecutor.executeAndFeed`, satisfying the API contract that `tool` messages must follow an `assistant` with `tool_calls`.
- `testConnection()` `HttpClient` wrapped in try-with-resources (Java 21 `AutoCloseable`).
- Thread-safety hardening on the cloud stream handler (`StringBuffer` accumulator, `volatile` fields).

### ⬆️ Dependencies

- `dev.langchain4j:langchain4j-open-ai:1.2.0`
- `dev.langchain4j:langchain4j-anthropic:1.2.0`
- (1.0.1 was originally pinned but `langchain4j-anthropic` was never published at that version; bumped to the lowest GA where both modules co-exist)

### ⚠️ Known Behavior Changes

- `cancelGeneration()` on cloud backends is now best-effort (LangChain4j 1.x does not expose mid-stream cancellation on streaming models); the in-progress flag is still cleared. Local mode is unaffected.
- Mid-stream SSE errors now surface via `callback.onError` on the JavaFX Application Thread.

### 📉 Net Code Change

- Deleted: ~1000 LOC (5 obsolete classes + 1 obsolete test)
- Added: ~1100 LOC (new unified types + tests + migration guides)
- Net: roughly even on LOC, but cloud code is now one unified class instead of two parallel implementations.

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

### ✨ New Features
- **Slash Commands**: Type `/` in AI chat to list available tools, get help on a specific tool, or invoke a tool directly without model inference — supports both direct execution and guided model parameter extraction
- **Plugin Resource Isolation**: Child-first `ClassLoader` for external plugins ensures plugin resources are resolved from the plugin JAR before the host; `PluginContext` provides TCCL switching on every plugin lifecycle call and event dispatch
- **Plugin Store Redesign**: Searchable, filterable card grid for the online plugin store with install state indicators and version comparison
- **AI Configuration Service**: Extracted `AiConfigService` centralizes AI configuration access, decoupling it from UI settings code
- **Email Archive**: New `email_archive` table, entity, and mapper for email archive storage

### 🔧 Fixes
- Fix sidebar icons not displaying on Windows — switched from JavaFX `Font` icons to MDI webfont
- Fix email settings save always failing; now shows missing required field names
- Fix Excel complex split Phase 3 corrupting pre-existing output files — only merge into files created during the split operation
- Fix POI `NullPointerException` during cross-workbook cell style cloning when data format string is null
- Harden Excel Splitter progress callback with null guard

### ♻️ Changes
- Extract `StorePlugin` and `StorePluginLogic` from `OnlineStorePane` with unit tests
- Add GPLv3 license file to the repository
- Add JUnit 5 test dependency to `ZhiFlow` module

---

**v3.0.0-rc.2** — 2026-06-05

### ✨ New Features
- **Tool Favorites**: Bookmark tools with a star toggle on tool cards and the detail panel; favorites persist across restarts via H2 database and are filterable from the sidebar "Favorites" category
- **Lazy AI Backend**: Local AI backend (native/Java) initialization is deferred until the AI tool is first opened, improving startup performance; Java/Native inference engine toggle in AI settings
- **Plugin Uninstall**: Uninstall external plugins from the detail panel with confirmation dialog; closes ClassLoader, removes JAR file, and cleans up from registry
- **Install Toast Notifications**: Success toast notification when a plugin is installed from the online store or local JAR
- **Token Batching**: AI token output is batched at 50ms intervals to reduce FX thread flooding during high-speed generation

### 🔧 Fixes
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
### ✨ New Features
- **Browser Automation**: AI-callable `browser_automate` tool that automates web browsers via natural language; uses Playwright with the system's installed Chrome/Edge/Chromium (no separate browser download); observe-think-act loop with page DOM snapshots, CSS selector targeting, and a planner LLM
- **Resizable Window**: Edge and corner drag resize for the undecorated `StageStyle.TRANSPARENT` window via `WindowResizeHelper`; uses screen coordinates for macOS compatibility
- **Responsive Layout**: Dynamic `FlowPane` wrap length bound to viewport width; `windowPane` and `ContentArea` properly fill parent with `setMaxWidth/Height(Double.MAX_VALUE)`
- **Pure Java PDF-to-DOCX**: `PdfBoxToDocxConverter` using PDFBox for extraction and Apache POI for DOCX generation — no external Office installation required; three-tier page strategy (text → extracted images → full-page render fallback)
- **Native Backend Health Tracking**: `NativeLoader.FailureReason` enum for structured failure diagnostics; degraded-mode banner in AI chat when native acceleration is unavailable
### 🔧 Fixes
- Fix macOS window resize not working due to unreliable `stage.isMaximized()` with `StageStyle.TRANSPARENT`
- Fix tool grid layout not responsive to window width changes
- Fix Playwright runtime attempting to download browser driver unnecessarily
- Fix AI browser planner recursively invoking `browser_automate` tool via tool injection loop

---

**v3.0.0-beta.2** — 2026-05-26

### ✨ New Features

- **AI Remote Backends**: Switch between local GGUF, OpenAI Chat Completions, and Anthropic Messages API via a global selector in AI settings; supports SSE streaming with token-by-token delivery and tool calling on all backends
- **AI Native Worker**: Out-of-process JNI inference via `NativeWorkerClient`/`NativeWorkerMain` child process for crash isolation and thread safety; stats callback overload on `GenerateCallback.onDone`
- **FunctionGemma Adapter**: Native tool calling protocol adapter for FunctionGemma models with custom stop sequences and single-round tool loop integration in `AiServiceImpl`
- **AI Built-in Tools**: Base64 encode/decode, Hash calculator (MD5/SHA-256/SHA-512), JSON format/validate, and Color converter (HEX/RGB/HSL) — all registered via `BuiltinAiToolRegistrar` with `ToolExecutor` and `ToolSchemaBuilder` utilities
- **AI Markdown Rendering**: AI responses render Markdown via `WebView` with dark theme (#1e1e2e) and auto-resize height to content
- **AI→Excel Tools**: Analyze, configure, execute, cancel, query, and complex-config AI tools that allow the AI chat to operate the Excel Splitter; includes auto-analyze on file drop/pick and cancel support
- **AI Auto-Initialize**: Configured AI backend (including remote API mode) activates automatically on startup without manual re-configuration
- **PDF Tools**: Split, merge, and convert-to-Word (via WPS or documents4j) with `OfficeDetector` auto-detection; 3-tab UI registered as a built-in tool; AI tools for all three PDF operations
- **Email Archive**: Built-in email archive tool with IMAP support (`EmailArchivePlugin`, `EmailArchiveService`), address book pane, and expanded mass-send service
- **Plugin Background Execution**: Plugins can run tasks in the background with view caching and a ToolCard indicator showing running status
- **Plugin Preview Window**: Self-contained preview shell for third-party plugin developers with `PreviewTitleBar`, `PreviewSidebar`, `PreviewToolCard`, `PreviewDetailPanel`, and `zhiflow-preview.css`
- **GlassNotification**: Glassmorphism-styled notification component replacing all `Alert` dialogs
- **Application Icons**: Native-resolution application icons for macOS (.icns), Windows (.ico), and Linux (.png)
- **Built-in Tools**: Base64 encoder/decoder, Hash Calculator, JSON Formatter, Color Converter, and Markdown Editor plugins registered as built-in tools
- **I18n Framework**: Core `I18n` classes in ZhiFlow-Api with DB-persisted locale, plugin bundle registration/unregistration, and live language switching across all UI components (TitleBar, MainWindow, Sidebar, ContentArea, ToolCard, DetailPanel, Settings)
- **Settings UI**: Redesigned settings with AI, Email, and Address Book tabs
- **Three-Layer CSS**: `zhiflow-common.css` (shared variables and glass-* utilities), `shell.css` (app shell), and `builtin.css` (built-in tools) with scene-graph inheritance
- **Type-Safe Enums**: `ToolCategory`, `ToolType`, and `IconStyle` enums in ZhiFlow-Api replacing String-based metadata
- **GGUFZ Support**: Accept `*.ggufz` compressed model files in the model file chooser
- **Gson/JsonHelper**: `JsonHelper` utility (Gson-based) replaces `JsonBuilder`/`JsonParser`; `ToolCallParser` and all services use Gson
- **Bilingual Docs**: English/Chinese documentation with docsify-flexible-i18n; complete English Javadoc on all public APIs

### 🔧 Fixes

- Fix AI backend not activating on restart — stale `MainWindow` initialization was overwriting the configured service
- Fix `NativeWorkerClient` thread safety and reset crash counter on successful generation, not model load
- Fix plugin i18n bundles returning host translations due to ClassLoader parent delegation
- Fix ToolCard background indicator not showing and preview i18n not working
- Fix ExcelSplitterPlugin missing `hasRunningTasks` implementation
- Fix WebView white background in AI message bubbles — use dark theme #1e1e2e with rounded corners
- Fix AI message bubble height — auto-resize WebView to match content instead of oversized default
- Fix JSON Schema array type handling in tool parameters
- Fix VBox→HBox type mismatch in email tab field rows
- Fix email editor — expand WebView height and allow rich-text paste from Word
- Fix Settings UI not reflecting language change — rebuild on locale switch
- Fix plugin storage path — moved to `.zhiflow/plugin/` and fixed install-then-load failure
- Fix Windows JAR discovery and release artifact path in CI
- Fix cross-platform JavaFX native library bundling in fat JAR

### ♻️ Changes

- Extract JNI inference to out-of-process `NativeWorkerClient` for crash isolation
- Refactor AI services (`OpenAiService`, `AnthropicService`, `AiServiceImpl`) to use shared tool registry, Gson, and `JsonHelper`
- Delete `JsonBuilder` and `JsonParser`, fully replaced by Gson/`JsonHelper`
- Move tool registry to `AiServiceProvider`, delete standalone `ToolRegistry`
- Decouple all module POMs to standalone (no parent inheritance)
- Polish plugin logging API, metadata, and shared components
- Migrate official plugins to separate `SwissKiJ-Plugin` repository
- Centralize dependency management and add PDFBox, documents4j dependencies
- Add stats overload to `GenerateCallback.onDone` for inference metrics
- Bump GitHub Actions to v5 for Node.js 24 compatibility

---

**v3.0.0-beta.1** — 2026-05-24

### ✨ New Features

- **I18n Framework**: Core `I18n` classes in ZhiFlow-Api with DB-persisted locale, plugin bundle registration/unregistration, and live language switching

### ♻️ Changes

- Convert all UI components (TitleBar, MainWindow, Sidebar, ContentArea, ToolCard, DetailPanel) to use I18n
- Complete i18n conversion for Settings UI (AI, Email, Address Book tabs)
- Complete i18n for all built-in tools and plugin store UI

### 🔧 Fixes

- Fix VBox→HBox type mismatch in email tab field rows
- Fix email editor — expand WebView height and allow rich-text paste from Word
- Rebuild Settings UI on locale change for live language switch

---

**v3.0.0-alpha.2** — 2026-05-21

### ✨ New Features

- **AI Chat**: Built-in tool for chatting with local GGUF models; supports Q3_K/Q5_0/Q4_0/Q8_0/IQ4_NL dequantization, streaming inference, tool calling, and chat session management
- **JNI Native Inference**: C++ `llama_jni` native layer with `GenerateCallback`, `LlamaContext`, `ModelParams`, `GenerateParams` bindings; bundles `libllama_jni-aarch64.dylib` for macOS
- **Tool Calling API**: `AiTool`, `AiToolCall`, `AiToolParam`, `AiToolResult` in the API module; `ToolCallParser` and `ToolRegistry` in the host
- **Chat Sessions**: `ChatSession` class managing message history and context

---

**v3.0.0-alpha.1** — 2026-05-19

### ✨ Highlights

Complete migration from Swing/FlatLaf to **JavaFX 21** with a glassmorphism dark theme, redesigned plugin API, and rebuilt Excel Splitter.

### ✨ New Features

- **JavaFX UI**: Custom window chrome (`StageStyle.TRANSPARENT`), glassmorphism sidebar, glow-effect ToolCards, animated step wizard
- **Plugin API v3**: `SwissKitJPlugin` interface replaces `KitPage`; plugins return JavaFX `Node`
- **Plugin Logger**: `LoggerFactory` with SLF4J/Logback backbone; safe no-op in tests
- **StepWizard**: Reusable multi-step wizard with dot navigation, slide transitions, validation
- **Plugin Store**: Online catalog + local JAR install with hot-reload
- **Three-Layer CSS**: `zhiflow-common.css`, `shell.css`, `builtin.css` with scene-graph inheritance
- **Type-Safe Enums**: `ToolCategory`, `ToolType`, `IconStyle` replace String-based metadata
- **Excel Splitter Wizard**: Redesigned with 4-step `StepWizard` flow

### 🔧 Fixes

- Fat JAR bundles JavaFX native `.dll` / `.so` / `.dylib` for all platforms

---

## [2.x] — Swing Era (Stable)

### v2.1.1 — 2026-05-07

- Fix: persist language setting and improve plugin classloader

### v2.1.0 — 2026-05-07

- Feat: i18n `panelMethod` attribute and Settings i18n refresh

### v2.0.2 — 2026-05-06

- Feat: i18n support for official plugins

### v2.0.1 — 2026-05-06

- Feat: internationalization system with English and Chinese support
- Feat: required `pluginName`/`pluginVersion` in `@SwissKitPage`
- Refactor: plugin registry and annotation-based plugin discovery

### v2.0.0 — 2026-04-20

- **Breaking**: remove `KitPage` interface, annotation-only plugin discovery
- Feat: menu click navigation and drag-to-reorder functionality

---

## [1.x] — Swing Era (Initial)

### v1.2.2 — 2026-04-15

- Feat: Mouse plugin (KeepMove to prevent screen saver)
- Fix: handle plugin loading errors gracefully

### v1.2.1 — 2026-04-08

- Feat: Excel copy entire sheet to all split files
- Fix: HappyLearning lesson type code, skip class, UI updates
- Fix: improve plugin uninstall reliability on Windows

### v1.2.0 — 2026-04-01

- Feat: Email rich text editor with formatting toolbar
- Feat: HappyLearning skip class button

### v1.1.0 — 2026-03-31

- **Feat: Plugin hot-deployment** — deploy, reload, and uninstall without restart
- Feat: HappyLearning class hours tracking and status display

### v1.0.0 — 2026-03-28

Initial stable release.

- Excel Kit: complex split, configuration editor, progress tracking
- Email Kit: mass sending, address book, tag management, sent log
- Plugin System: auto-discovery via Java SPI, isolated ClassLoader
- Settings: unified configuration, plugin management
- Cross-platform: Windows, Linux, macOS (Apple Silicon)
- Database: H2 embedded + MyBatis

---

## Pre-release

### v1.0.0-RC.1 — 2026-03-28

- 11 bug fixes: EDT violations, NPE guards, resource leaks, silent failures

### v1.0.0-Beta.4 — 2026-03-27

- Plugin management UI, HappyLearning lesson display
- EDT violation fixes, resource leak fixes, NPE guards

### v1.0.0-Beta.3 — 2026-03-27

- Email address book double-click editing

### v1.0.0-Beta.2 — 2026-03-27

- IsolatedPluginClassLoader JDK delegation fix

### v1.0.0-Beta.1 — 2026-03-26

- Error dialog on email send failure

### v1.0.0-Alpha.5 — 2026-03-26

- ZhiFlow-Api module, email sent log viewing

### v1.0.0-Alpha.4 — 2026-03-26

- Email progress bar, tag name display

### v1.0.0-Alpha.3 — 2026-03-26

- Tag association refactored to use tag ID

### v1.0.0-Alpha.2 — 2026-03-25

- Email sending, mass sending, tag-based attachments

### v1.0.0-Alpha.1 — 2026-03-24

- Excel complex split, email address book, tag management, plugin loading
