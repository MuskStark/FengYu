# Changelog

All notable changes to SwissKitJ. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [3.0.0] — JavaFX Migration

**v3.0.0-beta.2** — 2026-05-26

### ✨ New Features

- **AI Remote Backends**: Switch between local GGUF, OpenAI Chat Completions, and Anthropic Messages API via a global selector in AI settings; supports SSE streaming with token-by-token delivery and tool calling on all backends
- **Plugin Background Execution**: Plugins can run tasks in the background with view caching and a ToolCard indicator showing running status
- **AI Auto-Initialize**: Configured AI backend (including remote API mode) activates automatically on startup without manual re-configuration

### 🔧 Fixes

- Fix AI backend not activating on restart — stale `MainWindow` initialization was overwriting the configured service
- Fix plugin i18n bundles returning host translations due to ClassLoader parent delegation
- Fix ToolCard background indicator not showing and preview i18n not working
- Fix ExcelSplitterPlugin missing `hasRunningTasks` implementation

---

**v3.0.0-beta.1** — 2026-05-24

### ✨ New Features

- **I18n Framework**: Core `I18n` classes in SwissKitJ-Api with DB-persisted locale, plugin bundle registration/unregistration, and live language switching

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
- **Three-Layer CSS**: `swisskit-common.css`, `shell.css`, `builtin.css` with scene-graph inheritance
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

- SwissKitJ-Api module, email sent log viewing

### v1.0.0-Alpha.4 — 2026-03-26

- Email progress bar, tag name display

### v1.0.0-Alpha.3 — 2026-03-26

- Tag association refactored to use tag ID

### v1.0.0-Alpha.2 — 2026-03-25

- Email sending, mass sending, tag-based attachments

### v1.0.0-Alpha.1 — 2026-03-24

- Excel complex split, email address book, tag management, plugin loading
