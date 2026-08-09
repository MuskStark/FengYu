---
name: app-release
description: Cut a main FengYu/Infinia application release (tag vX.Y.Z or vX.Y.Z-{alpha|beta|rc}.N). Validates the tag against scripts/resolve-release-version.mjs, checks version consistency across the app manifests, invokes docs-updater, reviews the app release workflow and its contract tests, and runs frontend/Maven/packaging/smoke verification. Use when the user asks to release, ship, tag, or cut a version of the main app. Does NOT publish the independently versioned plugin toolchain — use toolchain-release for that.
---

# App Release

Cut a **main application** release. The app version is the Maven `${revision}` property, mirrored in
`.mvn/maven.config`, `frontend/package.json`, `desktop/electron/package.json` plus its lockfile, and
each of the five official plugins' `manifest.json` files.

This skill does **not** touch the plugin toolchain version (`toolchain/sdk-java/pom.xml` /
`@infinia/*`). Use the `toolchain-release` skill for that.

## Step 1 — Validate the release tag

The tag contract is enforced by `scripts/resolve-release-version.mjs`:

```
vX.Y.Z
vX.Y.Z-alpha.N
vX.Y.Z-beta.N
vX.Y.Z-rc.N
```

Resolve and confirm the four derived values (tag, version, appVersion, prerelease) before doing
anything:

```bash
node scripts/resolve-release-version.mjs vX.Y.Z-alpha.1   # prints tag/version/appVersion/prerelease
```

(When run outside Actions it errors on the missing `GITHUB_OUTPUT` file — that is expected; the
important check is that it does **not** throw `Invalid release tag`.) If the tag is invalid, stop and
agree a valid tag with the user.

## Step 2 — Check version consistency

Read the current app version from root `pom.xml` (`<revision>`) and confirm every mirror is
consistent: `.mvn/maven.config`, `frontend/package.json`, `desktop/electron/package.json` and
`desktop/electron/package-lock.json`, plus the `markdown`, `excel`, `email`, `offlinepython`, and
`browser` official manifests. Decide with the user whether the `${revision}` property and mirrors
should be bumped to the release version; do not edit yet. Do not rewrite unrelated version strings
in test fixtures or historical changelog entries.

## Step 3 — Update documentation

Invoke the **`docs-updater`** skill for the range from the last app tag to `HEAD`: CHANGELOG entry,
EN/ZH doc sections mapped to the code changes, and version-number replacement. Confirm
`npm --prefix docs run build` passes if the published site is affected.

## Step 4 — Review the release workflow and its contract tests

Read `.github/workflows/fengyu-release.yml` and confirm the intended tag triggers it (tag push
`v[0-9]+.[0-9]+.[0-9]+` including `-alpha.N`/`-beta.N`/`-rc.N`, or `workflow_dispatch` with a `tag`
input). Run the release-version contract test locally:

```bash
node --test scripts/resolve-release-version.test.mjs
node --test scripts/release-workflow.test.mjs
```

All must pass before any release mutation.

Also confirm the workflow stages, uploads, and assembles every official `.fyp` together with its
`.fyp.sha256` sidecar for Web and both desktop variants. A sidecar is an integrity check, not an
independent authenticity root; do not describe unsigned packages as cryptographically signed.

## Step 5 — Run pre-release verification

Run the focused set the release depends on (do not skip to save time):

```bash
# Backend
./mvnw clean package -f FengYu/pom.xml -DskipTests

# Frontend (audit + build + typecheck + tests)
cd frontend && npm install && npm audit --omit=dev && npm run build && npm run test:unit && npm run typecheck && cd ..

# Desktop shell (audit + unit tests + TypeScript; cross-platform packaging stays in CI)
cd desktop/electron && npm install && npm audit --omit=dev && npm test && npm run build:ts && cd ../..

# End-to-end smoke
scripts/e2e-smoke.sh

# Portable web distribution self-check (used by the release's web job)
# Stage the five freshly-built `.fyp` + `.fyp.sha256` pairs in a temporary plugin directory,
# then pass VERSION, the shaded JAR, that directory, and an output directory explicitly.
scripts/package-web-release.sh VERSION JAR PLUGIN_DIR OUTPUT_DIR
scripts/test-web-release.sh OUTPUT_DIR/Infinia-VERSION-web.zip
```

(Desktop packaging is exercised by the workflow's matrix; you do not need to build all three
platforms locally.)

## Step 6 — Mutate, with explicit confirmation

**Committing, tagging, pushing a branch, or pushing a tag requires explicit user confirmation at
each step.** Do not run these automatically. When the user confirms:

1. Apply the version bump (`<revision>` and every mirror listed in Step 2) and the docs-updater changes; commit with a
   `📝`/`⬆️` conventional message.
2. Create the tag `vX.Y.Z[-{alpha|beta|rc}.N]`.
3. Push the branch and the tag — the tag push triggers `.github/workflows/fengyu-release.yml`, which
   builds runtime JARs, the portable web archives, the unsigned Electron packages (Win/macOS/Linux,
   two variants per platform: with and without a bundled JRE), and publishes the GitHub release named
   "Infinia <version>".

**Never** publish the plugin toolchain (`@infinia/*`, `fengyu-plugin-sdk`) as part of an app release.
