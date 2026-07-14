---
title: Design System
description: Infinia 4.0.0 uses Material Design 3 on Vuetify 3, shared across the host UI and plugin micro-frontends.
lang: en
---

# Design System

Infinia 4.0.0 renders its entire UI — host shell and plugin micro-frontends alike — with **Vuetify 3** on the **Material Design 3** baseline. There is no JavaFX layer in the 4.0.0 desktop product; Tauri hosts the same Vue UI the browser does.

## Material Design 3 baseline

The palette is Google's MD3 default (primary `#6750A4`). Colors, elevation, shape, and typography follow the Material 3 token system, surfaced through Vuetify 3's theme engine.

- **Light and dark themes** ship out of the box.
- The MD3 palette is defined in `frontend/src/plugins/md3-themes.ts`.

## Theme store

Theming is driven by a single Pinia store, the `useThemeStore` singleton. Components read theme state from this store rather than touching Vuetify directly, so dark/light mode and palette swaps propagate consistently.

## Plugin micro-frontends share the host's Vuetify

A plugin's `ui/` micro-frontend mounts inside the host and reuses the **same Vuetify instance** the shell already installed — it does not bring its own copy. The host exposes that instance through `PluginContext.vuetify`, and the micro-frontend consumes it at mount:

```ts
app.use(ctx.vuetify)
```

This keeps MD3 tokens, components, and theme state identical between the host and every loaded plugin.

## Summary

| Layer | What it is |
| --- | --- |
| Component library | Vuetify 3 |
| Design language | Material Design 3 (Google default palette) |
| Palette source | `frontend/src/plugins/md3-themes.ts` |
| Theme runtime | Pinia `useThemeStore` singleton |
| Plugin theming | Reuses host Vuetify via `PluginContext.vuetify` (`app.use(ctx.vuetify)`) |
| Themes | Light and dark |

## Next steps

- [Features](/en/features) — what the MD3 UI renders.
- [Quick Start](/en/quickstart) — run the frontend dev server to see it live.
