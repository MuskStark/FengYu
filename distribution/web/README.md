# Infinia — Portable Web Distribution (Alpha)

A self-contained archive that runs the Infinia backend (a loopback-only Spring Boot web server)
and serves the bundled Vue single-page app from any folder. No installer; unzip and run.

> **Alpha status.** These packages are **unsigned**. Code-signing, a bundled JRE, and an auto-updater
> are deferred to a later release. Treat this build as a preview.

## Requirements

- **Java 21** (JDK or JRE) on `PATH`, or pointed at by `JAVA_HOME`.

## Run

Unzip the archive, then from the extracted folder:

- **macOS / Linux:** `./run.sh`
- **Windows:** `run.bat`

The backend prints its address (default loopback port `24056`, with a fallback to an OS-assigned
free port if it is taken) and serves the UI at that port. Open `http://127.0.0.1:<port>` in your
browser. On first launch the setup wizard initializes the local datasource; refresh after it completes.

### Optional arguments

Forwarded to the launcher (`run.sh --port=8080 --token=<secret>`):

- `--port=<n>` — bind a specific loopback port (`0` = pick a free one).
- `--token=<t>` — require every API request to carry `X-FengYu-Token: <t>`. When unset, token auth
  is disabled (loopback-only bind keeps other local apps from reaching the server).

## Scope

The backend binds **loopback only** (`127.0.0.1`); it is not reachable from other machines. The
bundled `plugins/` directory holds the official plugins (`markdown`, `excel`, `email`).
