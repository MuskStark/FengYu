---
name: toolchain-release
description: Cut an independently versioned FengYu plugin-toolchain release (tag plugin-tooling-vX.Y.Z or workflow_dispatch input). Covers fan.summer.fengyu.sdk:fengyu-plugin-sdk, fan.summer.fengyu.sdk:fengyu-plugin-devkit, @infinia/plugin-sdk, @infinia/plugin-ui, @infinia/plugin-cli, and @infinia/plugin-dev. Synchronizes the release version across all six, validates lockfiles and package contents, builds official plugins through the CLI, and exercises the local toolchain smoke path. Use when the user asks to release/publish the plugin SDK, UI kit, CLI, devkit, or dev plugin. Does NOT change the main application version — use app-release for that.
---

# Plugin Tooling Release

Release the **independently versioned plugin toolchain** — six artifacts that move together at one
version:

| Artifact | Source of truth |
|---|---|
| `fan.summer.fengyu.sdk:fengyu-plugin-sdk` | `toolchain/sdk-java/pom.xml` |
| `fan.summer.fengyu.sdk:fengyu-plugin-devkit` | `toolchain/devkit-java/pom.xml` |
| `@infinia/plugin-cli` | `toolchain/cli/package.json` |
| `@infinia/plugin-dev` | `toolchain/dev/package.json` |
| `@infinia/plugin-sdk` | `toolchain/sdk-ts/package.json` |
| `@infinia/plugin-ui` | `toolchain/ui/package.json` |

This skill does **not** change the main app version (`${revision}`). Use the `app-release` skill for
app releases.

The release contract is `.github/workflows/toolchain-release.yml` plus
`toolchain/cli/scripts/resolve-tooling-version.mjs`.

## Step 1 — Resolve and verify the version

The resolver accepts a `plugin-tooling-vX.Y.Z` tag, a `--ref`, or an explicit `--input`, validates
strict semver (no leading zeros), and cross-checks all six sources via `verifyRepositoryVersion`:

```bash
node toolchain/cli/scripts/resolve-tooling-version.mjs --ref plugin-tooling-vX.Y.Z   # prints the version
```

If it throws (invalid semver, or any of the six sources disagree), stop and reconcile the versions
with the user before proceeding.

## Step 2 — Synchronize the version across all six artifacts

Bump the version in exactly these six files to the intended release version and nothing else:

- `toolchain/sdk-java/pom.xml` (`<version>`)
- `toolchain/devkit-java/pom.xml` (`<version>`)
- `toolchain/cli/package.json`
- `toolchain/dev/package.json`
- `toolchain/sdk-ts/package.json`
- `toolchain/ui/package.json`

Also update any **dependent range** that is meant to track the release (e.g. a `@infinia/*`
peer/dependency range that must move with the release, the `fengyu.plugin.sdk.version` property
in `pom.xml` and `toolchain/devkit-java/pom.xml`, or the `${fengyu.plugin.sdk.version}` reference in
the scaffolded `worker/pom.xml.tpl`). Do **not** touch `pom.xml` `${revision}` or any app-side
manifest.

## Step 3 — Validate lockfiles and package contents

- Regenerate/confirm lockfiles for the four npm packages are consistent with the bumped versions.
- Run each package's own checks:

```bash
# CLI
cd toolchain/cli && npm install && npm test && cd ../..
# Dev plugin (Vite host simulator)
cd toolchain/dev && npm install && npm test && cd ../..
# TS SDK
cd toolchain/sdk-ts && npm install && npm test && cd ../..
# UI kit
cd toolchain/ui && npm install && npm run prepack && npm run test:visual && cd ../..
# Java Worker SDK
mvn -f toolchain/sdk-java/pom.xml test
# Java Plugin DevKit
mvn -f toolchain/devkit-java/pom.xml test
```

- Enforce packaging boundaries:

```bash
scripts/check-plugin-dependency-boundaries.sh
```

Treat CLI templates and the UI kit as one compatibility contract even though they are separate npm
packages. Release verification must cover the exact icon/component inputs emitted by the scaffold,
including `mdi-*` names, and must keep the host/plugin theme equality test green. A CLI template that
requires plugin authors to work around the released SDK/UI is a release blocker.

## Step 4 — Build official plugins through the CLI

Confirm the toolchain can actually produce a plugin end to end by building an official plugin with
the CLI (this is the same path the release's `consumer-smoke` job exercises against published
packages):

```bash
fengyu plugin build OfficialPlugins/plugin-markdown
```

Repeat for the other official plugins the toolchain must support (`plugin-excel`, `plugin-email`,
`plugin-offlinepython`).

## Step 5 — Exercise the local toolchain smoke path

```bash
scripts/plugin-tooling-local-smoke.sh
```

This installs the Java SDK to local `.m2`, packs the TS SDK/UI kit, and confirms a consumer can
resolve and use them locally. It must pass before release mutation. Also confirm docs still build
(the release workflow's `verify` job runs this):

```bash
npm --prefix docs run build
```

## Step 6 — Mutate, with explicit confirmation

**Committing, tagging, pushing, dispatching the workflow, or publishing to a registry each require
explicit user confirmation.** Do not run these automatically. When the user confirms:

1. Commit the four version bumps (+ any tracked dependent range) with a `⬆️`/`📝` conventional message.
2. Create the tag `plugin-tooling-vX.Y.Z`.
3. Push the branch and the tag — the tag push triggers
   `.github/workflows/toolchain-release.yml`, which verifies, publishes
   `fengyu-plugin-sdk` to GitHub Packages and the three `@infinia/*` packages to npm (with provenance),
   then runs a consumer smoke against the just-published packages. (Manual `workflow_dispatch` with a
   `tooling_version` input is the alternative trigger.)

**Never** bump the main app version (`${revision}`) in order to release the toolchain.
