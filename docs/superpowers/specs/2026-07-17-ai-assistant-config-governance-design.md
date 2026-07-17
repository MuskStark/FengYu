# AI Assistant Configuration Governance Design

**Date:** 2026-07-17
**Status:** Approved design, pending implementation

## Objective

Replace the repository's duplicated and outdated AI assistant configuration with a small,
cross-platform configuration that reflects the current FengYu 4.0.0 headless architecture.
The result must support both Codex-compatible assistants and Claude Code without maintaining
two copies of the same project guidance.

This change is limited to files and directories that assistants automatically load or register.
Historical plans, generated working artifacts, product documentation, application source, and
ordinary build scripts are outside the deletion scope.

## Current Problems

The current assistant configuration has several sources of drift:

- `AGENTS.md` and `CLAUDE.md` duplicate hundreds of lines and mix the current headless system
  with historical JavaFX architecture.
- `.agents/skills/` and `.claude/skills/` contain overlapping but different copies of skills,
  references, and templates.
- `.claude-plugin/` publishes a legacy JavaFX/SPI plugin-development model that no longer matches
  the `.fyp`, iframe, and JSON-RPC Worker architecture.
- Claude-specific agents, commands, marketplace metadata, standards snapshots, and synchronization
  conventions add maintenance surfaces without a current project requirement.
- The existing release and documentation skills refer to obsolete module names, version sources,
  and release workflows.

## Selected Approach

Use one canonical configuration with thin Claude Code adapters.

The alternatives were rejected for the following reasons:

- Git symlinks would reduce file count but are unreliable for contributors on Windows and in tools
  that do not preserve symlink semantics.
- Maintaining complete Codex and Claude copies would recreate the duplication and drift this change
  is intended to remove.

## Target Structure

```text
AGENTS.md
CLAUDE.md
.agents/skills/
  fengyu-plugin-dev/SKILL.md
  docs-updater/SKILL.md
  app-release/SKILL.md
  plugin-tooling-release/SKILL.md
.claude/skills/
  fengyu-plugin-dev/SKILL.md
  docs-updater/SKILL.md
  app-release/SKILL.md
  plugin-tooling-release/SKILL.md
```

These ten files are the complete intended assistant-behavior surface.

`AGENTS.md` is the canonical repository guidance. Each skill in `.agents/skills/` is the canonical
workflow for its domain. `CLAUDE.md` and the files under `.claude/skills/` are short adapters that
direct Claude Code to the matching canonical file; they do not repeat project rules or workflow
steps.

## Deletion Scope

Delete the existing assistant configuration before recreating the target structure:

- `AGENTS.md`
- `CLAUDE.md`
- `.agents/`
- `.claude/`
- `.claude-plugin/`

The deletion removes legacy reviewer agents, commands, plugin marketplace metadata, standards
snapshots, JavaFX templates, SPI/ServiceLoader instructions, and duplicated skill references.

Do not delete or rewrite these categories solely because they were produced or used by an AI tool:

- `docs/superpowers/`
- `.superpowers/`
- `final-branch-review`
- product documentation under `docs/en/` and `docs/zh/`
- application source and build output
- ordinary scripts, including `scripts/sync-plugin-standards.sh`

`scripts/sync-plugin-standards.sh` will have no consumer after `.claude-plugin/` is removed. It is
an out-of-scope historical script and must not be referenced by the rebuilt configuration.

## Canonical Repository Guidance

The rebuilt `AGENTS.md` will contain only information needed across normal development tasks:

- FengYu 4.0.0 is a headless Spring Boot backend, Vue 3/Vuetify SPA, and Tauri 2 desktop shell.
- The Maven reactor includes `FengYu-Api`, `FengYu-Plugin-Sdk`, `OfficialPlugins`, and `FengYu`.
- Plugin packages use `.fyp`, `manifest.json`, `fengyu.plugin.json`, iframe micro-frontends,
  `postMessage`, and isolated JSON-RPC Worker processes.
- The current source, schemas, package manifests, scripts, workflows, and focused module docs are
  authoritative when a detail conflicts with prose guidance.
- Common build, test, development, and smoke-test commands must use the repository wrappers and
  package scripts already present in the project.
- Changes must preserve user work, avoid unrelated rewrites, use focused verification, and follow
  the repository's conventional commit convention when a commit is requested.
- Historical JavaFX, `FengYuPluginV2`, in-process plugin, SPI, and shared-host-classpath assumptions
  do not describe the running 4.0.0 system.

Frequently changing inventories, exact versions, and long endpoint lists will not be copied into
`AGENTS.md`. Assistants must inspect their authoritative files when those details matter.

## Skill Responsibilities

### `fengyu-plugin-dev`

This skill handles both repository-owned official plugins and third-party plugin development. It
must inspect the target plugin and route the workflow according to whether it is UI-only or uses a
Java Worker.

