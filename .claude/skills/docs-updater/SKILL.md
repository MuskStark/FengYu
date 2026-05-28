---
name: docs-updater
description: Updates version numbers and content across docs/, README.md, and CHANGELOG.md to match current pom.xml version
---

# Docs Updater

Updates version references across the codebase after a version bump.

## Usage
User invokes via `/docs-updater`

## Steps

1. Read the current version from `pom.xml` at the project root:
   ```bash
   grep '<version>' pom.xml | head -1 | sed 's/.*<version>//' | sed 's/<\/version>.*//'
   ```

2. Update `docs/` directory — search for any `.md` files containing old version strings:
   ```bash
   grep -rl "<old-version>" docs/ 2>/dev/null | head -20
   ```
   Replace all occurrences with the new version.

3. Update `README.md` version badge if present.

4. Verify `CHANGELOG.md` already has an entry for the new version (created by `/release`).

5. Confirm all changes before committing.