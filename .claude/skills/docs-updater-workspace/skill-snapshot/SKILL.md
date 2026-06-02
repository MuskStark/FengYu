---
name: docs-updater
description: Updates version numbers and content across docs/, README.md, and CHANGELOG.md to match current pom.xml versions
---

# Docs Updater

Updates version references across the codebase after a version bump in `pom.xml`.

## When to Use

- After running `/release` or manually bumping versions in `pom.xml`
- When docs still reference stale versions
- User invokes via `/docs-updater`

## Version Sources

The project has two distinct versions:

| POM | Version field | Meaning |
|-----|--------------|---------|
| `SwissKitJ-Api/pom.xml` | `<version>` | API module version (shared interface) |
| `SwissKit/pom.xml` | `<version>` | Main app version (what users see) |
| `pom.xml` (root) | `<version>` | Aggregator — mirrors API version |

The **app version** (from `SwissKit/pom.xml`) is what docs and README should reflect. Extract it with:

```bash
grep -m1 '<version>' SwissKit/pom.xml | sed 's/.*<version>//;s/<\/version>.*//'
```

Note: `grep -m1` only works on BSD grep (macOS). On Linux use `grep -m 1`.

## Steps

### 1. Extract current app version

Read `SwissKit/pom.xml` and extract the `<version>` value (first match only). Also note the API version from `SwissKitJ-Api/pom.xml` for reference.

### 2. Find all files referencing old versions

Search across the entire docs tree for version strings:

```bash
grep -rE '[0-9]+\.[0-9]+\.[0-9]+(-[a-z]+\.[0-9]+)?' docs/ --include='*.md' -l
```

Ignore files under `docs/superpowers/` — those are planning/spec artifacts keyed by date, not version docs.

Also check:
- `README.md` (root)
- `CHANGELOG.md` (root)
- `docs/changelog.md`

### 3. Replace version references

For each file found, replace all occurrences of the old version with the new one. Use exact string replacement — never regex-replace version numbers blindly.

Common patterns to update:
- Badge URLs (`/v3.0.0-beta.1-`)
- Download links
- `java -jar SwissKit/target/SwissKitJ-<version>.jar`
- Inline version mentions

### 4. Verify CHANGELOG entries

Check that `CHANGELOG.md` and `docs/changelog.md` both have an entry for the new version. If the `/release` workflow created the root `CHANGELOG.md` entry, sync it to `docs/changelog.md`.

### 5. Update README badges and links

If `README.md` contains version badges (shields.io or similar), update the version portion. Also update any direct download links pointing to GitHub Releases with the tagged version.

### 6. Validate

After all replacements, run a final grep to confirm no stale version remains:

```bash
grep -r '<old-version>' docs/ README.md 2>/dev/null
```

Expected output: empty (no matches).

Then summarize: which files were changed, what the old version was, and what the new version is.
