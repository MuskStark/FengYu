# Changelog

All notable changes to SwissKitJ. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
