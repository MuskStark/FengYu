# ZhiFlow UI Design System

The ZhiFlow user interface is a faithful implementation of the **JetBrains IntelliJ IDEA
2025 "New UI"** visual language — neutral-gray surfaces, a single restrained accent, flat
shapes, and motion that serves feedback rather than spectacle. This doc set is the complete,
code-accurate reference for that system, written so that an AI (or a human who has never
seen the codebase) can generate UI that matches the v3.2.0 shell.

It is **bilingual** (this English tree mirrors
[`/zh/ui-design/`](/zh/ui-design/) exactly) and **code-anchored** — every class name, token
value, and JavaFX signature cited is verifiable against the real ZhiFlow source.

> **Web frontend note:** As of 4.0.0 the web shell (`frontend/`) implements
> Material Design 3 via Vuetify 3, not the IntelliJ token system below.
> The `--sk-*` token spec in these docs remains authoritative for the
> **JavaFX host** only.

## The 8 documents

| # | Document | What it's for | Read it if you… |
|---|---|---|---|
| **01** | [UI Design System](01-design-system.md) | Philosophy, layout primitives, typography/spacing/radius scales, how ZhiFlow extends IDEA New UI. | want the "why" — start here. |
| **02** | [JavaFX Implementation](02-javafx-implementation.md) | The dev playbook: `SwissKitJPlugin` contract, CSS naming convention, plugin skeleton. | are about to code a plugin UI. |
| **03** | [Component Library](03-component-library.md) | Per-component spec for every `.sk-*` foundation class and every shell component (nav-item, tool-card, …). | need a specific widget. |
| **04** | [Interaction Guidelines](04-interaction-guidelines.md) | Navigation, discovery/launch, plugin lifecycle, four-state feedback, destructive-confirm flows. | are wiring user actions. |
| **05** | [Theme & Color System](05-theme-color-system.md) | The single source of truth for all `-sk-*` color tokens + the WCAG contrast matrix. | need an exact color value. |
| **06** | [Icon System](06-icon-system.md) | MDI icon library, `MdiIconUtil` API, size scale, `IconStyle` accent colors. | are placing an icon. |
| **07** | [Animation Guidelines](07-animation-guidelines.md) | Every shipped animation with duration/easing + `file:line` + copyable templates. | are adding motion. |
| **08** | [Accessibility Guide](08-accessibility-guide.md) | Contrast rules, "not by color alone", keyboard operability, reduced motion. | must verify the UI is usable by all. |

## How to use these docs

**By goal:**

- **"I want to understand the design philosophy"** → read [01](01-design-system.md).
- **"I'm coding a plugin UI"** → [02](02-javafx-implementation.md) (playbook) + [05](05-theme-color-system.md) (colors).
- **"I need a specific component"** → look it up in [03](03-component-library.md).
- **"What's the exact value of `-sk-bg-elevated`?"** → [05](05-theme-color-system.md#token-reference-table).
- **"How long should this animation be?"** → [07](07-animation-guidelines.md#duration-scale).
- **"Does this color pair pass WCAG AA?"** → [05 contrast matrix](05-theme-color-system.md#contrast-matrix-wcag-aa) + [08](08-accessibility-guide.md).

### Single-source-of-truth convention

Each kind of fact lives in exactly one doc and is *linked* from everywhere else — the set
never restates a value in two places:

| Fact | Lives in |
|---|---|
| Token hex values & contrast ratios | [05](05-theme-color-system.md#token-reference-table) |
| CSS class names & naming convention | [02](02-javafx-implementation.md#css-naming) / [03](03-component-library.md) |
| Icon names, sizes, `IconStyle` colors | [06](06-icon-system.md#icon-reference) |
| Animation durations & easings | [07](07-animation-guidelines.md) |
| Component CSS & states | [03](03-component-library.md) |

If you find a discrepancy between a doc and the source, **the source wins** — file an issue or
open a PR.
