# ZhiFlow

![ZhiFlow](https://img.shields.io/badge/ZhiFlow-Desktop%20Toolbox-blue) ![Java](https://img.shields.io/badge/Java-21-orange) ![License](https://img.shields.io/badge/License-GPL--3.0-blue) ![Maven](https://img.shields.io/badge/Maven-3.6+-red) ![Version](https://img.shields.io/badge/version-3.2.0-blue)

**ZhiFlow** is a *modular toolbox* — a growing collection of utility tools (Excel splitting, PDF
processing, email, an AI chat assistant, developer helpers, and more) with a plugin-based
architecture and automatic service discovery.

> ### 🚧 4.0.0 Preview — web + desktop
> This branch (`4.0.0-ZhiFlow`) re-architects ZhiFlow from a JavaFX desktop app into a **web +
> desktop application**: a **headless Spring Boot backend** (loopback web server, no window), a
> **Vue 3.5 + TypeScript** frontend (identical for browser and desktop), and a **Tauri 2.0**
> desktop shell that sidecar-launches the Java backend. Built-in tools become official plugins that
> expose a JSON `invoke` backend plus a micro-frontend UI bundle. JavaFX has been removed.
> See [`CHANGELOG.md`](CHANGELOG.md) and [`CLAUDE.md`](CLAUDE.md) for the current state.
>
> Run the backend: `java -jar ZhiFlow/target/ZhiFlow-4.0.0-SNAPSHOT.jar --port=0 --token=<t>`
> · frontend: `cd frontend && npm run dev` · smoke test: `scripts/e2e-smoke.sh`.

---

## Quick Start

**Requirements:**

- **JDK 21 or higher** (recommended: [Eclipse Temurin](https://adoptium.net/))
- **Maven 3.6 or higher**

### Build from Source

The project uses standalone POMs (no parent inheritance), so the API module must be installed first:

```bash
# Clone the repository
git clone https://github.com/MuskStark/ZhiFlow.git
cd ZhiFlow

# 1. Install the API module into the local repo (required)
mvn install -f ZhiFlow-Api/pom.xml -DskipTests

# 2. Build the application
mvn clean package -f ZhiFlow/pom.xml -DskipTests

# 3. Run
java -jar ZhiFlow/target/ZhiFlow-3.2.0.jar
```

### Download a Prebuilt Release

Prebuilt packages are published with each release. Every platform ships **two** variants:

| Variant | Contains |
|---|---|
| `*-x64.zip` / `*-arm64.zip` | Application only — a system Java 21+ is required |
| `*-x64-jre.zip` / `*-arm64-jre.zip` | Application bundled with a JRE — no separate Java install needed |

| Platform | Files |
|---|---|
| Windows 10/11 (64-bit) | `ZhiFlow-3.2.0-windows-x64.zip`, `ZhiFlow-3.2.0-windows-x64-jre.zip` |
| Linux x64 | `ZhiFlow-3.2.0-linux-x64.zip`, `ZhiFlow-3.2.0-linux-x64-jre.zip` |
| macOS Apple Silicon (M-series) | `ZhiFlow-3.2.0-macos-arm64.zip`, `ZhiFlow-3.2.0-macos-arm64-jre.zip` |

See the [Releases page](https://github.com/MuskStark/ZhiFlow/releases) for the latest builds.

---

## Features

- **🎨 Glassmorphism UI** — Modern JavaFX UI with frosted glass effects, animated collapsible sidebar,
  MDI icon glyphs, and glowing accent styles
- **📦 Modular Architecture** — Plugin-based design with Java ServiceLoader auto-discovery for external
  JARs plus a built-in tool registrar for packaged tools
- **🤖 AI Chat Assistant** — Multi-backend chat with local GGUF models (inference engine included),
  OpenAI-compatible cloud APIs, and Anthropic Claude; supports streaming and tool calling
- **🧩 AI Tool Calling** — Plugins expose `AiTool`s that the assistant can invoke to act on your data
  (Excel operations, email archive queries, PDF split/merge/convert, browser automation, and more)
- **⚡ High Performance** — Streaming Excel processing via Apache FESOD, low memory footprint
- **🌐 Browser Automation** — Headless-style browser session tool drivable by the AI assistant
- **🔌 Plugin Store** — Browse and install plugins from an online store with one click, or install local JARs
- **💾 Database Support** — H2 + MyBatis for persistent storage of settings, contacts, and history
- **🌍 Internationalization** — i18n-backed UI labels and sidebar navigation
- **🛠️ Easy Extension** — Add new tools by implementing the `ZhiFlowPlugin` interface

### Built-in Tools

#### 🤖 AI Chat (`OTHER`)
- Multi-backend: local GGUF inference, OpenAI-compatible, Anthropic Claude
- Streaming responses and tool calling
- Plugin-provided tools auto-registered with the assistant

#### 📊 Excel Splitter (`DEV`)
- **4-Step Wizard** — Select file → Choose split mode → Configure → Output
- **Split by Sheet** — One output file per selected sheet
- **Split by Column** — Group rows by unique column value
- **Complex Split Mode** — Multi-config splitting with saved configurations
- **Progress Tracking** — Real-time progress with streaming (low memory usage)

#### 📄 PDF Tools (`OTHER`)
- **PDF Split** — Extract page ranges into separate files
- **PDF Merge** — Combine multiple PDFs into one
- **PDF → DOCX** — Convert PDF documents to editable Word files

#### 📧 Email (`OTHER`)
- **Email Composition** — Subject and body with plain-text/HTML toggle
- **Recipient Management** — Multiple recipients, CC/BCC support
- **Mass Email** — Send to contacts filtered by tags
- **Attachment by Tag** — Attach files from tag-based folder selection
- **SMTP Integration** — Full SMTP support with TLS/SSL
- **Sent Log** — View history of sent emails with status tracking

#### 🗄️ Email Archive (`OTHER`)
- Fetch and archive email messages from configured accounts
- Query archived emails (also exposed as an AI tool)

#### 🌐 Browser Automation (`NET`)
- Drives a browser session through a sequence of actions
- Page snapshot inspection; AI-callable automation tool

#### 🎨 Color Converter (`IMAGE`)
- Convert between color formats (HEX, RGB, HSL, etc.)

#### 📝 Markdown Editor (`TEXT`)
- Edit and preview Markdown

#### 🛠️ Developer Tools (`DEV`)
- **JSON Formatter** — Pretty-print and validate JSON
- **Base64** — Encode/decode Base64
- **Hash Calculator** — Compute common hash digests

#### ⚙️ Settings
- **Email Server** — SMTP configuration with TLS/SSL
- **Address Book** — Manage contacts with nicknames; double-click to edit
- **Tag Management** — Create and manage tags for contacts
- **AI Configuration** — Choose mode (local/OpenAI/Anthropic), endpoints, API keys, models
- **Plugin Store URL** — Configure the online plugin store endpoint

---

## Architecture

### Project Modules

ZhiFlow uses a multi-module Maven structure with **standalone POMs** (no parent inheritance):

| Module | Purpose |
|--------|---------|
| `ZhiFlow-Api` | Shared plugin contract + reusable UI components (`ZhiFlowPlugin`, `AiTool`, `StepWizard`, `ToolCategory`) |
| `ZhiFlow` | Main JavaFX application — UI shell, plugin loading, built-in tools, AI layer, database |

### UI Structure

```
MainWindow (StageStyle.TRANSPARENT)
├── TitleBar           — Custom window chrome (drag, minimize, maximize, close)
├── Sidebar            — Category navigation (all / text / image / dev / net / other + AI chat, plugins)
├── ContentArea        — ToolCard grid or active tool view
└── DetailPanel        — Slide-in panel with plugin metadata + Launch button
```

Sidebar categories are defined by the `ToolCategory` enum: `DEV`, `TEXT`, `IMAGE`, `NET`, `OTHER`.
The AI Chat assistant and installed-plugins list are pinned as dedicated sidebar sections.

### Plugin System

Plugins implement `fan.summer.api.ZhiFlowPlugin` (from `ZhiFlow-Api`):

```java
public interface ZhiFlowPlugin {
    // Metadata
    String getId();                  // reverse-domain ID, e.g. "com.example.my-tool"
    String getName();
    String getDescription();
    ToolCategory getCategory();      // DEV / TEXT / IMAGE / NET / OTHER
    String getVersion();
    String getMdiIcon();             // Material Design Icons name, e.g. "file-excel"
    default IconStyle getIconStyle() { return IconStyle.BLUE; }
    default ToolType getType()       { return ToolType.PLUGIN; }  // BUILTIN vs PLUGIN

    // UI lifecycle — createView() is called once and cached
    Node createView();
    default void onActivate()   {}
    default void onDeactivate() {}
    default void onUnload()     {}
    default boolean hasRunningTasks() { return false; }  // keep alive in background
    default void onBackground() {}
    default void onForeground() {}

    // AI integration — tools the assistant can call
    default List<AiTool> aiTools() { return List.of(); }
}
```

Register external plugins via `META-INF/services/fan.summer.api.ZhiFlowPlugin`, package as a fat
JAR, then drop it into the host's `plugins/` directory (hot-reload supported) or install via the
Plugin Store.

### Built-in Tool Registration

Built-in tools (packaged with the main app) bypass SPI and are registered programmatically via
`BuiltinToolRegistrar` during startup:

```java
BuiltinToolRegistrar.register(loader, registry);
```

The current registrar registers: AI Chat, JSON Formatter, Base64, Hash Calculator, Excel Splitter,
Color Converter, Markdown Editor, Email, Email Archive, PDF Tools, and Browser Automation.

---

## Tech Stack

| Category | Technology | Version |
|---|---|---|
| **Language** | Java | 21 |
| **Build Tool** | Maven | 3.6+ |
| **UI Framework** | JavaFX | 21.0.2 |
| **Theming** | Custom CSS (glassmorphism) + MDI icon glyphs | — |
| **Excel Processing** | Apache FESOD / Apache POI | 2.0.1-incubating / 5.4.1 |
| **PDF Processing** | Apache PDFBox | 3.0.4 |
| **AI — Cloud** | LangChain4j (OpenAI + Anthropic) | 1.2.0 |
| **AI — Local** | Built-in GGUF inference engine | — |
| **Database** | H2 + MyBatis | 2.4.240 / 3.5.19 |
| **Logging** | SLF4J + Logback | 2.0.13 / 1.5.6 |
| **Email** | Simple Java Mail | 8.12.6 |
| **Serialization** | Gson | 2.13.1 |

---

## Development

### Adding a Built-in Tool

1. Create a class implementing `ZhiFlowPlugin` under `ZhiFlow/src/main/java/fan/summer/buildintool/<your-tool>/`
2. Instantiate and add it inside `BuiltinToolRegistrar.register(...)`
3. The tool appears in the sidebar under its `ToolCategory`

### Creating an External Plugin

1. Create a Maven project with `ZhiFlow-Api` as a `provided` dependency
2. Implement `ZhiFlowPlugin` (optionally expose `AiTool`s)
3. Register in `META-INF/services/fan.summer.api.ZhiFlowPlugin`
4. Package as a fat JAR and install via the Plugin Store or the Local Install tab

### Building

```bash
# API module must be installed first
mvn install -f ZhiFlow-Api/pom.xml -DskipTests

# Build the app
mvn clean package -f ZhiFlow/pom.xml -DskipTests

# Run
java -jar ZhiFlow/target/ZhiFlow-3.2.0.jar
```

### Running with a Local Plugin Store

Override the store URL via system property:

```bash
java -Dstore.url=http://localhost:8888/plugins/store.json -jar ZhiFlow/target/ZhiFlow-3.2.0.jar
```

---

## Database

ZhiFlow uses an embedded H2 file-based database — no external server required.

### Location

`.zhiflow/zhiflow.db` relative to the application runtime directory.

### Key Entities

| Area | Entity / Mapper | Purpose |
|---|---|---|
| General | `AppSettingEntity` | General app settings (store URL, AI config, etc.) |
| General | `MenuOrderEntity` | Sidebar / tool menu ordering |
| General | `PluginFavoriteEntity` | Favorited plugins |
| Email | `zhiflow_setting_email` | SMTP configuration |
| Email | `email_address_book` | Contacts with nicknames and tags |
| Email | `email_tag` | Tags for categorizing contacts |
| Email | `email_mass_sent_config` | Mass email configuration |
| Email | `email_sent_log` | Email sending history |
| Excel | `complex_split_config` | Excel complex split configurations |

---

## Roadmap

- [x] Excel file analysis and split by sheet / column / complex mode
- [x] Email address book, tag management, and SMTP sending
- [x] Mass email with tag-based recipients + sent log
- [x] Plugin Store with online + local installation
- [x] JavaFX UI redesign (glassmorphism)
- [x] Multi-backend AI chat (local GGUF + OpenAI-compatible + Anthropic)
- [x] AI tool calling across plugins
- [x] PDF processing (split / merge / convert)
- [x] Browser automation tool
- [x] Internationalization (i18n)
- [ ] Image processing toolset (expansion)
- [ ] Theme switching (light/dark)
- [ ] Add unit tests

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

GNU General Public License v3.0 — see [LICENSE](LICENSE).

---

## Documentation

- [CHANGELOG](CHANGELOG.md) — Release history
- [AGENTS.md](AGENTS.md) — Technical documentation for AI assistants
- Online docs: https://muskstark.github.io/ZhiFlow/

---

**Built with ❤️ using JavaFX**
