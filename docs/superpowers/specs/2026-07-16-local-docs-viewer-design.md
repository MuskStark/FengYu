# Local Docs Viewer

## Problem

The About page's Documentation row links out to the online GitHub Pages site
(`https://muskstark.github.io/FengYu/`). The project's full documentation — a
VitePress site with `/en/` and `/zh/` locales and local search — is already
authored in `docs/` and builds to a self-contained static bundle
(`docs/.vitepress/dist/`, ~4.5 MB). None of it ships with the application, so
offline users (notably the Tauri desktop build) have no in-app documentation at
all.

## Goal

Bundle the built VitePress site into the main program and add a "Local Docs"
entry on the About page that opens the documentation inside the application,
locale-aligned with the active app language.

## Decisions (confirmed)

- **Bundling**: Frontend bundle. A Vite plugin builds VitePress and copies the
  output into `frontend/public/docs/`, so it ships inside the Tauri bundle
  (`frontendDist: ../frontend/dist`) and is served by the Vite dev server. Zero
  backend changes.
- **Viewing UX**: In-app iframe viewer. A new `/docs` route hosts
  `LocalDocs.vue`, which embeds the VitePress site in an iframe. The VitePress
  site renders with its own nav, sidebar, and search. A back button returns to
  the About page.
- **Build pipeline**: A `bundleDocs()` Vite plugin (mirroring the existing
  `vendorVue()` plugin) runs `vitepress build docs` at `buildStart` and copies
  the result; a dev-only middleware serves it. Fully automatic for both dev and
  prod.

## Design

### Data flow

```
docs/ (VitePress source)
  └─ vitepress build → docs/.vitepress/dist/      [bundleDocs() runs this]
       └─ recursive copy → frontend/public/docs/  [buildStart, build only]
            └─ vite build → frontend/dist/docs/   [verbatim public asset]
                 └─ Tauri bundles frontend/dist   [frontendDist: ../frontend/dist]
```

`/docs` is not among the SPA routes (`/`, `/setup`, `/tools`, `/agent`,
`/plugins`, `/settings`, `/about`, `/plugin/:id`), so there is no router
collision. VitePress uses `cleanUrls` and renders its own `index.html` with full
nav, so sub-paths need no SPA fallback.

### Component 1 — `bundleDocs()` Vite plugin

File: `frontend/vite.config.ts` (new plugin alongside `vendorVue()`).

- `apply: 'build'`: in `buildStart()`, run `vitepress build docs` from the repo
  root (shell exec), then recursively copy `docs/.vitepress/dist/*` into
  `frontend/public/docs/`. Mirrors `vendorVue()` (current lines 37-51) which
  copies a file into `public/vendor` at `buildStart`.
- `bundleDocsServe()` — `apply: 'serve'`, `enforce: 'pre'`: a
  `configureServer(server)` middleware that serves `/docs/**` requests from
  `docs/.vitepress/dist/` on disk, building it once on first hit (cached by the
  dist directory mtime). This makes `vite dev` reflect a live VitePress rebuild
  without restarting Vite.

Defensive behavior: if VitePress is not installed or the dist directory cannot
be produced, log a warning and skip — a developer who has not checked out/built
the docs must still get a successful frontend build.

The recursive copy and the "build if missing" check are extracted as small pure
helpers (`syncDocsDist()`, `docsDistExists()`) so they can be unit-tested
without a real VitePress build.

### Component 2 — `/docs` route + `LocalDocs.vue`

New file: `frontend/src/views/LocalDocs.vue`.
Route: `{ path: '/docs', name: 'local-docs', component: () => import('@/views/LocalDocs.vue') }`
added to `frontend/src/router/index.ts`.

`LocalDocs.vue` layout:
- A top bar with a `← Back` button that calls `router.back()` (falls back to
  `router.push('/about')` if there is no history).
- An `<iframe>` filling the remaining height.

iframe `src` is a computed locale path:
`/docs/${locale.value.startsWith('zh') ? 'zh' : 'en'}/`. A watcher on `locale`
reloads the iframe when the app language changes at runtime, so the docs stay
aligned with the UI language.

Fallback: the iframe `@load`/`@error` handlers (plus a load timeout) detect a
missing bundle (e.g. the build skipped docs). On failure, hide the iframe and
show a message card with a link to the online site, using the
`localDocs.loadError` i18n key.

### Component 3 — About page entry

File: `frontend/src/views/About.vue`.

The existing Documentation row (lines 113-119) stays as-is — it is the *online*
docs. A new `cx-setting-row` is added directly below it:
- Label icon: `mdi-book-multiple` (distinct from the online row's
  `mdi-book-open-variant`).
- Label text: `$t('about.localDocs')`.
- Right side: a `cx-btn`-style button that calls `router.push('/docs')` (import
  `useRouter` in the `<script setup>`).

### Component 4 — i18n keys

Files: `frontend/src/i18n/{en,zh}.json`.

| key | en | zh |
|-----|----|----|
| `about.localDocs` | Local Docs | 本地文档 |
| `localDocs.title` | Documentation | 项目文档 |
| `localDocs.back` | Back | 返回 |
| `localDocs.loadError` | Local docs are unavailable in this build. You can view them online. | 本构建中本地文档不可用，可在线查看。 |

`about.localDocs` is added in the `about` namespace right after `about.docs`.

## Error Handling

- **VitePress not installed / dist missing**: `bundleDocs()` logs a warning at
  build time and skips the copy; the dev middleware returns 404; at runtime
  `LocalDocs.vue` shows the fallback card linking to the online site.
- **Locale mapping**: VitePress locales are `/en/` and `/zh/`. Any app locale
  starting with `zh` maps to `/zh/`; everything else falls back to `/en/`. No
  third locale exists today; the mapping is intentionally exhaustive.
- **No router conflict**: confirmed above; no SPA fallback needed for VitePress
  sub-paths.

## Testing

- Unit test the extracted helpers: `syncDocsDist()` copies when dist exists and
  is a no-op (returns false, no throw) when it does not; `docsDistExists()`
  reports presence correctly. These run without invoking VitePress.
- `LocalDocs.vue` component test (shallow mount): the iframe `src` follows the
  active `locale` (`zh` → `/docs/zh/`, otherwise `/docs/en/`), and the fallback
  card renders when the iframe signals an error.
- Manual/CI smoke: `cd frontend && npm run build` succeeds with the docs plugin
  active; the About page shows the Local Docs button; clicking it loads the
  iframe; toggling the app language reloads the iframe to the matching locale.

## Scope (non-goals)

- No backend changes (no controller, no classpath serving, no token exemption).
- No `marked`-based client-side rendering — VitePress emits complete HTML.
- No documentation content or theme changes — the VitePress site is served
  as-is.
- No separate search index build — VitePress local search is already bundled in
  the dist.
