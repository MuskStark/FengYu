---
title: Aceternity UI Components
description: "Fetch Aceternity UI components into your plugin with fengyu add — straight from the official registry, stamped with provenance, under Aceternity's own license."
lang: en
---

# Aceternity UI Components

The Infinia kit (`@infinia/plugin-ui`) ships the FengYu-owned pieces — shell,
bars, pages, states, pickers, select/combobox, wizard, and the bootstrap hooks.
**Aceternity UI components are intentionally not part of the package**: the
[Aceternity license](https://ui.aceternity.com/licence) permits using their
components inside end products but forbids redistributing their source files,
and a published component kit would be exactly that redistribution.

Instead, fetch what you need per project, directly from the official registry:

```bash
fengyu add sidebar          # or: fengyu add aceternity/sidebar
```

## What the command does

- Fetches `https://ui.aceternity.com/registry/<name>.json` **on your machine,
  at command time** — the CLI deliberately bundles, mirrors, and caches
  nothing, and there is no offline fallback. If the fetch fails it prints the
  registry URL so you can copy the component manually from
  [ui.aceternity.com](https://ui.aceternity.com).
- Writes the component's files under `ui-src/src/aceternity/` (a root-level
  `src/aceternity/` for `--ui-only` scaffolds), keeping the payload's inner
  layout for multi-file components.
- Stamps every written file with a provenance header: source URL, © Aceternity
  UI, fetch date, and the license reminder.
- Adapts shadcn-style imports for the FengYu scaffold — `@/lib/utils` becomes
  `@infinia/plugin-ui` (which exports the same `cn`), `@/components/*` becomes
  a sibling path inside `src/aceternity/`. Pass `--raw-imports` to keep the
  upstream sources byte-identical.
- Installs the dependencies the registry payload declares (for example
  `motion`, `@tabler/icons-react`) with `npm install --save` inside the UI
  project; already-present dependencies are skipped. `--no-install` prints the
  command instead of running it.
- Recursively pulls `registryDependencies` the same way, from the same
  upstream registry.

The first run per project asks you to acknowledge the Aceternity license
(`--yes` acknowledges non-interactively; the acknowledgment is remembered in
`ui-src/.fengyu-aceternity-ack`). Existing files are never overwritten unless
you pass `--force`.

## License guardrails

The rules are short:

- ✅ **Use and modify the components freely inside your plugin** — a plugin is
  an end product, and end products may be distributed (that includes shipping
  them through the Infinia store).
- ❌ **Never redistribute the source files.** Concretely: do not commit the
  fetched files under `src/aceternity/` to a **public** repository, do not
  re-publish them as part of a component library, and do not copy them into
  other people's projects. Private repositories and built plugin artifacts
  (the `.fyp`) are fine.
- Pro (paid) components are not on the public registry — purchase them on
  ui.aceternity.com and drop them into `src/aceternity/` yourself; the
  provenance-header convention above is still the recommended way to mark them.

FengYu's own first-party plugins follow the same model: the components live in
the private store repository and ship only as built artifacts.
