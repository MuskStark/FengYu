# AI-Assisted Plugin Development

ZhiFlow ships an **Agent Skills** package that turns an AI coding agent into an expert
ZhiFlow plugin author. It knows the real `SwissKitJPlugin` contract (3.2.0, 16 methods),
the SPI loading mechanism, the theme/component system, and the recurring pitfalls — so the
plugins it produces **load cleanly and render theme-correctly** the first time, and follow the
[UI Design System](/ui-design/README.md) instead of inventing their own look.

> **Cross-agent:** the skill follows the open [Agent Skills](https://agentskills.io/) standard,
> so the **same files** work in **ZCode**, **Claude Code**, and any compatible agent
> (e.g. GitHub Copilot agent mode). You only pick the install directory.

## What it does

When you ask the agent to build/scaffold/debug a plugin, the skill activates and guides it to:

- Implement `SwissKitJPlugin` **directly in one class** (the single-class pattern all 11
  builtins use), not a `*PluginUi` wrapper.
- Wire the 7 required + 9 default methods with the correct signatures and returns.
- Set up `META-INF/services/fan.summer.zhiflow.api.SwissKitJPlugin` and the `maven-shade-plugin`
  `ServicesResourceTransformer` so the plugin actually loads.
- Theme the UI with `-sk-*` tokens / `.sk-*` classes (never inline hex), matching the
  [design spec](/ui-design/01-design-system.md) — typography, spacing, radius, motion,
  accessibility.
- Expose AI tools, run background tasks, and use the shared components (`GlassNotification`,
  `StepWizard`, `UiUtils`).

It ships with a ready-to-copy Maven scaffold (`pom.xml`, plugin class, dev launcher, SPI file,
i18n bundles) under `assets/plugin-template/`.

## Install

The skill is portable and self-contained (absolute URLs + inlined facts, no repo-relative
paths), so the **same files** work everywhere. Pick the **directory** for your agent:

| Agent | Project-level (committed, recommended) | User-level (all your projects) |
|---|---|---|
| **ZCode** | `<project>/.agents/skills/` | `~/.agents/skills/` |
| **Claude Code** | `<project>/.claude/skills/` | `~/.claude/skills/` |

### In a plugin repo (recommended for the official plugin repo & any plugin project)

Commit the skill so every contributor gets it. For **Claude Code**:

```bash
# from the root of your plugin project
mkdir -p .claude/skills
svn export https://github.com/MuskStark/ZhiFlow/trunk/.agents/skills/zhiflow-plugin-dev \
  .claude/skills/zhiflow-plugin-dev
git add .claude/skills/zhiflow-plugin-dev
git commit -m "chore: add zhiflow-plugin-dev skill"
```

For **ZCode**, do the same under `.agents/skills/`. A plugin repo used by contributors on both
agents can hold **both** copies (identical files) — keep them in sync.

### User-level (for third-party developers — all projects)

Install once into your home directory:

```bash
# Claude Code
mkdir -p ~/.claude/skills
svn export https://github.com/MuskStark/ZhiFlow/trunk/.agents/skills/zhiflow-plugin-dev \
  ~/.claude/skills/zhiflow-plugin-dev

# ZCode
mkdir -p ~/.agents/skills
svn export https://github.com/MuskStark/ZhiFlow/trunk/.agents/skills/zhiflow-plugin-dev \
  ~/.agents/skills/zhiflow-plugin-dev
```

Full install details (both agents, discovery priority, sync/update) are in the skill's
[`INSTALL.md`](https://github.com/MuskStark/ZhiFlow/blob/main/.agents/skills/zhiflow-plugin-dev/INSTALL.md).

## Use it

Once installed, just describe what you want in your AI tool — the skill triggers on intent.
Examples:

- "帮我写一个 ZhiFlow 插件，把文本转成二维码"
- "Add an AI tool to my plugin that formats JSON"
- "My plugin loads but the UI shows raw i18n keys"
- "Scaffold a CSV sorter plugin"

You can also force-load it with `/zhiflow-plugin-dev <your request>`.

## Where it lives

- **Source / canonical copy:**
  [`MuskStark/ZhiFlow/.agents/skills/zhiflow-plugin-dev/`](https://github.com/MuskStark/ZhiFlow/tree/main/.agents/skills/zhiflow-plugin-dev)
- Targets **API 3.2.0** and the current `docs/ui-design/` spec. Re-sync from the main repo
  when the API or spec ships breaking changes.

The skill is a companion to these human-readable plugin docs — it encodes the same facts (plus
the current 3.2.0 contract) in a form the agent can apply directly. For the authoritative API
contract and design rules, always cross-check with these docs and the
[UI Design System](/ui-design/README.md).
