# Plugin SDK and CLI Lifecycle Remediation Design

## Background

The implementation of `2026-07-15-plugin-sdk-cli-lifecycle.md` landed as nine focused commits and its current unit suites pass, but review found several gaps that prevent the lifecycle from meeting its end-to-end goal:

- generated Maven wrappers lose executable permissions;
- the generated worker artifact path does not match Maven output;
- the TypeScript SDK builds through `frontend/node_modules` and is not independently publishable;
- release version resolution and clean-consumer CLI invocation are invalid;
- packaged worker JARs are not checked against the configured `Main-Class`;
- offline installation uploads backend packages whose worker is missing;
- package resource destinations are converted into source-tree absolute paths;
- worker development rebuilds do not fully isolate rebuilding state or guarantee cleanup.

The remediation must fix the lifecycle as a whole rather than only patching the first failing command.

## Goals

1. A default `fengyu plugin create` project builds on macOS, Linux, and Windows with the generated Maven Wrapper.
2. All three npm packages and the Java SDK build and publish from a clean checkout without relying on undeclared repository-level dependencies.
3. Source, staging, archive, install, and host validation agree on the runtime package contract.
4. Invalid or unsafe packages are rejected before upload whenever the CLI has enough information to reject them locally.
5. `plugin dev` never leaks a worker, serves a worker that is being replaced, or reports a successful restart before the replacement process has spawned.
6. The release workflow resolves one semantic version, verifies it across all packages, and proves a clean published consumer can create and build a plugin.

## Non-Goals

- Changing the runtime manifest schema version.
- Changing the JSON-RPC protocol or adding new plugin capabilities.
- Refactoring the host marketplace installation architecture beyond sharing equivalent validation rules.
- Publishing a new tooling version as part of the remediation implementation itself.
- Redesigning the plugin UI templates.

## Design

### 1. Preserve scaffold file metadata and correct generated paths

`createPlugin` will treat template files as files, not only as UTF-8 strings. Text templates still receive placeholder replacement, while non-template files are copied byte-for-byte. The destination mode will be copied from the source mode so `mvnw` remains executable on Unix.

The generated build declaration will resolve the worker artifact from the project root as `worker/target/<finalName>.jar`, matching the current `normalizeWorker` contract and Maven output. Tests will assert both the normalized artifact path and the executable mode.

### 2. Make the TypeScript SDK a self-contained npm package

`plugin-sdk/typescript` will declare TypeScript as its own development dependency and call `tsc` through npm's local binary resolution. Its test and prepack scripts must succeed when only that package directory and its lockfile are present.

Release verification will copy or pack the package into an isolated directory and run its declared build, preventing repository-root `node_modules` from masking undeclared dependencies.

### 3. Centralize archived runtime validation

The CLI will expose one archive-level validator that:

- inspects archive size, expanded size, duplicate entries, absolute paths, and traversal paths;
- reads `manifest.json` safely;
- applies `validateManifestObject`;
- verifies `ui.entry` exists and is a regular archive entry;
- when a backend is declared, requires the canonical command and protocol plus `backend/worker.jar`;
- opens the worker JAR without executing it;
- reads `META-INF/MANIFEST.MF`, unfolds continuation lines, and compares `Main-Class` with the configured or declared worker class where available;
- verifies the corresponding class entry exists for declared builds.

Build validation and install validation will use the same low-level archive-reading helpers. The host remains Java-based, but its fixture tests must demonstrate equivalent acceptance and rejection for shared manifest cases.

### 4. Keep resource source paths and runtime destinations as different types

`package.resources[].from` is a project filesystem path and remains an absolute normalized path after validation. `package.resources[].to` is an archive-relative POSIX path and remains relative after validation. A dedicated resolver will reject empty paths, absolute paths, drive-letter paths, `.`/`..` segments, backslashes, and destinations that overlap protected runtime files such as `manifest.json`.

Staging will join the validated relative destination directly beneath the staging root. Tests will inspect the resulting archive paths.

### 5. Make worker development restart atomic and cleanup-safe

`startWorker` will resolve only after the child emits `spawn`, and reject on `error` or premature exit. Every request terminal path will clear timers and abort listeners through one helper.

During rebuild, the simulator returns `worker rebuilding` instead of forwarding new calls to the old worker. Source changes received during a build set a dirty flag and trigger one additional rebuild after the current build finishes. The old worker remains alive until the replacement has spawned, then closes exactly once.

`dev().close()` will stop new rebuilds, close the watcher, await an active rebuild, and close whichever replacement or current worker remains. Tests will cover close-during-rebuild and spawn failure.

### 6. Resolve and verify release versions once

The release workflow will add a version-resolution job or step that:

- uses the manual input when dispatched;
- otherwise strips `plugin-tooling-v` from the tag;
- validates semantic version syntax;
- compares the result with all three npm `package.json` files and the Java SDK POM;
- exposes the normalized version to every downstream job.

Consumer smoke will invoke the CLI through `npx @fengyu/plugin-cli@${VERSION}` for every command, or install it locally and use `npm exec`; it will never rely on a prior `npx` invocation leaving a binary on `PATH`.

The verify job will run package-content checks, official plugin builds, the host build, the end-to-end smoke script, and the documentation build before publication jobs can start.

## Testing Strategy

The remediation follows test-driven development:

- unit tests reproduce each reviewed defect before implementation;
- generated-project tests inspect actual modes and normalized paths;
- package tests operate from isolated temporary directories;
- archive tests use deliberately malformed packages and JARs;
- dev tests use controllable fake clients and spawn failures;
- workflow tests use a small local script for version resolution instead of embedding untestable shell logic;
- final verification repeats the original plan's complete repository command set.

The completion gate is a clean consumer that uses published npm and GitHub Packages artifacts without `file:` dependencies. Local tarball smoke is supplementary and cannot replace the published consumer smoke.

## Compatibility

- Existing declared official plugins retain their current artifact paths because their worker roots are `.`.
- UI-only and legacy zero-config plugins continue to omit backend validation.
- Existing manifests remain schema version 1.
- Windows uses `mvnw.cmd`; Unix mode preservation applies only where executable bits exist.
- Existing package resource declarations that use safe relative destinations keep the same intended archive layout, while currently incorrect absolute-prefix output is corrected.

## Completion Criteria

- The default generated project has executable `mvnw` and resolves its worker artifact under `worker/target`.
- The TypeScript SDK builds in an isolated directory.
- A backend package missing `backend/worker.jar` is rejected before fetch.
- A worker JAR with a mismatched `Main-Class` is rejected.
- A package resource declared as `runtime-assets` appears exactly at `runtime-assets/**`.
- Closing dev during rebuild leaves zero worker processes.
- Tag and manual release paths resolve the same semantic version format.
- The full verification sequence and clean published consumer smoke pass.
