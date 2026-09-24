---
title: Design System
description: The Infinia host shell renders with the Zai design system (zai.css tokens on Tailwind 4); plugin micro-frontends keep their own Vuetify 3 / MD3 instance, synchronized through the host bridge.
lang: en
---

# Design System

Infinia renders its host shell with the **Zai design system** — a token palette defined in `frontend/src/styles/zai.css` and consumed through **Tailwind CSS 4** utilities. Plugin micro-frontends keep rendering with **Vuetify 3 on Material Design 3** inside their sandboxed iframes; the host bridge keeps their locale and dark/light theme aligned with the shell. There is no JavaFX layer in the desktop product; the Electron shell hosts the same React UI the browser does.

## Zai tokens (`zai.css`)

Two layers live in the single `frontend/src/styles/zai.css`:

- An `@theme` block: the `text-ui-*` typography scale (UI font size is owned by `--ui-font-size`) and the semantic `--color-*` defaults Tailwind utilities resolve against.
- The `.theme-zai-light` / `.theme-zai-dark` palette blocks, which also bridge the same values into the legacy `--v-theme-*` and `--cx-*` token spaces so older stylesheets resolve to Zai colors unchanged.

## Theme classes

Theme state is expressed as classes on `<html>`: `dark` (Tailwind variant), `theme-zai-dark` / `theme-zai-light`, and the legacy `v-theme--*` class. An inline anti-flash script in `index.html` applies the persisted theme before first paint; `stores/settings.ts` applies it thereafter and persists changes.

## Plugin micro-frontends use the UI kit

A plugin runs in a separate iframe JavaScript realm, so it cannot reuse the host's React tree or tokens. It installs `@infinia/plugin-ui`, which creates a plugin-local Vuetify instance with FengYu's components, defaults, and themes:

```ts
const vuetify = createFengYuVuetify()
app.use(vuetify)
await bindFengYuEnvironment(vuetify, fengyu)
```

`bindFengYuEnvironment` obtains the initial locale and theme during `host.ready`, then applies later `environment` events. Isolation is preserved while the visible dark/light mode stays aligned.

## Summary

| Layer | What it is |
| --- | --- |
| Shell design language | Zai (ZCode-derived token system) |
| Shell styling | Tailwind CSS 4 + `frontend/src/styles/zai.css` |
| Theme classes | `dark` + `theme-zai-*` + legacy `v-theme--*` on `<html>` |
| Theme runtime | `stores/settings.ts` + the `index.html` anti-flash script |
| Plugin theming | Local `@infinia/plugin-ui` (Vuetify 3 / MD3) instance bound to SDK environment events |
| Themes | Light and dark |

## Next steps

- [Features](/en/features) — what the Zai UI renders.
- [Quick Start](/en/quickstart) — run the frontend dev server to see it live.
