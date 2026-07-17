# CLAUDE.md

This file guides Claude Code in this repository.

**Read and follow [`AGENTS.md`](AGENTS.md).** It is the single canonical source of project guidance
for all AI assistants, including Claude Code. Do not maintain a separate copy of the architecture,
build commands, plugin model, release rules, or working conventions here — `AGENTS.md` (and the
canonical skills under `.agents/skills/`) is the source of truth.

When a workflow skill applies, Claude Code reaches the same canonical skill through the short
adapter under `.claude/skills/<name>/SKILL.md`, which points at `.agents/skills/<name>/SKILL.md`.
Execute that canonical skill completely.
