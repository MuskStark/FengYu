# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Install API module to local Maven repo (required before building main project)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Compile
mvn clean compile

# Run (development)
mvn exec:java -Dexec.mainClass="fan.summer.Main"

# Package executable JAR
mvn clean package

# Package without tests
mvn clean package -DskipTests

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName
```

## Architecture

SwissKit is a modular desktop toolbox built with Java Swing. It uses a multi-module Maven structure:

- **SwissKitJ-Api** - Shared API module (interfaces, annotations, UI components). Must be installed to local Maven repo before building the main module.
- **SwissKit** (main) - Core application with Excel, Email, and Settings tools.
- **SwissKitJ-Plugin-HappyLearning** - Auto-learning plugin (in `OfficalPlugin/` directory).

### Plugin System

SwissKit auto-discovers tools using annotation-based discovery.

**All pages** (built-in and external):
1. Annotate with `@SwissKitPage`
2. External JAR plugins are discovered by `PluginLoader` scanning for `@SwissKitPage` annotation

**Built-in pages** (in `SwissKit/` or `OfficalPlugin/` modules):
- Scanned via `SwissKitPageScaner` using the classpath
- SPI registration in `META-INF/services/fan.summer.api.KitPage` is for module pages, not required for JAR plugins

**External plugins** (installed JARs):
1. Package as JAR with `SwissKitJ-Api` dependency
2. Classes annotated with `@SwissKitPage` are auto-discovered when JAR is loaded
3. Install via Settings page → copies JAR to `.swisskit/plugins/`
4. `PluginService.deployPlugin()` coordinates `PluginLoader` + `PluginManager` to hot-load

**Plugin architecture** (3-layer coordination):
- `PluginService` - Facade coordinating deployment, uninstallation, and state sync
- `PluginLoader` - Loads JARs via `IsolatedPluginClassLoader`, annotation-based discovery
- `PluginManager` - Persists plugin registry to database (plugin_manager table)

Pages are sorted by `order()` value from `@SwissKitPage` annotation.

### Key Components

- `Main.java` - Application entry point
- `HomePage.java` - Main orchestrator; initializes `SwissKitPageScaner`, `SideMenuBar`, and content panel
- `SwissKitPageScaner` - Scans **both** built-in pages and external plugins (via `PluginLoader`), sorts by `order()`
- `PluginService` - Facade for plugin operations; coordinates `PluginLoader` and `PluginManager`
- `PluginManager` - Manages plugin lifecycle persistence in database (plugin_manager table)
- `PluginLoader` - Loads/unloads external JAR plugins from `.swisskit/plugins/`
- `IsolatedPluginClassLoader` - Break-parent-delegation ClassLoader: `fan.summer.*`/`java.*`/`javax.*`/`sun.*`/`com.sun.*` delegate to main ClassLoader (shared interfaces/annotations); all other classes load from plugin JAR first
- `DatabaseInit` - Initializes H2 database at `.swisskit/swisskit.db`
- `SideMenuBar` - Dynamic sidebar menu showing all discovered pages, handles page switching

### Database

- H2 embedded database with MyBatis ORM
- Database stored at `.swisskit/swisskit.db`
- MyBatis mappers in `src/main/resources/mapper/` with corresponding Java mapper interfaces

### UI Conventions

- Use `SansSerif` font for UI components
- Use color constants from `UIUtils`
- All long-running operations must use `SwingWorker` to avoid blocking EDT
- UI updates from background threads must use `SwingUtilities.invokeLater()`

### JFormDesigner UI Files

Project UI is built with **JFormDesigner** (IntelliJ IDEA plugin). UI classes store the GUI layout in `.java` files using MigLayout, not XML.

**JFormDesigner file structure:**
```java
/*
 * Created by JFormDesigner on [date]
 */
public class SomePage {
    public SomePage() {
        initComponents();
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        // ... auto-generated layout code ...
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel panel1;
    private JLabel label1;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
```

**Critical rules for JFormDesigner files:**

1. **NEVER modify** code between `//GEN-BEGIN:initComponents` and `//GEN-END:initComponents`
2. **NEVER modify** variable declarations between `//GEN-BEGIN:variables` and `//GEN-END:variables`
3. **NEVER remove** the `@formatter:off` / `@formatter:on` comments — these prevent IDE formatting from corrupting generated code
4. To add custom components or event handlers, write **separate methods** outside the generated blocks and call them after `initComponents()`
5. To edit the UI, use **JFormDesigner UI designer in IntelliJ** — do not edit the `.java` file manually

**Example — adding a custom button handler in a JFormDesigner class:**
```java
public class SomePage {
    private JButton submitBtn;

    public SomePage() {
        initComponents();
        setupEventHandlers();  // Custom method outside generated blocks
    }

    private void setupEventHandlers() {
        submitBtn.addActionListener(e -> {
            // handle submit
        });
    }

    // JFormDesigner blocks remain untouched...
}
```

### Technology Stack

- Java 11
- Maven 3.6+
- Swing + FlatLaf 3.5 (UI theme)
- Apache FESOD 2.0.1-incubating (Excel processing)
- H2 2.4.240 + MyBatis 3.5.19 (database)
- Simple Java Mail 8.12.6 (email)
- Lombok 1.18.42 (code generation)

## Code Standards

- All code comments and UI text in English
- camelCase for methods/variables, PascalCase for class names
- Javadoc for public methods
- Thread safety: always update UI on EDT using `SwingUtilities.invokeLater()`
