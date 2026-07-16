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
> expose a JSON-RPC worker backend plus a micro-frontend UI bundle. JavaFX has been removed.
> See [`CHANGELOG.md`](CHANGELOG.md) and the [online docs](https://muskstark.github.io/FengYu/) for the current state.
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
- **Node 20+ and npm** (for the frontend dev server and plugin UIs)
- **Rust + `tauri-cli`** (only for the desktop shell)

### Build from Source

The project uses standalone POMs (no parent inheritance), so the API module must be installed first:

```bash
# Clone the repository
git clone https://github.com/MuskStark/FengYu.git
cd FengYu

# 1. Install the API module into the local repo (required)
mvn install -f FengYu-Api/pom.xml -DskipTests

# 2. Build the backend fat JAR
mvn clean package -f FengYu/pom.xml -DskipTests

# 3. Run the headless backend (loopback web server on 127.0.0.1:24056)
java -jar FengYu/target/FengYu-4.0.0-SNAPSHOT.jar --token=<your-token>
```

### Run the Frontend (dev)

```bash
cd frontend && npm install && npm run dev   # Vite proxies /api + /plugin-runtime to :24056
```

### Run the Desktop Shell (dev)

```bash
cd desktop && cargo tauri dev               # release: cargo tauri build
```

### Smoke Test

`scripts/e2e-smoke.sh` boots the jar and probes every endpoint.

### Releases (Alpha)

Pushed release tags (`v4.0.0`, `v4.0.0-alpha.1`, `-beta.*`, `-rc.*`) trigger
[`.github/workflows/fengyu-release.yml`](.github/workflows/fengyu-release.yml), which publishes:

- **Unsigned Tauri packages** for Windows, macOS, and Linux.
- A **portable Web distribution** (`Infinia-<version>-web.zip` / `.tar.gz`) — unzip and run `./run.sh`
  (macOS/Linux) or `run.bat` (Windows). Requires **Java 21**; the backend binds **loopback only**
  (`127.0.0.1`) and is not reachable from other machines.

These are **unsigned Alpha builds**: code-signing, a bundled JRE, and the auto-updater are deferred to
a later release.

---

## Features

- **🤖 AI Chat & Agent** — Multi-backend chat (Ollama, OpenAI, Anthropic, DeepSeek) with streaming, thinking cards, and tool calls, plus a plan-and-execute agent with approvals. See [AI Chat](docs/en/guide/ai-chat) / [AI Agent](docs/en/guide/ai-agent).
- **🧩 Plugin Marketplace** — Browse, install, update, enable/disable, and uninstall `.fyp` plugin packages: JSON-RPC workers + micro-frontend UIs. See [Marketplace](docs/en/plugins/marketplace).
- **📊 Excel Splitter** — Split workbooks by sheet, column value, or complex rules — an official plugin with six AI tools. See [Excel](docs/en/plugins/official-excel).
- **📧 Email Center** — Multi-account SMTP/IMAP, contact/tag management, filename-tag batch sending, manual archive collection, and seven confirmation-first AI tools. See [Email Center](docs/en/plugins/email-center.md).
- **📝 Markdown Editor** — Split-pane editor with isolated server-side rendering. See [Markdown](docs/en/plugins/official-markdown).
- **💾 Multi-Database** — First-launch wizard picks H2, SQLite, MySQL, or PostgreSQL; passwords AES-GCM encrypted. See [Database](docs/en/guide/database).
- **🎨 Material Design 3** — Vuetify 3 MD3 UI, shared with plugin micro-frontends, dark and light themes. See [Design System](docs/en/design-system).
- **🌍 Internationalization** — English-first docs and a localized Vue UI (vue-i18n).

---

## Architecture

Infinia 4.0.0 is a **three-layer web + desktop application**:

```
┌──────────────────────────────────────────────────────────────┐
│  Tauri 2.0 desktop shell (optional)   ·   Browser (default)  │
│  ──────────────────────────────────────────────────────────  │
│                  Vue 3.5 + TypeScript SPA                     │
│            Vuetify 3 (MD3) · Pinia · vue-router · vue-i18n    │
│            MF host mounts plugin UIs in sandboxed iframes     │
└──────────────────────────┬───────────────────────────────────┘
                           │  HTTP + SSE  (loopback 127.0.0.1)
┌──────────────────────────▼───────────────────────────────────┐
│            Headless Spring Boot backend (no window)           │
│   REST + SSE controllers · Spring AI · JPA/Hibernate · H2     │
│   Spawns plugin workers as out-of-process JSON-RPC children   │
└──────────────────────────────────────────────────────────────┘
```

The backend binds **loopback only** (`127.0.0.1:24056` by default) and every request (except
`/api/health`, `/api/setup/*`, and plugin UI static assets) is gated by the per-launch
`X-FengYu-Token` header. The desktop shell sidecar-launches the JAR, waits for health, and injects
the token into the webview. See [Architecture Overview](docs/en/architecture/overview).

### Project Modules

| Module / dir | Purpose |
|--------|---------|
| `FengYu-Api` | Plugin + AI contract (`manifest.json` schema, worker JSON-RPC protocol, `AiTool`). |
| `FengYu-Plugin-Sdk` | Java Worker SDK + TypeScript `@infinia/plugin-sdk` (the iframe `postMessage` bridge). |
| `OfficialPlugins` | Official plugins: `plugin-markdown`, `plugin-excel`, `plugin-email` (each ships a `.fyp`). |
| `FengYu` | Headless Spring Boot backend — REST/SSE controllers, AI backends, JPA/Hibernate, marketplace. |
| `frontend/` | Vue 3.5 + TS SPA (runs identically in the browser or the Tauri webview). |
| `desktop/` | Tauri 2.0 desktop shell — sidecar-launches the JAR, native dialogs, window chrome. |
| `plugin-ui/` | `@infinia/plugin-ui` — the official Vue/Vuetify component kit for plugin micro-frontends. |
| `plugin-cli/` | `fengyu plugin` CLI — `create`, `dev`, `build`, `validate`, `install`. |

### Plugin System

Plugins are isolated **`.fyp`** packages (a zip of `manifest.json` + `ui/` + `backend/worker.jar`).
The UI runs in a **sandboxed iframe** and talks to the host through the `@infinia/plugin-sdk`
`postMessage` bridge; the backend is an **out-of-process worker** speaking newline-delimited
JSON-RPC 2.0 over stdio. A worker crash can never take down the host, and workers never touch the
host Spring context or JPA session.

File selection uploads into a scoped temporary grant; on desktop the Tauri shell resolves a native
path into the same opaque `FileRef`. Plugin UI code is identical on both targets and never sees an
absolute path. Plugins that need persistence declare the `database` permission and get an
injected datasource connection (table-name-prefixed, plugin-owned schema).

Third-party authors scaffold with `fengyu plugin create` and use the component kit, Worker SDK, and
CLI — there is no `FengYuPluginV2` interface and no in-host JavaFX. See the
[Plugin Overview](docs/en/plugins/overview).

---

## Tech Stack

| Category | Technology | Version |
|---|---|---|
| **Language** | Java | 21 |
| **Backend** | Spring Boot | 4.1.0 |
| **AI** | Spring AI | 2.0.0 |
| **Frontend** | Vue | 3.5.39 |
| **UI** | Vuetify (Material Design 3) | ^3.12.9 |
| **Desktop** | Tauri | 2.0 |
| **i18n** | vue-i18n | ^10.0.8 |
| **Database** | JPA + Hibernate (H2 / SQLite / MySQL / PostgreSQL) | ddl-auto=update |
| **Plugin worker I/O** | newline-delimited JSON-RPC 2.0 | — |
| **License** | GPL-3.0 | — |

---

## Database

Infinia uses **JPA + Hibernate** and supports **four database backends**, chosen at first launch
via a setup wizard. No database knowledge is required for the default local experience.

### First-launch setup wizard

On first launch (no `~/.fengyu/config/datasource.properties`), the backend boots in **SETUP mode**
and the frontend shows a wizard that lets you pick a database:

- **H2** (default, local embedded) — zero configuration.
- **SQLite** (local embedded) — single-file database.
- **MySQL** (remote) — for multi-user or server deployment.
- **PostgreSQL** (remote) — for multi-user or server deployment.

The wizard tests the connection, persists the configuration to
`~/.fengyu/config/datasource.properties` (passwords AES-GCM encrypted, machine-bound), then exits
(`SETUP_DONE=0`). The Tauri/desktop supervisor restarts the backend into **APP mode**, where
Hibernate `ddl-auto=update` creates the schema from the JPA entities. To reconfigure, delete
`datasource.properties` and restart — the wizard reappears. See [Database](docs/en/guide/database).

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

- 🌐 **Online docs:** https://muskstark.github.io/FengYu/ (English + 简体中文)
- [CHANGELOG](CHANGELOG.md) — Release history
- [AGENTS.md](AGENTS.md) — Technical documentation for AI assistants

---

**Built with ❤️ using Spring Boot, Vue 3, and Tauri.**
