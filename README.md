# Infinia

![Infinia](https://img.shields.io/badge/Infinia-Web%20%2B%20Desktop-blue) ![Java](https://img.shields.io/badge/Java-21-orange) ![License](https://img.shields.io/badge/License-GPL--3.0-blue) ![Maven](https://img.shields.io/badge/Maven-3.6+-red) ![Version](https://img.shields.io/badge/version-4.0.0--alpha.1-blue)

**Infinia** (蜂语 / FengYu) is an *AI-native orchestration platform*. A plan-and-execute Agent
turns natural-language goals into multi-step business workflows by orchestrating three extension
surfaces — `.fyp` plugins, `.fys` skills, and in-process AI tools. It runs as a headless Spring Boot
backend, a Vue 3.5 + Vuetify 3 UI, and an optional Electron desktop shell; built-in tools (Excel
splitting, email, markdown, and more) ship as official plugins the Agent can call.

> ### 🚧 4.0.0-alpha.2 — web + desktop
> This branch (`4.0.0-FengYu`) re-architects Infinia from a JavaFX desktop app into a **web +
> desktop application**: a **headless Spring Boot backend** (loopback web server, no window), a
> **Vue 3.5 + TypeScript** frontend (identical for browser and desktop), and an **Electron 43.x**
> desktop shell that sidecar-launches the Java backend. Built-in tools become official plugins that
> expose a JSON-RPC worker backend plus a micro-frontend UI bundle. JavaFX has been removed.
> See [`CHANGELOG.md`](CHANGELOG.md) and the [online docs](https://muskstark.github.io/FengYu/) for the current state.
>
> Run the backend: `java -jar FengYu/target/FengYu-4.0.0-alpha.2.jar --token=<t>` (binds port 24056 by default)
> · frontend: `cd frontend && npm run dev` · smoke test: `scripts/e2e-smoke.sh`.
>
> The official **Email Center** plugin now ships as `fan.summer.email`: five sandboxed UI tabs,
> multi-account SMTP/IMAP, manual-only collection, encrypted credentials, and seven confirmation-first AI tools.
> See [Email Center](docs/en/plugins/email-center.md) and the [plugin database standard](docs/en/plugins/database.md).
>
> **Skills** — Codex-style progressive disclosure: enabled skills appear as a compact catalog in
> the system prompt, and the assistant loads a skill's full body on demand via the built-in
> `skill` tool. Skills are managed as `.fys` packages alongside plugins — both live on the
> **Plugins** page (`/plugins`), with a single Upload button accepting `.fyp` and `.fys`.
> See [Skills](docs/en/skills/).

---

## Quick Start

**Requirements:**

- **JDK 21 or higher** (recommended: [Eclipse Temurin](https://adoptium.net/))
- **Node 24.18.0 and npm** (for the frontend dev server, plugin UIs, and the Electron desktop shell)

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
java -jar FengYu/target/FengYu-4.0.0-alpha.2.jar --token=<your-token>
```

### Run the Frontend (dev)

```bash
cd frontend && npm install && npm run dev   # Vite proxies /api + /plugin-runtime to :24056
```

### Run the Desktop Shell (dev)

```bash
cd desktop/electron && npm install && npm run dev   # set FENGYU_JAR or run the backend on :24056
# release: cd desktop/electron && npm run build
```

### Smoke Test

`scripts/e2e-smoke.sh` boots the jar and probes every endpoint.

### Releases (Alpha)

Pushed release tags (`v4.0.0`, `v4.0.0-alpha.2`, `-beta.*`, `-rc.*`) trigger
[`.github/workflows/fengyu-release.yml`](.github/workflows/fengyu-release.yml), which publishes:

- **Unsigned Electron packages** for Windows, macOS, and Linux — two variants per platform: a
  lightweight build (needs Java 21+ on PATH) and a self-contained build that bundles a jlink-minimized
  JRE. The Electron shell ships with a tray, file logging, and an auto-updater (GitHub Releases).
- A **portable Web distribution** (`Infinia-<version>-web.zip` / `.tar.gz`) — unzip and run `./run.sh`
  (macOS/Linux) or `run.bat` (Windows). Requires **Java 21**; the backend binds **loopback only**
  (`127.0.0.1`) and is not reachable from other machines.

These are **unsigned Alpha builds**: code-signing is deferred to a later release.

---

## Features

- **🤖 AI Agent (the spine)** — A plan-and-execute Agent decomposes a goal into steps and orchestrates the surfaces below. Sensitive actions require your approval. Multi-backend (Ollama, OpenAI, Anthropic, DeepSeek) with streaming, thinking cards, and tool calls. See [AI Agent](docs/en/guide/ai-agent) / [AI Chat](docs/en/guide/ai-chat).
- **🧩 Plugins (`.fyp`)** — Capabilities the Agent calls: isolated packages of a JSON-RPC worker + micro-frontend UI, installed from the marketplace. See [Marketplace](docs/en/plugins/marketplace).
- **📜 Skills (`.fys`)** — Progressive-disclosure domain knowledge and procedures the Agent loads on demand. See [Skills](docs/en/skills/).
- **📊 Excel Splitter** — Split workbooks by sheet, column value, or complex rules — an official plugin with six AI tools. See [Excel](docs/en/plugins/official-excel).
- **📧 Email Center** — Multi-account SMTP/IMAP, contact/tag management, filename-tag batch sending, manual archive collection, and seven confirmation-first AI tools. See [Email Center](docs/en/plugins/email-center.md).
- **📝 Markdown Editor** — Split-pane editor with isolated server-side rendering. See [Markdown](docs/en/plugins/official-markdown).
- **💾 Multi-Database** — First-launch wizard picks H2, SQLite, MySQL, or PostgreSQL; passwords AES-GCM encrypted. See [Database](docs/en/guide/database).
- **🎨 Material Design 3** — Vuetify 3 MD3 UI, shared with plugin micro-frontends, dark and light themes. See [Design System](docs/en/design-system).
- **🌍 Internationalization** — English-first docs and a localized Vue UI (vue-i18n).

## How it works

You state a business goal in chat; the Agent plans steps and calls the best-fit surface — a `.fyp`
plugin for a concrete capability, a `.fys` skill for domain procedure, or an in-process AI tool.
Steps that touch the outside world (sending email, writing files, mutating data) need your explicit
approval. Results flow back into the conversation, and the Agent re-plans on failure. See the
[Features](docs/en/features) page for the full capability matrix.

---

## Architecture

Infinia 4.0.0 is a **three-layer web + desktop application**:

```
┌──────────────────────────────────────────────────────────────┐
│  Electron desktop shell (optional)   ·   Browser (default)   │
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
`X-FengYu-Token` header. The desktop shell sidecar-launches the JAR, waits for health, and exposes
the token + api-base to the renderer via a `contextBridge` preload. See [Architecture Overview](docs/en/architecture/overview).

### Project Modules

| Module / dir | Purpose |
|--------|---------|
| `FengYu-Api` | Plugin + AI contract (`manifest.json` schema, worker JSON-RPC protocol, `AiTool`). |
| `toolchain/sdk-java` | Java Worker SDK + TypeScript `@infinia/plugin-sdk` (the iframe `postMessage` bridge, in `toolchain/sdk-ts`). |
| `OfficialPlugins` | Official plugins: `plugin-markdown`, `plugin-excel`, `plugin-email` (each ships a `.fyp`). |
| `FengYu` | Headless Spring Boot backend — REST/SSE controllers, AI backends, JPA/Hibernate, marketplace. |
| `frontend/` | Vue 3.5 + TS SPA (runs identically in the browser or the Electron BrowserWindow). |
| `desktop/` | Electron 43.x desktop shell — sidecar-launches the JAR, tray, native dialogs, auto-updater. |
| `toolchain/ui/` | `@infinia/plugin-ui` — the official Vue/Vuetify component kit for plugin micro-frontends. |
| `toolchain/cli/` | `fengyu plugin` CLI — `create`, `build` (development moved to the IDE via `toolchain/dev/` + `toolchain/devkit-java/`). |
| `toolchain/dev/` | `@infinia/plugin-dev` — Vite plugin that turns the dev server into a FengYu host simulator for IDE debugging. |
| `toolchain/devkit-java/` | `fengyu-plugin-devkit` — loopback-TCP JSON-RPC dev server (`PluginDevMain`) so worker breakpoints fire in the IDE. |

### Plugin System

Plugins are isolated **`.fyp`** packages (a zip of `manifest.json` + `ui/` + `backend/worker.jar`).
The UI runs in a **sandboxed iframe** and talks to the host through the `@infinia/plugin-sdk`
`postMessage` bridge; the backend is an **out-of-process worker** speaking newline-delimited
JSON-RPC 2.0 over stdio. A worker crash can never take down the host, and workers never touch the
host Spring context or JPA session.

File selection uploads into a scoped temporary grant; on desktop the Electron shell resolves a native
path into the same opaque `FileRef`. Plugin UI code is identical on both targets and never sees an
absolute path. Plugins that need persistence declare the `database` permission and get an
injected datasource connection (table-name-prefixed, plugin-owned schema).

Third-party authors scaffold with `fengyu plugin create`, develop in their IDE (the `@infinia/plugin-dev`
Vite plugin + `fengyu-plugin-devkit`'s `PluginDevMain` give them real breakpoints in UI and worker),
and package with `fengyu plugin build` — there is no `FengYuPluginV2` interface and no in-host JavaFX.
See the [Plugin Overview](docs/en/plugins/overview).

---

## Tech Stack

| Category | Technology | Version |
|---|---|---|
| **Language** | Java | 21 |
| **Backend** | Spring Boot | 4.1.0 |
| **AI** | Spring AI | 2.0.0 |
| **Frontend** | Vue | 3.5.39 |
| **UI** | Vuetify (Material Design 3) | ^3.12.9 |
| **Desktop** | Electron | 43.x |
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
(`SETUP_DONE=0`). The desktop supervisor restarts the backend into **APP mode**, where
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

**Built with ❤️ using Spring Boot, Vue 3, and Electron.**
