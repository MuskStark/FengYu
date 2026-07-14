# Email Center Codex Style Refresh Design

## Scope

This refresh changes only the visual presentation of the completed Email Center
redesign. Compose, Batch Send, Contacts, Archive, Send Records, Account Settings,
all Worker RPC contracts, bilingual copy, responsive behavior, accessibility, and
host theme synchronization remain unchanged.

## Visual Direction

The iframe uses a restrained Codex-like workspace rather than the current raised
Material card treatment:

- neutral warm-gray canvas and surfaces in light mode, and layered charcoal
  surfaces in dark mode;
- a compact 200 px text-and-icon task rail on desktop, with the existing
  horizontal navigation behavior below 720 px;
- 10–12 px radii, one-pixel neutral borders, and almost no elevation;
- dark neutral primary actions, with green reserved for active/status accents;
- compact headings, controls, tables, editor toolbar, and metadata;
- one clear work surface per task, with secondary previews visually quieter than
  the main form.

## Implementation Boundary

`styles.css` owns reusable `--email-*` visual tokens and Vuetify component
overrides scoped under `.email-layout`. `TaskRail.vue` adds a small brand label so
the navigation has the same product-workspace hierarchy as Codex. No new UI
library, icon font, remote font, hard-coded single-theme component color, or
business-state change is introduced.

## Acceptance

- Light and dark tokens are explicit and selected through the existing
  `data-theme` attribute.
- Desktop navigation is a compact 200 px rail with horizontal icon/label rows.
- Mobile navigation remains horizontal and scrollable at 720 px.
- Cards use low elevation, 12 px or smaller radii, and neutral borders.
- Focus indicators and reduced-motion behavior remain accessible.
- The full email UI test suite, typecheck, and production build pass.