Its authoritative inputs are:

- `plugin-spec/manifest.schema.json`
- `fengyu.plugin.json` in the target plugin
- `FengYu-Plugin-Sdk/`
- `plugin-sdk/typescript/`
- `plugin-ui/vue/`
- `plugin-cli/`
- current plugin documentation under `docs/en/plugins/` and `docs/zh/plugins/`

It covers scaffold, develop, validate, test, build, package, and install workflows through the
current plugin CLI. It enforces iframe isolation, the browser SDK bridge, Worker JSON-RPC contracts,
manifest permissions, packaging boundaries, and focused UI/Worker verification.

It must not generate or recommend JavaFX views, `FengYuPluginV2`, Java ServiceLoader SPI files,
in-process plugin beans, or reliance on host-provided Worker dependencies.

### `docs-updater`

This skill updates `README.md`, `CHANGELOG.md`, `docs/en/`, and `docs/zh/` from concrete source and
git changes. It determines the relevant comparison range from the release type and existing tags,
then maps implementation changes to the affected documentation sections.

It keeps English and Chinese documentation structurally aligned, preserves historical changelog
entries, and treats application and plugin-tooling versions as separate version lines. It does not
perform broad editorial rewrites without a corresponding code or release change. It skips
`docs/superpowers/` and generated VitePress output.

Validation includes stale-reference searches, link/path checks where practical, and
`npm run docs:build` for documentation changes that affect the published site.

### `app-release`

This skill handles only main application releases. Accepted release tags follow the existing
`vX.Y.Z`, `vX.Y.Z-alpha.N`, `vX.Y.Z-beta.N`, and `vX.Y.Z-rc.N` contract enforced by
`scripts/resolve-release-version.mjs`.

The skill verifies version consistency where source manifests require a base application version,
invokes `docs-updater`, reviews the application release workflow and its contract tests, and runs
the appropriate frontend, Maven, packaging, and smoke verification before release mutation.

Committing, creating a tag, pushing a branch, or pushing a tag requires explicit user confirmation.
The skill must not publish the independently versioned plugin toolchain.

### `plugin-tooling-release`

This skill handles the independently versioned plugin toolchain:

- `fan.summer.fengyu.sdk:fengyu-plugin-sdk`
- `@infinia/plugin-sdk`
- `@infinia/plugin-ui`
- `@infinia/plugin-cli`

It synchronizes their intended release version and dependent ranges, validates lockfiles and package
contents, builds official plugins through the CLI, and exercises the local toolchain smoke path. Its
release trigger is `plugin-tooling-vX.Y.Z` or the existing workflow dispatch input.

The skill treats `.github/workflows/plugin-tooling-release.yml` and its version resolver as the
release contract. It requires explicit user confirmation before commits, tags, pushes, workflow
dispatches, or registry publication. It must not change the main application version merely to
release the toolchain.

## Claude Code Adapters

`CLAUDE.md` tells Claude Code to read and follow `AGENTS.md` as the repository instruction source.
Each `.claude/skills/<name>/SKILL.md` file has valid skill metadata and instructs Claude Code to read
and execute `.agents/skills/<name>/SKILL.md` completely.

The adapters contain no copied architecture, command sequences, templates, or release rules. This
keeps them portable without introducing symlinks.

## Governance Rules

- Keep assistant configuration in English for consistent parsing across supported tools.
- Do not add custom agents, command copies, assistant plugins, marketplace metadata, or generated
  standards snapshots without a new, explicit requirement.
- Extend an existing skill when work shares the same lifecycle. Add a new skill only for a genuinely
  independent workflow, as with application and plugin-tooling releases.
- Prefer links to authoritative repository files over copied schemas, inventories, or templates.
- Update `AGENTS.md` when stable architecture or repository-wide development rules change.
- Update the relevant canonical skill when one of its workflow contracts changes.
- Never make `.claude/` a second source of truth.

## Verification

Implementation verification will confirm:

1. Only the ten target behavior files remain under the identified assistant configuration paths.
2. Every skill has valid frontmatter, a unique name, and resolvable repository-relative references.
3. Claude adapters point to the correct canonical skill and do not duplicate its body.
4. The new guidance contains no affirmative legacy JavaFX, `FengYuPluginV2`, SPI, SwissKitJ, or
   ZhiFlow instructions. Explicit legacy prohibitions are allowed.
5. Plugin commands match the current CLI, manifest schema, and official plugin layouts.
6. Documentation and release commands match the current package scripts, version resolvers, and
   GitHub Actions workflows.
7. No rebuilt file references `.claude-plugin/` or `scripts/sync-plugin-standards.sh`.
8. `git diff --check` passes.

No new validation or synchronization script will be added. The configuration is intentionally small
enough to audit directly with repository searches and focused command checks.
