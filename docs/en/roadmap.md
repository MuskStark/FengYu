# Infinia 4.0 Roadmap

This roadmap prioritizes shipping a dependable 4.0 release over expanding the feature set. Target
windows create urgency, but a release advances only when its exit criteria are satisfied.

## Current decision

**Ship `v4.0.0-beta.5` before the first release candidate.** The current change set is too broad for
an RC: it combines Flow authoring and recovery, code-first plugin contracts, Agent/tool-loading
changes, desktop updating, UOS packaging, and documentation updates. Plugin Toolchain 2.1.0 is also
present in source and documentation but has not yet been published under a matching remote tag.

Snapshot from 2026-08-24:

- the working tree differs from `v4.0.0-beta.4` across 522 files (about 31,900 additions and 8,900
  deletions);
- the working tree still contains tracked and untracked work, so it is not tag-ready;
- focused backend, frontend, desktop, CLI, Java SDK/DevKit, Go SDK, and documentation checks pass;
- the full release assembly, host smoke test, Web archive self-check, Python SDK test, and complete
  cross-platform release matrix still need final evidence.

## Release train

| Target window | Milestone | Change policy | Exit result |
|---|---|---|---|
| 2026-08-24 to 2026-08-28 | Scope freeze and `v4.0.0-beta.5` | Finish only work already in the agreed 4.0 scope | One complete feature-bearing beta |
| 2026-08-29 to 2026-09-03 | Beta stabilization | P0/P1 fixes; low-risk tests and documentation only | No known release-blocking regression |
| 2026-09-04 target | `v4.0.0-rc.1` | No new features, public contracts, or migrations | GA-shaped build for final validation |
| 2026-09-05 to 2026-09-10 | RC soak | Blocking fixes only; any functional fix may require another RC | All distribution and upgrade paths accepted |
| 2026-09-11 target | `v4.0.0` | Version and release documentation only | General availability |
| After GA | `4.0.x` stabilization | Backward-compatible fixes only | Stable maintenance line |

If a gate misses its window, move the milestone rather than weakening the gate.

## Phase 1 — Freeze and ship beta.5

### Included scope

- Headless Spring Boot backend, first-launch setup, loopback-only Web runtime, and Electron desktop
  shell.
- Visual workflows: typed nodes, IF and LLM nodes, single-step debugging, input/output bindings,
  local draft recovery, restart checkpoints, and durable execution telemetry.
- Agent runtime: dynamic tool loading, MCP controls, background-task scheduling, approvals, and
  persisted notifications.
- Plugin platform: schema v2, code-first Java/Python/Go contracts, generated manifests and clients,
  sandboxed workers, and the four official plugins.
- Distribution: portable Web archives, Windows/macOS/Linux desktop variants, UOS packages,
  checksums, updater behavior, and release documentation.

### Required work

1. Stop accepting new 4.0 features and agree on one release commit/branch.
2. Resolve every tracked deletion and untracked source file intentionally; tag only a clean tree.
3. Publish Plugin Toolchain `2.1.0` through its independent release line before claiming that
   version is available to plugin authors. Do not couple its version to the app version.
4. Fold the current `Unreleased` changelog into `4.0.0-beta.5` and synchronize all app-version
   mirrors.
5. Complete the release verification matrix below and exercise the generated beta.5 artifacts.

### Beta.5 exit criteria

- No P0 defects and no known P1 defect without an explicit release-blocking decision.
- Clean install and upgrade from beta.4 preserve settings, workflows, plugin state, and database
  migrations.
- Install, update, invoke, restart, and uninstall pass for all four official plugins.
- Windows portable updating succeeds while a plugin worker has previously been opened.
- The GitHub release workflow publishes every expected artifact and checksum sidecar.

## Phase 2 — Stabilize beta.5

Use beta.5 as the compatibility and packaging rehearsal. Test real artifacts, not only development
servers or locally assembled JARs.

Required scenarios:

- fresh setup with H2/SQLite plus at least one external database path;
- beta.4 to beta.5 application update, including the Windows portable path;
- Flow creation, draft recovery, execution, restart recovery, retry/skip telemetry, and published
  workflow invocation;
