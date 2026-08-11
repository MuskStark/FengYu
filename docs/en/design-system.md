---
title: Design System
description: Infinia 4.0.0 uses Material Design 3 on Vuetify 3, with isolated plugin UIs synchronized through the host bridge.
lang: en
---

# Design System

Infinia 4.0.0 renders its entire UI — host shell and plugin micro-frontends alike — with **Vuetify 3** on the **Material Design 3** baseline. There is no JavaFX layer in the 4.0.0 desktop product; the Electron shell hosts the same Vue UI the browser does.

## Material Design 3 baseline

The palette is Google's MD3 default (primary `#6750A4`). Colors, elevation, shape, and typography follow the Material 3 token system, surfaced through Vuetify 3's theme engine.

- **Light and dark themes** ship out of the box.
- The MD3 palette is defined in `frontend/src/plugins/md3-themes.ts`.

## Theme store

Theming is driven by a single Pinia store, the `useThemeStore` singleton. Components read theme state from this store rather than touching Vuetify directly, so dark/light mode and palette swaps propagate consistently.

## Plugin micro-frontends use the UI kit

A plugin runs in a separate iframe JavaScript realm, so it cannot reuse the host's Vue or Vuetify instances. It installs `@infinia/plugin-ui`, which creates a plugin-local Vuetify instance with FengYu's components, defaults, and themes:

```ts
const vuetify = createFengYuVuetify()
app.use(vuetify)
await bindFengYuEnvironment(vuetify, fengyu)
```

`bindFengYuEnvironment` obtains the initial locale and theme during `host.ready`, then applies later `environment` events. Isolation is preserved while the visible theme stays aligned.

## Summary

| Layer | What it is |
| --- | --- |
| Component library | Vuetify 3 |
| Design language | Material Design 3 (Google default palette) |
| Palette source | `frontend/src/plugins/md3-themes.ts` |
| Theme runtime | Pinia `useThemeStore` singleton |
| Plugin theming | Local `@infinia/plugin-ui` instance bound to SDK environment events |
| Themes | Light and dark |

## Next steps

- [Features](/en/features) — what the MD3 UI renders.
- [Quick Start](/en/quickstart) — run the frontend dev server to see it live.
