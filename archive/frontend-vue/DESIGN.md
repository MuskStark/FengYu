# FengYu Frontend Design System

Portable rules for UI work in `frontend/`. Humans and coding agents follow this file before
inventing visual rules; violating it is a design-system defect, not a style preference.
When this file conflicts with the code, the token files below are the source of truth —
fix whichever is wrong rather than widening the split.

## Product character

Calm, flat, hairline-bordered, information-dense. The shell is a desktop-first workspace
(browser and Electron webview), not a marketing surface:

- **Borders, not shadows.** Structure comes from `1px solid var(--cx-border)` and background
  contrast. Shadows are reserved for popover menus (one shared recipe, see below).
- Long sessions, readable chat, compact operational controls.
- Both themes always: every color decision is validated against `v-theme--dark` AND
  `v-theme--light`.

## The three layers

1. **Vuetify MD3 palette** — `plugins/md3-themes.ts` defines the dark/light palettes and
   exposes them as `--v-theme-*` CSS vars (installed by `plugins/vuetify.ts`). Vuetify
   components are NOT the visual language of the shell; the instance mainly provides theme
   CSS variables and the micro-frontend host.
2. **Semantic tokens** — `theme/tokens.css`: `--cx-hover`, `--cx-border`,
   `--cx-border-subtle`, `--cx-user-tint`, `--cx-code-bg`, `--cx-hl-*` (code highlighting),
   radii `--cx-radius-sm|​|lg` (8/10/14), plus the shared hljs diff/neutral mappings. The same
   token name resolves in both themes; only the values flip between the `.v-theme--dark` and
   `.v-theme--light` blocks.
3. **Component kit** — `theme/codex.css`: `cx-btn` (+ `--primary/--tonal/--outline/--text/--sm`),
   `cx-iconbtn`, `cx-input`, `cx-card`, `cx-chip`, `cx-alert`, `cx-details`, `cx-segment`,
   `cx-setting-row`, `cx-page`, `cx-topbar`, `cx-spin`, plus conversation primitives
   (`cx-conversation`, `cx-msg`, `cx-md`, `cx-composer`, `cx-code`) in `tokens.css`.

Rules:

- Never hardcode a color in a view. Use `rgb(var(--v-theme-*))` or `var(--cx-*)`.
- Reuse a `cx-*` class before inventing a new one; extend with a scoped modifier class in
  the component (`.cx-btn.my-trigger { … }`), never by editing `codex.css` for one screen.
- Semantic colors (`error`, `tertiary`…) carry meaning only; never borrow them to make a
  block louder. Diff rows are the one sanctioned success/error pairing.
- New tokens follow the two-theme pattern: define the variable in both theme blocks of
  `tokens.css` first.

## Layout and views

- Views orchestrate; feature components own their half. The reference decomposition is the
  AI chat: `views/AiChat.vue` (top bar + layout + workspace panel state) →
  `components/chat/ChatTranscript.vue` (read-only timeline) + `components/chat/ChatComposer.vue`
  (all input/context state) + `components/chat/WorkspacePanel.vue` (read-only workspace file
  tree and preview). New chat-like surfaces should reuse these patterns instead of
  re-implementing message rendering or SSE handling from scratch.
- Conversation content sits in `cx-conversation` (760px reading measure, centered).
- **No inline `style="…"` in views.** Put one-off rules in the component's `<style scoped>`
  as named classes. Dynamic styling stays a `:style` binding only when it encodes state
  (e.g. an error tint), not for static layout.
- Overriding a global `cx-*` class from scoped CSS requires compound specificity
  (`.cx-card.my-menu`), because plain class ties resolve by injection order.
- Popover menus share one recipe: `.cx-card` surface, `position: absolute` above the
  composer, `box-shadow: 0 12px 32px rgba(0,0,0,.18)`, `data-menu` pairs + a document
  outside-click listener for closing (see `ChatComposer`).
- Spacing rhythm: 4 / 8 / 12 / 16 / 20-24. Body text is 14px; supporting text 12-13px;
  chips and micro-labels 11px. Monospace only for code, paths, commands, hashes.

## Markdown and chat content

- Untrusted markdown renders ONLY through `security/markdown.ts` (`renderMarkdown`) —
  the marked + DOMPurify pipeline that also wraps fenced code in the `cx-code` shell.
  Never `v-html` model output directly.
- Code blocks are highlighted synchronously by highlight.js (common bundle); token colors
  come exclusively from the `.cx-md .cx-code .hljs-*` mapping in `tokens.css`. Unknown
  languages fall back to plain escaped text — never auto-detect.
- Copy affordances inside sanitized content are `span[role="button"]` with keyboard
  delegation on the scroll container (DOMPurify forbids real buttons there).
- Unified diffs render per-line (`diffLines` + `cx-diff__add|del|hunk|ctx`): added =
  success color, removed = error color, hunk = primary, context = faded on-surface.

## i18n

- Every user-visible string goes through vue-i18n keys. `src/i18n/en.json` and `zh.json`
  must stay leaf-for-leaf aligned (there is a coverage check in review; keep it green).
- Components use `useI18n()`; stores and store-adjacent modules use `i18n.global.t(...)`
  (see `stores/aiSession.ts`, `stores/aiToolActivity.ts` for the pattern).
- Hardcoded English anywhere in a store or view is a bug, including error fallbacks.
- Write layouts that survive longer Chinese and English strings; truncation needs a
  `title`/tooltip fallback, never silent clipping alone.

## Accessibility

- Keep `focus-visible` outlines working (`codex.css` wires them for buttons and inputs).
- Loading states: `cx-spin` with a text label or `role="status"`; errors: `role="alert"`
  or the `cx-alert--error` surface.
- Icon-only buttons carry `:title` / `aria-label`.
- Never encode meaning by color alone (diff rows pair color with +/- line text; activity
  rows pair icons with status words).

## Don't

- Don't reintroduce Vuetify component markup as the shell language (`v-btn`, `v-card`, …);
  the plugin stays for theming and micro-frontends.
- Don't ship a color, radius, or shadow that exists nowhere else in the shell.
- Don't add per-view copies of chat primitives; extend `components/chat/` instead.
- Don't bypass `renderMarkdown`, and don't run raw highlight.js themes with their own
  colors — the palette lives in `tokens.css`.
