# Email Center Codex Style Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the completed Email Center task workspace with a restrained Codex-like visual system without changing behavior.

**Architecture:** Add scoped light/dark `--email-*` presentation tokens and compact Vuetify overrides in the iframe stylesheet. Keep the existing component tree and add only a small semantic brand block to the task rail.

**Tech Stack:** Vue 3.5, TypeScript, Vuetify 3, Vitest, CSS custom properties.

## Global Constraints

- Preserve all Worker RPCs, stores, business behavior, bilingual copy, and workspace IDs.
- Continue using Vuetify 3 and SVG icons; add no UI library, icon font, or remote font.
- Follow the existing host `light`/`dark` environment and the 1000 px / 720 px responsive breakpoints.
- Keep visible keyboard focus and respect `prefers-reduced-motion`.

---

### Task 1: Apply the Codex visual system

**Files:**
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/ResponsiveShell.test.ts`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/components/TaskRail.vue`
- Modify: `OfficialPlugins/plugin-email/ui-src/src/styles.css`

**Interfaces:**
- Consumes: `document.documentElement.dataset.theme`, `.email-layout`, `.task-rail`, `.surface`, and the existing 1000 px / 720 px breakpoints.
- Produces: scoped `--email-*` light/dark tokens and unchanged navigation/store behavior.

- [ ] **Step 1: Write the failing visual contract test**

Extend `ResponsiveShell.test.ts` to require explicit light/dark `--email-canvas`
tokens, a `200px` desktop rail, horizontal task-rail items, `12px` surfaces with
low elevation, a green accent token, and a reduced-motion media query.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `npm test -- ResponsiveShell.test.ts`

Expected: FAIL because the existing stylesheet uses an 88 px vertical icon rail,
18 px elevated cards, and has no `--email-*` token layer.

- [ ] **Step 3: Implement the minimal semantic and CSS changes**

Add a `task-rail__brand` block to `TaskRail.vue`. Rewrite `styles.css` with scoped
Codex-like tokens, compact component overrides, a 200 px desktop navigation rail,
the unchanged 1000 px collapse behavior, and the unchanged 720 px horizontal
mobile navigation behavior.

- [ ] **Step 4: Verify the focused test and full frontend**

Run:

```bash
npm test -- ResponsiveShell.test.ts
npm test
npm run typecheck
npm run build
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-07-14-email-center-codex-style-design.md \
  docs/superpowers/plans/2026-07-14-email-center-codex-style-refresh.md \
  OfficialPlugins/plugin-email/ui-src/src/components/ResponsiveShell.test.ts \
  OfficialPlugins/plugin-email/ui-src/src/components/TaskRail.vue \
  OfficialPlugins/plugin-email/ui-src/src/styles.css
git commit -m "✨ feat(email): adopt Codex workspace styling"
```
