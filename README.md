# Infinia

![Infinia](https://img.shields.io/badge/Infinia-Web%20%2B%20Desktop-blue) ![Java](https://img.shields.io/badge/Java-21-orange) ![License](https://img.shields.io/badge/License-GPL--3.0-blue) ![Maven](https://img.shields.io/badge/Maven-3.6+-red) ![Version](https://img.shields.io/badge/version-4.0.0--preview-blue)

**Infinia** (蜂语) is a *modular toolbox* — a growing collection of utility tools (Excel splitting, PDF
processing, email, an AI chat assistant, developer helpers, and more) with a plugin-based
architecture and automatic service discovery.

> ### 🚧 4.0.0 Preview — web + desktop
> This branch (`4.0.0-FengYu`) re-architects Infinia from a JavaFX desktop app into a **web +
> desktop application**: a **headless Spring Boot backend** (loopback web server, no window), a
> **Vue 3.5 + TypeScript** frontend (identical for browser and desktop), and a **Tauri 2.0**
> desktop shell that sidecar-launches the Java backend. Built-in tools become official plugins that
> expose a JSON `invoke` backend plus a micro-frontend UI bundle. JavaFX has been removed.
> See [`CHANGELOG.md`](CHANGELOG.md) and [`CLAUDE.md`](CLAUDE.md) for the current state.
>
> Run the backend: `java -jar FengYu/target/FengYu-4.0.0-SNAPSHOT.jar --token=<t>` (binds port 24056 by default)
> · frontend: `cd frontend && npm run dev` · smoke test: `scripts/e2e-smoke.sh`.
>
> The official **Email Center** plugin now ships as `fan.summer.email`: five sandboxed UI tabs,
> multi-account SMTP/IMAP, manual-only collection, encrypted credentials, and seven confirmation-first AI tools.
> See [Email Center](docs/en/plugins/email-center.md) and the [plugin database standard](docs/en/plugins/database.md).

---

## Quick Start

**Requirements:**

- **JDK 21 or higher** (recommended: [Eclipse Temurin](https://adoptium.net/))
- **Maven 3.6 or higher**

### Build from Source

The project uses standalone POMs (no parent inheritance), so the API module must be installed first:

```bash
# Clone the repository
git clone https://github.com/MuskStark/FengYu.git
cd FengYu

# 1. Install the API module into the local repo (required)
mvn install -f FengYu-Api/pom.xml -DskipTests

# 2. Build the application
mvn clean package -f FengYu/pom.xml -DskipTests

# 3. Run
java -jar FengYu/target/FengYu-3.2.0.jar
```

### Download a Prebuilt Release

Prebuilt packages are published with each release. Every platform ships **two** variants:

| Variant | Contains |
|---|---|
| `*-x64.zip` / `*-arm64.zip` | Application only — a system Java 21+ is required |
| `*-x64-jre.zip` / `*-arm64-jre.zip` | Application bundled with a JRE — no separate Java install needed |

| Platform | Files |
|---|---|
| Windows 10/11 (64-bit) | `FengYu-3.2.0-windows-x64.zip`, `FengYu-3.2.0-windows-x64-jre.zip` |
| Linux x64 | `FengYu-3.2.0-linux-x64.zip`, `FengYu-3.2.0-linux-x64-jre.zip` |
| macOS Apple Silicon (M-series) | `FengYu-3.2.0-macos-arm64.zip`, `FengYu-3.2.0-macos-arm64-jre.zip` |

See the [Releases page](https://github.com/MuskStark/FengYu/releases) for the latest builds.

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
- **💾 Database Support** — Multi-datasource (H2 / SQLite / MySQL / PostgreSQL) via a first-launch setup wizard, persisted with Spring Data JPA
- **🌍 Internationalization** — i18n-backed UI labels and sidebar navigation
- **🛠️ Easy Extension** — Add new tools by implementing the `FengYuPlugin` interface

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

Infinia uses a multi-module Maven structure with **standalone POMs** (no parent inheritance):

| Module | Purpose |
|--------|---------|
| `FengYu-Api` | Shared plugin contract + reusable UI components (`FengYuPlugin`, `AiTool`, `StepWizard`, `ToolCategory`) |
| `FengYu` | Main JavaFX application — UI shell, plugin loading, built-in tools, AI layer, database |

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

Plugins are isolated `.fyp` packages. Each package contains a `manifest.json`, a standalone web UI,
and an optional backend command speaking newline-delimited JSON-RPC 2.0. The UI runs in a sandboxed
iframe and can only access declared host capabilities through the FengYu bridge. Backend code runs
in a child process, never in the host Spring context.

Web file selection uploads into a scoped temporary grant. The desktop shell uses native Tauri
dialogs and turns the selected path into the same opaque `FileRef`; plugin UI code is identical on
both targets and never sees an absolute path. Install, update, enable, disable, and uninstall are
available from the Plugins page. See [Plugin Marketplace](docs/plugins/marketplace.md).
Third-party authors use `@fengyu/plugin-sdk`, the Java Worker SDK, and the cross-platform
`fengyu plugin` CLI documented in [Plugin SDK and CLI](docs/plugins/sdk-cli.md).

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
| **Database** | Spring Data JPA + Hibernate (H2/SQLite/MySQL/PostgreSQL) | 4.1.0 / 7.4.1 |
| **Logging** | SLF4J + Logback | 2.0.13 / 1.5.6 |
| **Email** | Simple Java Mail | 8.12.6 |
| **Serialization** | Gson | 2.13.1 |

---

## Development

### Adding a Built-in Tool

1. Create a class implementing `FengYuPlugin` under `FengYu/src/main/java/fan/summer/buildintool/<your-tool>/`
2. Instantiate and add it inside `BuiltinToolRegistrar.register(...)`
3. The tool appears in the sidebar under its `ToolCategory`

### Creating an External Plugin

1. Create `manifest.json` and a standalone `ui/index.html`
2. Call host capabilities through the message-based FengYu SDK
3. Optionally provide a JSON-RPC 2.0 worker executable
4. ZIP the package root as `<plugin-id>-<version>.fyp`

### Building

```bash
# Build the backend
mvn -pl FengYu -am clean package -DskipTests

# Build the two official .fyp packages
mvn -pl OfficialPlugins/plugin-markdown,OfficialPlugins/plugin-excel,OfficialPlugins/plugin-email -am package -DskipTests
OfficialPlugins/build-packages.sh

# Run
java -jar FengYu/target/FengYu-4.0.0-SNAPSHOT.jar
```

### Running with a Local Plugin Store

Override the store URL via system property:

```bash
java -Dfengyu.marketplace.catalog-url=https://example.com/catalog.json \
  -jar FengYu/target/FengYu-4.0.0-SNAPSHOT.jar
```

---

## Database

Infinia uses **Spring Data JPA + Hibernate** and supports **four database backends**, chosen at
first launch via a setup wizard. No database knowledge is required for the default local
experience.

### First-launch setup wizard

On first launch (no `datasource.properties`), the backend boots in **SETUP mode** and the frontend
shows a wizard that lets you pick a database:

- **H2** (default, local embedded) — zero configuration, data stored under `.fengyu/data/`.
- **SQLite** (local embedded) — single-file database.
- **MySQL** (remote) — for multi-user or server deployment.
- **PostgreSQL** (remote) — for multi-user or server deployment.

The wizard tests the connection, persists the configuration to
`~/.fengyu/config/datasource.properties` (passwords AES-GCM encrypted, machine-bound), then exits.
The Tauri/desktop supervisor restarts the backend into **APP mode**, where Hibernate
`ddl-auto=update` creates the schema from the JPA entities automatically. To reconfigure, delete
`datasource.properties` and restart — the wizard reappears.

### Persistence

- **Layer:** Spring Data JPA repositories (replaced the former MyBatis mappers).
- **Schema:** Hibernate `ddl-auto=update` — entity changes auto-create tables / add columns.
- **User isolation:** every user-scoped table carries a `user_id` column; local offline mode
  attributes all data to a single virtual user (id=1, "ZFlow-Summer"). The groundwork for real
  multi-user login is in place (`AuthProvider` / `SecurityContext` interfaces) but login UI is
  deferred to a later phase.

### Key Entities

| Area | Entity | Purpose |
|---|---|---|
| General | `AppSettingEntity` | General app settings (store URL, AI config, etc.) |
| General | `MenuOrderEntity` | Sidebar / tool menu ordering |
| General | `PluginFavoriteEntity` | Favorited plugins |
| Email | `FengYuSettingEmailEntity` | SMTP configuration |
| Email | `EmailAddressBookEntity` | Contacts with nicknames and tags |
| Email | `EmailTagEntity` | Tags for categorizing contacts |
| Email | `EmailMassSentConfigEntity` | Mass email configuration |
| Email | `EmailSentLogEntity` | Email sending history |
| Email | `EmailArchiveEntity` | Archived IMAP messages |
| Excel | `ComplexSplitConfigEntity` | Excel complex split configurations |
| System | `SysUserEntity` / `SysSessionEntity` | User / session (login groundwork) |

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
- Online docs: https://muskstark.github.io/FengYu/

---

**Built with ❤️ using JavaFX**
