# Asset inventory — FengYu 4.0 launch promo

No vision key available; built from DOM context. The docs-site capture yielded no
usable raster imagery for a promo (VitePress default theme, icons and fonts only).

- `capture/assets/svgs/` — site UI icons (logo mark, nav glyphs); small, monochrome.
- `capture/assets/fonts/` — webfonts from the docs site (Inter + Punctuation SC).
- No screenshots of the product UI itself: the product was not run during capture.

**Decision (recorded in BRIEF.md):** all product visuals (chat, flow canvas, plugins
page, desktop shell) are rebuilt as stylized HTML inside the compositions, using the
true brand tokens from `frontend/src/plugins/md3-themes.ts` (Codex-style monochrome
palette, mint accent) — written into `capture/extracted/tokens.json`.
