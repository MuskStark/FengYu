# SwissKit

![SwissKit](https://img.shields.io/badge/SwissKit-Desktop%20Toolbox-blue) ![Java](https://img.shields.io/badge/Java-17-orange) ![License](https://img.shields.io/badge/License-MIT-green) ![Maven](https://img.shields.io/badge/Maven-3.6+-red)

**SwissKit** is a *modular desktop toolbox* built with JavaFX. It provides a clean, extensible platform for various
utility tools including Excel file processing, email sending, and plugin support. The application uses a
plugin-based architecture with automatic service discovery, making it easy to add new functionality.

If you want a lightweight, fast, and customizable desktop utility suite with a modern glassmorphism UI, this is it.

---

## Quick Start

**Requirements:**

- **JDK 17 or higher** (recommended: [Eclipse Temurin](https://adoptium.net/))
- **Maven 3.6 or higher**

### Installation

```bash
# Clone the repository
git clone https://github.com/MuskStark/SwissKitJ.git
cd SwissKitJ

# Install API module first (required)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Build the main project
mvn clean package -DskipTests

# Run the application
java -jar SwissKit/target/SwissKit-3.0.0.jar
```

### Platform-Specific Builds

The GitHub Actions workflow builds three platform packages:

| Platform | File | How to run |
|---|---|---|
| Windows 10/11 (64-bit) | `SwissKit-3.0.0-windows.zip` | Extract then double-click `SwissKit.exe` |
| Linux x64 | `SwissKit-3.0.0-linux.zip` | Extract then run `./run.sh` |
| macOS Apple Silicon (M1/M2/M3) | `SwissKit-3.0.0-macos-apple-silicon.zip` | Extract then double-click `SwissKit.app` |

All platforms require Java 11+ installed on the target machine.

---

## Features

- **🎨 Glassmorphism UI** — Modern JavaFX UI with frosted glass effects, animated sidebar, and glowing accents
- **📦 Modular Architecture** — Plugin-based design with Java ServiceLoader auto-discovery
- **⚡ High Performance** — Uses Apache FESOD for efficient Excel processing
- **🔌 Plugin Store** — Browse and install plugins from the online store with one-click installation
- **💾 Database Support** — H2 + MyBatis for persistent storage
- **📧 Email Management** — Address book with tags for contact organization, mass sending
- **🛠️ Easy Extension** — Add new tools by implementing the `SwissKitJPlugin` interface

### Built-in Tools

#### 📊 Excel Splitter

- **4-Step Wizard** — Guided workflow: Select file → Choose split mode → Configure → Output
- **Split by Sheet** — One output file per selected sheet
- **Split by Column** — Group rows by unique column value
- **Complex Split Mode** — Multi-config splitting with saved configurations
- **Progress Tracking** — Real-time progress with streaming (low memory usage)
- **Multi-Sheet Support** — Handle multiple sheets in a single file

#### 📧 Email

- **Email Composition** — Subject and body with plain-text/HTML toggle
- **Recipient Management** — Add multiple recipients, CC/BCC support
- **Mass Email** — Send to contacts filtered by tags
- **Attachment by Tag** — Attach files from tag-based folder selection
- **SMTP Integration** — Full SMTP support with TLS/SSL
- **Sent Log** — View history of sent emails with status tracking

#### ⚙️ Settings

- **Email Server** — SMTP configuration with TLS/SSL
- **Address Book** — Manage contacts with nicknames; double-click to edit
- **Tag Management** — Create and manage tags for contacts
- **Plugin Store URL** — Configure the online plugin store endpoint

---

## Architecture

### Project Modules

SwissKit uses a multi-module Maven structure:

| Module | Description |
|--------|-------------|
| `SwissKitJ-Api` | Shared plugin interface + reusable UI components (`SwissKitJPlugin`, `StepWizard`) |
| `SwissKit` | Main JavaFX application — UI shell, plugin loading, built-in tools |
| `OfficalPlugin/SwissKitJ-Plugin-HappyLearning` | Auto-learning plugin |
| `OfficalPlugin/SwissKitJ-Plugin-Qcc` | CSV-to-Excel converter plugin |
| `OfficalPlugin/SwissKit-Plugin-Mouse` | Mouse automation plugin |

### UI Structure

```
MainWindow (StageStyle.TRANSPARENT)
├── TitleBar           — Custom window chrome (drag, minimize, maximize, close)
├── Sidebar            — Category navigation (all / text / image / dev / net / other)
├── ContentArea        — ToolCard grid or active tool view
└── DetailPanel        — Slide-in panel with plugin metadata + Launch button
```

### Plugin System

Plugins implement `fan.summer.api.SwissKitJPlugin` (from `SwissKitJ-Api`):

```java
public interface SwissKitJPlugin {
    String getId();          // reverse-domain ID, e.g. "com.example.my-tool"
    String getName();
    String getDescription();
    String getCategory();    // dev / text / image / net / other
    String getVersion();
    String getIconText();    // emoji or single char
    Node createView();       // JavaFX Node — cached and reused

    default void onActivate()   {}
    default void onDeactivate() {}
    default void onUnload()     {}
}
```

Register via `META-INF/services/fan.summer.api.SwissKitJPlugin`, then drop the JAR into the `plugins/` directory. Hot-reload is supported.

### Built-in Tool Registration

Built-in tools (those packaged with the main app) bypass SPI and are registered directly via `BuiltinToolRegistrar`:

```java
BuiltinToolRegistrar.register(builtinPluginInstance);
```

---

## Tech Stack

| Category | Technology | Version |
|---|---|---|
| **Language** | Java | 17 (target) / 11 (minimum) |
| **Build Tool** | Maven | 3.6+ |
| **UI Framework** | JavaFX | 21 |
| **Theming** | Custom CSS (glassmorphism) | — |
| **Excel Processing** | Apache FESOD | 2.0.1-incubating |
| **Database** | H2 + MyBatis | 2.4.240 / 3.5.19 |
| **Logging** | SLF4J + Logback | 2.0.16 / 1.3.15 |
| **Email** | Simple Java Mail | 8.12.6 |

---

## Development

### Adding a Built-in Tool

1. Create a class implementing `SwissKitJPlugin` in `SwissKit/src/main/java/fan/summer/kitpage/`
2. Register it in `BuiltinToolRegistrar.register()` during app startup
3. The tool will appear in the sidebar under its category

### Creating an External Plugin

1. Create a Maven project with `SwissKitJ-Api` as a `provided` dependency
2. Implement `SwissKitJPlugin`
3. Register in `META-INF/services/fan.summer.api.SwissKitJPlugin`
4. Package as JAR and install via the Plugin Store or Local Install tab

### Building

```bash
# Install API module first
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Build all modules
mvn clean package -DskipTests

# Run
java -jar SwissKit/target/SwissKit-3.0.0.jar
```

### Running with Local Plugin Store

Override the store URL for local testing:

```bash
java -Dstore.url=http://localhost:8888/plugins/store.json -jar SwissKit/target/SwissKit-3.0.0.jar
```

---

## Database

### Tables

| Table | Purpose |
|---|---|
| `app_setting` | General app settings (store URL, etc.) |
| `swiss_kit_setting_email` | Email SMTP configuration |
| `complex_split_config` | Excel complex split configurations |
| `email_address_book` | Email contacts with nicknames and tags |
| `email_tag` | Tags for categorizing contacts |
| `email_mass_sent_config` | Mass email sending configuration |
| `email_sent_log` | Email sending history |

### Location

`.swisskit/swisskit.db` relative to the application runtime directory. H2 embedded file-based — no external server required.

---

## Roadmap

- [x] Excel file analysis and split by sheet
- [x] Excel split by column value
- [x] Excel complex split mode
- [x] Email address book management
- [x] Email tag management
- [x] Plugin installation (online + local)
- [x] Email sending with SMTP (TLS/SSL)
- [x] Mass email with tag-based recipients
- [x] Email sent log viewing
- [x] JavaFX UI redesign (glassmorphism)
- [x] Plugin Store with online install
- [ ] PDF processing tool
- [ ] Image processing tool
- [ ] Add unit tests
- [ ] Theme switching (light/dark)

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes using conventional commits with emojis
4. Push to the branch
5. Open a Pull Request

### Commit Message Format

- `✨` — New feature
- `📝` — Documentation
- `🐛` — Bug fix
- `♻️` — Refactor
- `⬆️` — Dependency upgrade

---

## License

MIT License — see [LICENSE](LICENSE) file.

---

## Documentation

- [CHANGELOG](CHANGELOG.md) — Release history
- [AGENTS.md](AGENTS.md) — Technical documentation for AI assistants
- Online docs: https://muskstark.github.io/SwissKitJ/

---

**Built with ❤️ using JavaFX**