# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

**Critical: SwissKitJ-Api must be installed before the main module:**

```bash
# Install API module first (required for any build)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Clean build with executable JAR
mvn clean package

# Skip tests during build
mvn clean package -DskipTests

# Run application directly (no JAR packaging)
mvn exec:java -Dexec.mainClass="fan.summer.Main"

# Run tests
mvn test
```

## Architecture

### Multi-Module Maven Structure

Build order matters:
1. `SwissKitJ-Api` - Shared API (interfaces, annotations, UI components)
2. `SwissKit` - Main application
3. `OfficalPlugin/*` - Built-in plugins

### Plugin System

SwissKit uses SPI (Service Provider Interface) for automatic tool discovery:

1. Implement `KitPage` interface from `fan.summer.api`
2. Annotate with `@SwissKitPage` from `fan.summer.annoattion`
3. Register in `META-INF/services/fan.summer.api.KitPage`
4. Pages auto-discovered and sorted by `order()` value at runtime

**Plugin Architecture:**
- `PluginAPI` - Interface for plugins to communicate with host app
- `PluginRegistry` - Manages plugin UI injection and lifecycle
- `PluginLoader` - Hot-loads JAR plugins with isolated classloaders
- `PluginService` - Facade for plugin lifecycle (deploy/upgrade/uninstall)

**Content Injection:**
Plugins provide content via `getContent()` method (returns `Node` for JavaFX or `JPanel` for Swing). The `PluginRegistry` handles injecting plugin content into the main window's content area via `ContentProvider` callbacks.

### Entry Point

- `fan.summer.Main` - Application entry point (Swing → being migrated to JavaFX)
- `fan.summer.ui.home.HomePage` - Main JavaFX container
- `fan.summer.controller.MainController` - JavaFX window controller
- `SwissKitPageScaner` - Discovers all `KitPage` implementations via SPI

### Database

- **Location**: `.swisskit/swisskit.db` (relative to runtime directory)
- **Type**: H2 embedded database
- **Access**: MyBatis `SqlSession` via `DatabaseInit.getSqlSession()`
- **Auto-initialized**: Tables created on first run via `init.sql`

### UI Pattern

- JavaFX with MaterialFX 11.17.0 theming
- FXML-based UI layouts in `css/uiFxml/` directories
- Background tasks via `Task` subclasses (javafx.concurrent)
- Custom UI components and theming in `fan.summer.ui` packages
- Theme management via `ThemeManager`, scaling via `ScaleManager`

### Key Packages

- `fan.summer.kitpage/` - Tool pages (Excel, Email, Settings, Welcome)
- `fan.summer.ui/` - JavaFX UI components, theme, scaling, loading screens
- `fan.summer.database/` - MyBatis entities and mappers
- `fan.summer.plugin/` - Plugin loading system
- `fan.summer.utils/` - Utilities (EmailUtil, ExcelUtil, etc.)

## Tech Stack

| Component       | Technology                  |
|-----------------|-----------------------------|
| Language        | Java 21                     |
| Build           | Maven 3.6+                  |
| UI              | JavaFX 21 + MaterialFX 11.17|
| Excel           | Apache FESOD 2.0.1         |
| Database        | H2 2.4.240 + MyBatis 3.5.19|
| Email           | Simple Java Mail 8.12.6    |
| JSON            | FastJSON2 2.0.59           |
| Logging         | Log4j2 + SLF4J             |