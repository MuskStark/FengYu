# Alpha Desktop and Web Release

## Problem

The existing `.github/workflows/fengyu-release.yml` cannot publish the current
4.0 application reliably. It is invalid YAML and still packages the deleted
JavaFX application with Launch4j and `jpackage`. FengYu 4.0 is now a Vue SPA, a
headless Spring Boot backend, and a Tauri desktop shell. The release pipeline
must publish those real runtime components together.

The release target is `v4.0.0-alpha.1`, with both desktop packages and a
downloadable self-hosted Web package. Alpha artifacts may be unsigned and may
require a system Java 21 runtime.

## Goals

- Publish unsigned Tauri desktop artifacts for Windows, macOS, and Linux.
- Publish a portable Web archive that starts the Java backend and serves the
  Vue SPA on the same loopback origin.
- Bundle the backend JAR and official `.fyp` plugins into both distributions.
- Mark semantic prerelease tags such as `v4.0.0-alpha.1` as GitHub
  prereleases.
- Derive artifact names and build versions from the release tag.
- Generate SHA-256 checksums and clear Java 21/runtime warnings.

## Decisions

- **Runtime requirement:** Alpha packages require Java 21 on `PATH`. Bundling a
  per-platform JRE is deferred until the production packaging phase.
- **Desktop format:** Use Tauri's native bundle output. Packages are unsigned
  and not notarized for Alpha.
- **Web format:** Publish a portable zip/tar archive containing one executable
  backend JAR, official plugins, README, and launch scripts. The SPA is embedded
  in the JAR and served by Spring Boot.
- **Network scope:** Preserve the loopback-only `127.0.0.1` bind. This Web
  package is for a user running and opening FengYu on the same machine; remote
  and public hosting are outside this release.
- **Version source:** The Git tag is authoritative during release builds. The
  workflow passes the resolved version to Maven and synchronizes the frontend
  and Tauri build metadata without requiring release-only source commits.

## Architecture

### Shared application build

The workflow builds the frontend first, then packages its `dist/` output into
the Spring Boot JAR as classpath static assets. The resulting JAR owns the
same-origin Web runtime:

```text
browser or Tauri webview
          |
          v
Spring Boot on 127.0.0.1:<port>
  |- /, /settings, /plugins ... -> Vue SPA
  |- /api/**                    -> REST/SSE backend
  `- /plugin-runtime/**         -> installed plugin UI assets
```

A Spring MVC SPA fallback forwards non-file, non-API application routes to
`index.html`. API and plugin resource paths are never swallowed by the
fallback.

### Desktop distribution

Each operating-system job receives or rebuilds the shared JAR and official
plugin packages, stages them as Tauri resources, and runs the Tauri release
build. Runtime resource lookup uses Tauri's resource directory rather than the
process working directory. The shell starts Java 21, passes its per-launch
token, waits for health, and opens the embedded Vue UI.

The release includes the native Tauri bundle files that exist for the runner's
platform. It does not claim code signing or automatic update support.

### Web distribution

The Web archive contains:

```text
Infinia-<version>-web/
  Infinia.jar
  plugins/*.fyp
  run.sh
  run.bat
  README.md
```

The scripts verify that Java 21 is available, start the JAR with the official
plugin directory configured, and print the local URL. Authentication remains
disabled only because the backend is loopback-only; the Web archive does not
open a public listener.

## Release Workflow

`fengyu-release.yml` is replaced with a valid workflow organized into these
jobs:

1. **Setup** validates `vMAJOR.MINOR.PATCH` plus optional
   `-alpha.N`, `-beta.N`, or `-rc.N`, resolves the Maven/Tauri-compatible
   versions, and reports whether the release is a prerelease.
2. **Build and test** installs Node and Java 21, builds official plugin UI and
   `.fyp` packages, runs frontend checks/build, runs Maven tests/package, and
   uploads the shared runtime inputs.
3. **Web package** assembles and smoke-tests the portable archive, including a
   health probe from the extracted artifact.
4. **Desktop matrix** builds Tauri packages on Windows, macOS, and Linux and
   uploads whatever native bundle formats Tauri produces.
5. **Release** downloads all artifacts, generates `checksums.txt`, and creates
   or updates a GitHub Release. Prerelease tags set `prerelease: true`.

Manual dispatch checks out the requested tag or commit explicitly. Tag pushes
use the tagged source. Invalid or missing tag input fails before expensive
matrix builds begin.

## Version Handling

- Maven receives `-Drevision=<version>` so the shaded JAR and build metadata use
  `4.0.0-alpha.1`.
- The frontend receives the release version through a build environment value;
  `vite.config.ts` prefers that value over `package.json` for
  `__APP_VERSION__`.
- Tauri receives a numeric package version acceptable to all target packagers.
  The full prerelease remains present in artifact and GitHub Release names.
- Official plugin resource discovery must not depend on a hard-coded
  `4.0.0` filename. Staging selects the packages produced by the current build.

## Error Handling

- Invalid release tags fail in `setup` with the accepted formats listed.
- Missing JAR, frontend bundle, plugin package, or Tauri bundle fails its job;
  artifact uploads do not silently ignore required files.
- Desktop startup reports a clear Java 21 error when `java` is absent or too
  old.
- The Web smoke test extracts the final archive, launches it in an isolated
  temporary home, waits for `/api/health`, then terminates the process.
- Release creation depends on all platform and Web jobs, so a partial build is
  not published as a complete Alpha release.

## Testing

- Parse the workflow as YAML and lint it with `actionlint` when available.
- Run frontend type checking/tests and production build.
- Run Maven tests and package with an Alpha `revision` override.
- Run `cargo check` and a Tauri release build on the host platform.
- Inspect the produced JAR for `static/index.html` and test SPA route fallback.
- Smoke-test the extracted Web archive and `/api/health`.
- Verify staged desktop resources contain the backend JAR and official plugin
  packages.
- Exercise tag parsing for stable, alpha, beta, rc, and invalid inputs.

## Documentation Impact

The implementation updates the release/download instructions and desktop
runtime requirements that are directly changed by this work. Historical
planning documents are not version-synchronized. A release changelog entry is
added only when the Alpha version is actually prepared, rather than during the
pipeline design step.

## Non-goals

- Bundled JRE images.
- Windows/macOS code signing, macOS notarization, or Linux repository signing.
- Public/LAN Web serving, TLS termination, multi-user authentication, or CORS
  policy changes.
- Container images or automatic deployment to a server.
- Tauri updater feeds and in-app automatic upgrades.
