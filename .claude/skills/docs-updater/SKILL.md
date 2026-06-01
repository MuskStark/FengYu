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

## Core Principle: Diff-Driven, Not Doc Review

Content updates are driven **exclusively by what changed in the code**. Do not review docs for general accuracy or completeness. Do not rewrite, reorganize, or "improve" existing content. If the diff shows a new built-in tool was added, add that tool to `features.md`. If the diff shows no change to the build system, don't touch `development.md`'s build section. Every doc edit must trace back to a specific commit in the git log.

## Phase 2: Analyze Code Changes

### 2a. Collect commits since last release

```bash
git log <previous-tag>..HEAD --oneline --no-decorate
```

If the range is empty, skip content updates and only sync version numbers.

### 2b. Examine diffs to determine what actually changed

Don't rely on commit messages alone — read the diff to see exactly which files changed:

```bash
git diff <previous-tag>..HEAD --stat
```

Then drill into relevant paths:

```bash
git diff <previous-tag>..HEAD -- SwissKitJ-Api/src/main/java/ SwissKit/src/main/java/
```

### 2c. Map each changed file to the doc section it affects

Only create doc updates when a specific changed file maps to a specific doc section:

| Changed file(s) | Doc to update | What to add/change |
|----------------|---------------|--------------------|
| New class in `buildintool/` registered in `BuiltinToolRegistrar.java` | `docs/features.md` | Add the new tool under its category section, following the existing `### Tool Name (\`CATEGORY\`)` format |
| `SwissKitJPlugin.java` interface changes (new methods, changed signatures) | `docs/api.md` | Add/update the method in the interface table |
| `SwissKitJApp.java` startup sequence changes | `docs/architecture.md` | Update the numbered startup steps |
| New files in `SwissKitJ-Api/.../component/` | `docs/development.md` | Add usage example in UI Components section |
| `SwissKitJ-Api/.../theme/` changes | `docs/development.md` | Update theming section |

If a changed file doesn't map to any doc section, don't create a doc update for it. If no changed files map to a given doc file, don't touch that doc file at all (beyond version number replacement in Phase 5).

### 2d. Categorize commits for CHANGELOG

| Prefix | CHANGELOG section |
|--------|-------------------|
| `feat:` / `✨` | New Features |
| `fix:` / `🐛` | Fixes |
| `refactor:` / `♻️` | Changes |
| `deps:` / `⬆️` | Changes |
| `docs:` / `📝` | Skip (already documentation) |

## Phase 3: Update CHANGELOG

Add a new version section at the top of `CHANGELOG.md` (after the intro header block). Follow the existing format — see the file for the exact pattern. Dedup related commits into single bullet points. Then sync the same entry to `docs/changelog.md` and `docs/zh/changelog.md` (translate to Chinese for the zh version).

## Phase 4: Update Doc Content (diff-driven only)

For each mapping identified in Phase 2c, surgically add or update **only the specific subsection** corresponding to the code change. Do not modify other sections of the same file. Do not rewrite existing descriptions. Do not reorder content. Add new content in the same position and format as existing similar entries.

When adding content, copy the formatting pattern from the nearest existing entry in that file. Don't invent new patterns or section styles.

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
