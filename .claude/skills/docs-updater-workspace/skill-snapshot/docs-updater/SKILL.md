---
name: docs-updater
description: Updates version numbers and content across docs/, README.md, and CHANGELOG.md to match current pom.xml versions. Also analyzes git history since the last release to update feature docs, API docs, architecture docs, and other documentation based on what changed in the code. Use this skill whenever the user mentions updating docs, bumping versions, syncing documentation, or after running /release.
---

# Docs Updater

Updates documentation after a version bump — both version number sync and content updates driven by code changes in git history.

## Project Context

SwissKitJ has two modules with **standalone POMs** (no parent inheritance):

| POM | Version | Purpose |
|-----|---------|---------|
| `SwissKit/pom.xml` | App version (e.g. `3.0.0-beta.1`) | Main JavaFX app — what users see |
| `SwissKitJ-Api/pom.xml` | API version (e.g. `3.0.0`) | Shared plugin interface |
| `pom.xml` (root) | Same as API version | Aggregator POM only |

The **app version** from `SwissKit/pom.xml` is what docs and README should reflect. Never use the root `pom.xml` version for docs — that's the API module version, not the app version.

## Phase 1: Extract Versions

### 1a. Get the current app version

Read `SwissKit/pom.xml` and extract the first `<version>` value. Also note the API version from `SwissKitJ-Api/pom.xml` — only change API version references if the API module itself was bumped.

### 1b. Find the previous version

```bash
git tag --sort=-v:refname | grep -E '^v?[0-9]+\.[0-9]+\.[0-9]+' | head -1
```

If no tags exist, check the latest version header in `CHANGELOG.md`.

## Phase 2: Analyze Code Changes

### 2a. Collect commits since last release

```bash
git log <previous-tag>..HEAD --oneline --no-decorate
```

If the range is empty, skip content updates and only sync version numbers.

### 2b. Categorize commits and map to docs

Categorize by conventional commit prefix and map each category to the docs that need updating:

| Prefix | What changed | Docs to update |
|--------|-------------|----------------|
| `feat:` / `✨` | New feature or tool | `docs/features.md` — add to appropriate category section; `CHANGELOG.md` — add under New Features |
| `fix:` / `🐛` | Bug fix | `CHANGELOG.md` — add under Fixes |
| `refactor:` / `♻️` | Internal restructure | `docs/architecture.md` if module/startup/plugin loading changed; `docs/development.md` if build/conventions changed; `CHANGELOG.md` — add under Changes |
| `docs:` / `📝` | Documentation only | Already handled by the commit itself; no action needed |
| `deps:` / `⬆️` | Dependency upgrade | `CHANGELOG.md` only |

### 2c. Examine diffs for content details

For feat/refactor commits, read the actual diff to understand what was added:

```bash
git diff <previous-tag>..HEAD -- SwissKitJ-Api/src/main/java/ SwissKit/src/main/java/
```

Pay attention to:
- New classes in `SwissKitJ-Api/` → may need `docs/api.md` updates
- New registrations in `BuiltinToolRegistrar.java` → new built-in tool → update `docs/features.md`
- Changes to `SwissKitJPlugin.java` → plugin interface changes → update `docs/api.md` and `docs/development.md`
- New UI components → update `docs/development.md` UI Components section
- Startup sequence changes in `SwissKitJApp.java` → update `docs/architecture.md`

## Phase 3: Update CHANGELOG

Add a new version section at the top of `CHANGELOG.md` (after the intro header block). Follow the existing format — see the file for the exact pattern. Dedup related commits into single bullet points. Then sync the same entry to `docs/changelog.md` and `docs/zh/changelog.md` (translate to Chinese for the zh version).

## Phase 4: Update Doc Content

For each doc file identified in Phase 2b/2c:

- **`docs/features.md`**: Add new built-in tools under the appropriate category (`DEV`, `TEXT`, `IMAGE`, `NET`, `OTHER`). Follow the existing pattern: `### Tool Name (\`CATEGORY\`)` header + bullet list of capabilities. If a feature isn't a standalone tool (e.g. "plugin background execution"), add it under a relevant System Features subsection.
- **`docs/api.md`**: Add new interfaces, methods, enums, or entities. Follow the existing table + code-block format.
- **`docs/architecture.md`**: Update startup sequence steps, module descriptions, or subsystem sections if relevant code changed.
- **`docs/development.md`**: Update if build steps, conventions, UI component usage, or logging patterns changed.
- **`docs/getting-started.md`**: Update if prerequisites, setup steps, or run commands changed.

When adding content, follow the exact formatting patterns already in each file. Don't invent new section styles.

## Phase 5: Version Number Replacement

### 5a. Find stale references

Search for the old version string across the docs tree:

```bash
grep -r '<old-version>' docs/ README.md CHANGELOG.md --include='*.md' -l
```

Skip `docs/superpowers/` — those are date-keyed planning artifacts, not version docs.

### 5b. Replace with exact string matching

Use exact string replacement (never regex) to swap old version → new version. Common patterns:

- Badge URLs: `version-<old>-blue` → `version-<new>-blue`
- Download links: `/releases/tag/v<old>` → `/releases/tag/v<new>`
- JAR filenames: `SwissKitJ-<old>.jar` → `SwissKitJ-<new>.jar`
- Inline version: `**<old>**` → `**<new>**`

Be careful not to change the API version (`3.0.0`) which appears in Maven dependency examples in `docs/development.md` — those reference `SwissKitJ-Api` and should stay unchanged.

## Phase 6: Validate

After all changes, confirm no stale version remains:

```bash
grep -r '<old-version>' docs/ README.md 2>/dev/null
```

Expected: empty output (no matches, except in historical CHANGELOG entries for past releases).

Then verify the new version appears where expected:

```bash
grep -r '<new-version>' docs/ README.md CHANGELOG.md 2>/dev/null | head -20
```

## Summary Output

When done, report a concise summary:
- Old version → New version
- Files changed (with what was updated in each)
- Any files that were skipped and why
