# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

**Build order is required** — the API module must be installed before anything else compiles:

```bash
# Step 1: Install the API module
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Step 2: Build the full project (from repo root)
mvn clean package -DskipTests

# Run the application
java -jar SwissKit/target/SwissKit-2.1.1.jar
```

To build and run only the main app module:
```bash
mvn clean package -pl SwissKit -am -DskipTests
```

On Windows, the `windows-exe` Maven profile is auto-activated and produces `SwissKit.exe` via Launch4j. GitHub Actions handles multi-platform builds — local Maven is not required for releases.

## Module Structure

| Module | Purpose |
|--------|---------|
| `SwissKitJ-Api` | Shared plugin interface (`SwissKitJPlugin`), consumed by core and all plugins |
| `SwissKit` | Main JavaFX application — UI shell, plugin loading, built-in tools |
| `OfficalPlugin/SwissKitJ-Plugin-HappyLearning` | Auto-learning plugin |
| `OfficalPlugin/SwissKitJ-Plugin-Qcc` | CSV-to-Excel converter plugin |
| `OfficalPlugin/SwissKit-Plugin-Mouse` | Mouse automation plugin |

All plugins declare `SwissKitJ-Api` as `provided` scope. The main app provides it at runtime via the fat JAR.

## Architecture

**Entry point**: `fan.summer.Launcher` (fat-JAR manifest) → `fan.summer.app.SwissKitJApp` (JavaFX `Application`).

**Startup sequence** (in `SwissKitJApp.start()`):
1. Resolve `plugins/` directory (JAR sibling in production, `./plugins/` in dev)
2. Create `PluginLoader` + `PluginRegistry`
3. Register built-in tools via `BuiltinToolRegistrar` (bypasses JAR loading, directly adds to registry)
4. Build `MainWindow` and display it
5. Start `PluginLoader` (scans `plugins/` dir and watches for changes)

**UI structure** (all in `fan.summer.api.*`):
- `MainWindow` — root `BorderPane`; owns `Sidebar`, `ContentArea`, `DetailPanel`, `TitleBar`
- `Sidebar` — category-based navigation
- `ContentArea` — shows `ToolCard` grid or the active tool's `Node`
- `DetailPanel` — slide-in panel showing plugin metadata
- `TitleBar` — custom window chrome (window is `StageStyle.TRANSPARENT`)

**Theming**: Single CSS file at `src/main/resources/css/glass.css` (glassmorphism). Plugin icon background colors are CSS classes: `ic-blue / ic-purple / ic-teal / ic-amber / ic-red / ic-pink / ic-gray`.

**Database**: H2 file at `.swisskit/swisskit.db` relative to the runtime working directory. Schema initialized from `init.sql`. Accessed via MyBatis; mapper XMLs are in `src/main/resources/mapper/`.

**i18n**: `src/main/resources/i18n/messages.properties` (Chinese default), `messages_en.properties` (English).

## Branch Status — v3.0.0-JavaFX

This branch is an active migration from Swing/FlatLaf to JavaFX. Legacy Swing classes live in `SwissKit/backup/` and are **excluded from Maven compilation** via `<excludes>` in `SwissKit/pom.xml`. Do not move files out of `backup/` unless completing their JavaFX port.

The plugin interface was also renamed: the old `fan.summer.api.KitPage` (Swing `JPanel`-based) is replaced by `fan.summer.api.SwissKitJPlugin` (JavaFX `Node`-based).

## Plugin Development

**Interface**: `fan.summer.api.SwissKitJPlugin` (in `SwissKitJ-Api`)

```java
public interface SwissKitJPlugin {
    String getId();          // reverse-domain ID, e.g. "com.example.my-tool"
    String getName();
    String getDescription();
    String getCategory();    // dev / text / image / net / other
    String getVersion();
    String getIconText();    // emoji or single char
    default String getIconStyle() { return "ic-blue"; }  // CSS class for icon bg
    default String getType()      { return "plugin"; }   // "builtin" for built-ins

    Node createView();       // called once; result cached and reused
    default void onActivate()   {}
    default void onDeactivate() {}
    default void onUnload()     {}
}
```

**External plugins** (JAR-based):
1. Implement `SwissKitJPlugin`
2. Declare in `META-INF/services/fan.summer.api.SwissKitJPlugin`
3. Drop JAR into `plugins/` directory; hot-reload is supported

**Built-in tools** skip SPI entirely — `BuiltinToolRegistrar.register()` adds them directly to `PluginRegistry`. See existing tools there as templates.

## Custom Commands

- `/docs-updater` — Updates version numbers and content across `docs/`, `README.md`, and `CHANGELOG.md` to match current pom.xml versions.
- `/release <version>` — Full release workflow: bumps all pom.xml versions, updates `CHANGELOG.md`, commits, creates and pushes a `v{version}` git tag, which triggers GitHub Actions for multi-platform builds.

## Commit Convention

Follow conventional commits with emojis:
- `✨` / `:sparkles:` — new feature
- `🐛` / `:bug:` — bug fix
- `♻️` / `:recycle:` — refactor
- `📝` / `:memo:` — documentation
- `⬆️` / `:arrow_up:` — dependency upgrade
