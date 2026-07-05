# Installing the `swisskit-plugin-dev` skill

This skill follows the **Agent Skills open standard** — a `SKILL.md` with `name` +
`description` YAML frontmatter and a Markdown body. The format is **identical across agents**,
so the *same files* work in **ZCode** and **Claude Code** (and any other agent implementing
the standard, e.g. GitHub Copilot agent mode). It's also portable: all references use
absolute URLs and the must-know facts are inlined, so it works offline in any location.

You only pick the **directory** that matches your agent. The SKILL.md contents never change.

| Agent | Project-level (committed, recommended) | User-level (all your projects) |
|---|---|---|
| **ZCode** | `<project>/.agents/skills/` | `~/.agents/skills/` |
| **Claude Code** | `<project>/.claude/skills/` | `~/.claude/skills/` |
| (override / pin) | `<project>/.zcode/skills/` *(ZCode)* | — |

The canonical copy lives in the main repo:
[`MuskStark/SwissKitJ/.agents/skills/swisskit-plugin-dev/`](https://github.com/MuskStark/SwissKitJ/tree/main/.agents/skills/swisskit-plugin-dev).
Re-sync from there when the API or design spec changes.

> **Multi-agent repo tip:** if your plugin repo is used by both ZCode and Claude Code
> contributors, just copy the skill into **both** directories — `.agents/skills/` *and*
> `.claude/skills/`. They're the same files; symlink or script it so they stay in sync.

---

## Option A — In a plugin project (recommended for the official plugin repo & any plugin repo)

Committed to the repo so every contributor gets it, auto-discovered by the agent when they
open the project.

### ZCode
```bash
# from the root of your plugin project (e.g. the official SwissKiJ-Plugin repo)
mkdir -p .agents/skills
svn export https://github.com/MuskStark/SwissKitJ/trunk/.agents/skills/swisskit-plugin-dev \
  .agents/skills/swisskit-plugin-dev
git add .agents/skills/swisskit-plugin-dev
git commit -m "chore: add swisskit-plugin-dev skill"
```

### Claude Code
```bash
mkdir -p .claude/skills
svn export https://github.com/MuskStark/SwissKitJ/trunk/.agents/skills/swisskit-plugin-dev \
  .claude/skills/swisskit-plugin-dev
git add .claude/skills/swisskit-plugin-dev
git commit -m "chore: add swisskit-plugin-dev skill"
```

### Both (multi-agent repo) — keep them in sync
```bash
mkdir -p .agents/skills .claude/skills
svn export https://github.com/MuskStark/SwissKitJ/trunk/.agents/skills/swisskit-plugin-dev \
  .agents/skills/swisskit-plugin-dev
cp -R .agents/skills/swisskit-plugin-dev .claude/skills/swisskit-plugin-dev
git add .agents/skills/swisskit-plugin-dev .claude/skills/swisskit-plugin-dev
git commit -m "chore: add swisskit-plugin-dev skill (ZCode + Claude Code)"
```

> No `svn`? Use `git sparse-checkout`, [`degit`](https://github.com/Rich-Harris/degit), or
> download the [tarball](https://github.com/MuskStark/SwissKitJ/archive/refs/heads/main.zip)
> and extract just `.agents/skills/swisskit-plugin-dev/`.

---

## Option B — User-level (for third-party developers, all projects)

Install once into your home directory; the skill is available in **every** project you open.

### ZCode
```bash
mkdir -p ~/.agents/skills
svn export https://github.com/MuskStark/SwissKitJ/trunk/.agents/skills/swisskit-plugin-dev \
  ~/.agents/skills/swisskit-plugin-dev
```

### Claude Code
```bash
mkdir -p ~/.claude/skills
svn export https://github.com/MuskStark/SwissKitJ/trunk/.agents/skills/swisskit-plugin-dev \
  ~/.claude/skills/swisskit-plugin-dev
```

### Both
```bash
mkdir -p ~/.agents/skills ~/.claude/skills
svn export https://github.com/MuskStark/SwissKitJ/trunk/.agents/skills/swisskit-plugin-dev \
  ~/.agents/skills/swisskit-plugin-dev
cp -R ~/.agents/skills/swisskit-plugin-dev ~/.claude/skills/swisskit-plugin-dev
```

Verify (either agent):
```bash
ls ~/.agents/skills/swisskit-plugin-dev/SKILL.md     # ZCode
ls ~/.claude/skills/swisskit-plugin-dev/SKILL.md     # Claude Code
```

---

## Option C — One-off / no install (just point the AI at it)

Because the skill uses only absolute URLs and inlines its facts, you can drop the folder
contents into a chat, or link the agent to the GitHub copy, and it can follow it with no
install:

> `https://github.com/MuskStark/SwissKitJ/tree/main/.agents/skills/swisskit-plugin-dev`

This works in both ZCode (e.g. via `/swisskit-plugin-dev`) and Claude Code (e.g. `@skill` or
by pasting the SKILL.md into the conversation).

---

## Triggering

Once installed, the skill auto-triggers on plugin-authoring intent in both agents. Examples
that fire it: *"scaffold a SwissKitJ plugin"*, *"fix my plugin that won't load"*, *"add an AI
tool to my plugin"*, *"implement SwissKitJPlugin"*, mentions of `.swisskit/plugin` or the
plugin SPI.

- **ZCode** — auto-discovers; force-load with `/swisskit-plugin-dev <request>`.
- **Claude Code** — auto-discovers from the directories above; invoke explicitly by asking
  for the skill or pasting its contents.

---

## Updating

The skill targets **API 3.2.0** and the current `docs/ui-design/` spec. When the API or spec
ships breaking changes, re-run the install command for your agent(s) — it overwrites in place.
The `name: swisskit-plugin-dev` and directory name stay stable across updates, so there's no
migration.

---

## Skill discovery order (for the curious)

**ZCode** (highest priority first):
1. `<project>/.zcode/skills/<name>/` — project override
2. `<project>/.agents/skills/<name>/` — project-level *(recommended)*
3. `~/.zcode/skills/<name>/` — user override
4. `~/.agents/skills/<name>/` — user-level *(recommended for personal)*

**Claude Code** (watches both for file changes):
1. `<project>/.claude/skills/<name>/` — project-level *(recommended)*
2. `~/.claude/skills/<name>/` — user-level *(recommended for personal)*

In both, a project-level copy wins over a user-level one when both exist — handy if a specific
plugin repo needs to pin a different version.