- plugin package validation and lifecycle across Java, Python, and Go workers;
- Web archive launch and desktop launch on Windows, macOS Apple Silicon, Linux, and UOS;
- offline or intranet update failure handling with actionable logs and a recoverable installation.

Only P0/P1 fixes enter during this phase. A P2 fix may enter only when it is localized, covered by a
regression test, and cannot alter a public contract or persistence format.

## Phase 3 — Release candidate

RC1 is a declaration that 4.0 is feature-complete and contract-complete. It is not another beta.

### RC1 entry criteria

- beta.5 has completed the stabilization scenarios with no open P0/P1 defects;
- Plugin Toolchain 2.1.0 is published and a clean generated plugin can complete `init`, `dev`,
  `check`, `build`, install, and invoke against the app;
- no pending schema, REST/SSE, database migration, updater, or packaging change remains;
- the beta.5-to-RC diff contains only fixes, tests, documentation, and release metadata;
- every app-version mirror resolves to `4.0.0-rc.1` and the release workflow contract tests pass.

### RC soak rule

If RC validation requires a functional code change, cut `rc.2`. Promote RC1 directly to GA only
when GA needs release metadata and documentation changes, not a new binary behavior.

## Phase 4 — General availability

GA requires:

- the full Windows/macOS/Linux release matrix and Linux host smoke tests to be green;
- portable Web ZIP and tarball self-checks to pass;
- expected JAR, desktop, UOS, `.fyp`, checksum, and updater metadata artifacts to be present;
- release notes to state that desktop packages are unsigned and accurately describe integrity
  verification;
- installation, upgrade, rollback/recovery, and troubleshooting documentation to match the shipped
  artifacts;
- zero open P0/P1 defects.

## Release verification matrix

| Surface | Required gate |
|---|---|
| Backend | Full Maven reactor tests/package plus `scripts/e2e-smoke.sh` |
| Frontend | Immutable install, production audit, build, unit tests, Node tests, and typecheck |
| Desktop | Production audit, unit tests, TypeScript build, and gating launch E2E on Linux/macOS |
| Plugin platform | CLI/spec tests, Java/Python/Go SDK tests, DevKit tests, and four official `.fyp` builds |
| Web distribution | `package-web-release.sh` followed by `test-web-release.sh` |
| Documentation | EN/ZH structural parity, generated changelog sync, and VitePress build |
| Release workflow | Version and workflow contract tests plus complete artifact/checksum inspection |

## Defect policy after beta.5

| Severity | Definition | Release handling |
|---|---|---|
| P0 | Security exposure, data loss, corrupted update, or app cannot launch | Blocks every milestone |
| P1 | Core Agent/Flow/plugin lifecycle is unusable, or a supported platform cannot install/update | Blocks RC and GA |
| P2 | Localized functional or usability defect with a viable workaround | Defer unless the fix is isolated and fully covered |
| P3 | Polish, optimization, or feature request | Move to the post-4.0 backlog |

## Explicitly deferred until after 4.0

- new official plugins or new Flow node families;
- another plugin manifest/schema generation model;
- additional AI providers or a new tool-loading architecture;
- LAN/public binding, multi-user collaboration, or remote server deployment;
- another shell/navigation redesign;
- desktop code-signing/notarization work that is not already configured for the 4.0 release line.

The first post-GA planning checkpoint should rank these items for 4.1 only after 4.0.x field data
shows that launch, update, Flow execution, and plugin lifecycle are stable.

## Key risks and controls

| Risk | Control |
|---|---|
| Large beta.4-to-current delta | Ship beta.5 and require an artifact soak before RC |
| Toolchain 2.1.0 source/docs without a matching release | Publish the independent toolchain first or remove availability claims |
| Long-lived/dirty feature branch | Select one clean release commit and prohibit tags from a dirty tree |
| Cross-platform behavior differs | Make the release workflow matrix and desktop launch E2E the evidence source |
| Windows updater leaves plugin descendants alive | Test an update after opening a plugin and inspect the updater log |
| Generated manifest, docs, or lockfile drift | Keep generation, immutable install, spec-sync, and release-contract checks gating |
