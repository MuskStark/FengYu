---
name: docs-updater
description: Update README.md, CHANGELOG.md, docs/en/, and docs/zh/ after a code or release change. Diff-driven — maps concrete git changes to the documentation sections they affect, keeps English and Chinese structurally aligned, and treats the app and plugin-tooling versions as separate lines. Use whenever the user asks to update docs, sync documentation to code, or bump/sync versions after a release.
---

# Docs Updater

Sync repository documentation to concrete code and release changes. **Diff-driven, not editorial:**
every edit traces to a specific commit or release mutation. Do not review docs for general accuracy
or "improve" prose without a corresponding change.

## Scope

In scope: `README.md`, `CHANGELOG.md`, `docs/en/`, `docs/zh/`.

Out of scope (never touch here):

- `docs/superpowers/` — date-keyed planning artifacts.
- Generated VitePress output (`docs/.vitepress/dist/`).
- Application source and build output.

## Two version lines — do not conflate

- **App version** — Maven `${revision}` (root `pom.xml`), mirrored in `frontend/package.json`,
  `desktop/electron/package.json`, and each official plugin's `manifest.json`.
- **Plugin toolchain version** — independent; `toolchain/sdk-java/pom.xml` and the three
  `@infinia/*` `package.json` files (`toolchain/cli/`, `toolchain/sdk-ts/`, `toolchain/ui/`).

Always read the literal from its source file before replacing it anywhere. An app release does not
touch the toolchain version, and vice-versa.

## Step 1 — Determine the comparison range

Pick the range from the release type:

- **App release** — compare from the latest app tag (`git tag --sort=-v:refname | grep -E '^v[0-9]'`)
  to `HEAD`.
- **Plugin-tooling release** — compare from the latest `plugin-tooling-v*` tag to `HEAD`.
- **Non-release doc sync** — compare from the last release tag (app or tooling, whichever is
  relevant to the changed files) to `HEAD`.

```bash
git log <previous-tag>..HEAD --oneline --no-decorate
git diff <previous-tag>..HEAD --stat
```

If the range is empty, skip content updates and only sync version numbers (Step 4).

## Step 2 — Map changes to doc sections

For each materially changed file, decide the **specific** doc section it maps to. Only edit a doc
section when a concrete change maps to it. Examples:

| Changed source | Affected doc |
|---|---|
| New official plugin module under `OfficialPlugins/` | `docs/{en,zh}/features.md` (or the plugin overview), `README.md` Features list, and a new `docs/{en,zh}/plugins/official-<name>.md` |
| `manifest.json` schema change in `toolchain/spec/` | `docs/{en,zh}/plugins/manifest.md` |
| New REST/SSE controller method | `docs/{en,zh}/reference/rest-api.md` / `sse-events.md` |
| Headless boot / setup-wizard change in `FengYu/` | `docs/{en,zh}/architecture/backend.md`, `docs/{en,zh}/guide/database.md` |
| `fengyu` CLI subcommand change in `toolchain/cli/` | `docs/{en,zh}/plugins/sdk-cli.md` |

If a changed file maps to no doc section, make no doc edit for it. Do not invent new patterns — copy
the formatting of the nearest existing entry.

## Step 3 — Update CHANGELOG and keep EN/ZH aligned

- Add a new section at the top of `CHANGELOG.md` (after the intro header) following the existing
  format. Dedup related commits into single bullets.
- Categorize by prefix: `feat`/`✨` → New Features; `fix`/`🐛` → Fixes; `refactor`/`♻️`,
  `deps`/`⬆️` → Changes; `docs`/`📝` → skip (already documentation).
- Keep `docs/en/` and `docs/zh/` **structurally aligned**: the same headings, the same section order,
  the same facts. Translate to 简体中文 for the `zh` tree; do not let one language drift ahead of the
  other. Preserve all historical changelog entries — never rewrite past releases.

## Step 4 — Replace version numbers (exact string matching)

Find stale references and swap with **exact string** replacement (never regex):

```bash
grep -r '<old-version>' docs/ README.md CHANGELOG.md --include='*.md' -l
```

Watch for: badge URLs, `/releases/tag/v<old>` links, JAR/`.fyp` filenames, inline bold versions. Be
careful not to change the **other** version line (e.g. do not bump the toolchain version when
syncing an app release). Skip matches inside historical CHANGELOG entries for past releases.

## Step 5 — Validate

- **Stale-reference search:** `grep -r '<old-version>' docs/ README.md 2>/dev/null` → expect empty
  (except historical changelog entries).
- **New-version presence:** `grep -r '<new-version>' docs/ README.md CHANGELOG.md` → expect it where
  intended.
- **Link/path checks where practical:** spot-check any changed internal links and file paths resolve.
- **Docs build (when the change affects the published site):** from the repo root,

  ```bash
  npm run docs:build      # VitePress; builds docs/ → docs/.vitepress/dist/
  ```

  Skip this for changes confined to `CHANGELOG.md` or `docs/superpowers/`.

## Output

Report concisely: old → new version (per line), files changed and what changed in each, and anything
skipped with the reason.
