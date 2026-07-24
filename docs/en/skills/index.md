---
title: Skills
description: Runtime skills are domain guidance the assistant loads on demand (Codex-style progressive disclosure). Managed like plugins — packaged as .fys archives with a full install/uninstall/marketplace lifecycle.
lang: en
---

# Skills

A **skill** is a unit of domain guidance the assistant loads on demand. Skills are FengYu's
third extension surface, alongside **plugins** (callable tools in isolated `.fyp` packages)
and **AI tools** (in-process `@Tool` beans). The three are intentionally independent: plugins
contribute *capabilities*, tools contribute *functions the model can call*, and skills
contribute *contextual guidance*.

> **Where to manage skills:** the **Plugins page** (`/plugins`) is the single management surface
> for both extension types. A `Plugins | Skills` tab pair at the top switches the view; installed
> items appear in a fast-row near the top, everything else in a card grid below. There is no
> separate Skills page. A single **Upload** button accepts both `.fyp` and `.fys` archives and
> routes each to the right installer by extension. This mirrors how Codex groups every extension
> kind under one Extensions view.

## How they work (progressive disclosure)

Skills use **Codex-style progressive disclosure** so large guidance documents never bloat the
token budget:

1. On each chat request, the host appends a compact **Available Skills** catalog to the system
   prompt — one line per enabled skill (`id` + `description` only).
2. When the user's request matches a skill, the model calls the built-in **`skill` tool** with
   that skill's id.
3. The tool returns the skill's **full body** (its markdown guidance), which the model then
   follows.

The per-request cost of *N* skills is roughly *N* lines, regardless of how long each body is.
A skill body only costs tokens when it is actually loaded.

## Managed like plugins

A skill has the **same lifecycle as a plugin**:

- Packaged as a **`.fys` archive** (zip: `manifest.json` + `SKILL.md`).
- Installed under `~/.fengyu/skills/<id>/` — a filesystem peer of `~/.fengyu/plugins/<id>/`.
- Enabled/disabled via a **`.disabled` marker file** (so the state survives reinstall and stays
  out of the DB).
- Browseable and installable from a **remote catalog** through the skill marketplace.
- Bundled official skills can be **seeded on boot** by `OfficialSkillSeeder`.

| Source | Location | Lifecycle |
| --- | --- | --- |
| **Built-in** | `classpath:/skills/<id>/SKILL.md` (inside the app JAR) | Ships with every release. **Cannot be uninstalled or disabled** (install an overriding skill of the same id to customize). |
| **Installed** | `~/.fengyu/skills/<id>/` (from a `.fys` package) | Full install / uninstall / enable / disable, exactly like a plugin. |

An installed skill with the same id as a built-in one **overrides** it — so you can tailor
shipped guidance without forking the JAR. The installed source wins.

## The `.fys` package format

A `.fys` archive is a zip with two required files at its root:

```
manifest.json   # SkillManifest — id, name, description, version, author, icon, homepage, official
SKILL.md        # frontmatter (name, description) + markdown body
assets/...      # optional extra resources
```

`manifest.json` is the authoritative metadata for an installed skill; `SKILL.md` provides the
guidance body. (For built-in classpath skills there is no manifest — both metadata and body
come from the `SKILL.md` frontmatter.)

```json
{
  "schemaVersion": 1,
  "id": "my-team-conventions",
  "name": "Team Conventions",
  "description": "Coding style and review checklist. Load when writing or reviewing code in this repo.",
  "version": "1.0.0",
  "author": "me",
  "icon": "book-outline",
  "homepage": "https://github.com/me/conventions",
  "official": false
}
```

The **id** must match `[a-z0-9]+(?:[.-][a-z0-9]+)+` (same rule plugin ids use). `official: true`
requires the id to start with `fan.summer.`. Versions are semver and drive the marketplace's
"update available" comparison.

## Enable / disable

Enable state is a **filesystem marker** (`~/.fengyu/skills/<id>/.disabled`), exactly like a
plugin. Absent the file, an installed skill is enabled. Toggle it from the **Skills** page
(`/skills`) or via `PATCH /api/skills/{id}/enabled`. Built-in skills have no install directory
and are always enabled.

## REST API

The skill lifecycle mirrors `/api/plugin-market`, exposed under `/api/skills`:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/skills` | List every discovered skill (builtin + installed) — no bodies. |
| `GET` | `/api/skills/{id}` | Full detail for one skill, including its markdown body. |
| `GET` | `/api/skills/market` | Marketplace merged view (`MarketplaceSkill[]`). |
| `POST` | `/api/skills/upload` | Install a `.fys` archive (multipart `file`). → 201 |
| `POST` | `/api/skills/upload-native` | Install a `.fys` by absolute path (desktop). → 201 |
| `POST` | `/api/skills/{id}/install` | Install from the configured catalog. → 201 |
| `POST` | `/api/skills/{id}/update` | Update from the catalog (reuses install). |
| `PATCH` | `/api/skills/{id}/enabled` | Flip the `.disabled` marker. Body `{"enabled": bool}`. |
| `DELETE` | `/api/skills/{id}` | Uninstall. → 204 (409 for built-in skills) |

All endpoints require the `X-FengYu-Token` header. Built-in skills return **409 Conflict** on
uninstall or disable attempts.

## Configuration

| Key | Default | Purpose |
| --- | --- | --- |
| `fengyu.skills.directory` | `${user.home}/.fengyu/skills` | Where `.fys` packages are installed. |
| `fengyu.skills.catalog-url` | `""` (none) | Remote skill marketplace catalog JSON. Blank → local installed only. |
| `fengyu.skills.official-directory` | `${user.dir}/OfficialSkills/target/packages` | Scanned by `OfficialSkillSeeder` on boot. |

## Authoring tips

- Put the skill's **trigger conditions** in `description` — that is the only line the model
  sees before deciding to load the body. Name concrete tokens.
- Open the body with "load authoritative inputs BEFORE acting" and "if this disagrees with
  the repo, the repo wins" — skills are guidance, not authority.
- One concern per skill. If a skill grows past a few hundred lines, consider splitting it.

## Source of truth

When this page disagrees with the repository, **the repository wins**:

- Runtime contract: `FengYu/src/main/java/fan/summer/fengyu/ai/skill/`
- Built-in skills: `FengYu/src/main/resources/skills/`
- REST surface: `FengYu/src/main/java/fan/summer/fengyu/web/controller/SkillController.java`
