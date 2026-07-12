# Popup & Full-Project Theme Strict-Audit — Design Spec

- **Date:** 2026-07-01
- **Branch:** `v3.2.0`
- **Scope:** Full-project strict tokenization of hardcoded colors, fixing popup/dialog
  theme adaptation in both DARK and LIGHT (纯白) themes.
- **Status:** Design (pending implementation plan)

---

## 1. Problem

The user reported "主项目所有弹出提示框，均未适配黑暗与纯白主题" (all popup notification boxes
in the main project are not adapted for dark and pure-white themes). An exhaustive audit found the
root cause is narrower than the symptom, but the user elected a **full-project strict tokenization**
("全项目严格化") so that *no* hardcoded color remains.

### Root cause — why popups look un-themed

`GlassNotification` (the single toast/notify/confirm component used app-wide) loads
`fengyu-common.css` directly but **never stamps the theme class on its scene root**:

```java
// GlassNotification.java:259-262  (BUG)
Scene scene = new Scene(root);
scene.setFill(null);
scene.getStylesheets().add(..."/css/fengyu-common.css"...);  // stylesheet loaded...
stage.setScene(scene);
// ...but Themes.applyTo(scene) is NEVER called → no .theme-dark/.theme-light class
// → every -sk-* looked-up color is UNDEFINED → falls back to JavaFX Modena (white)
```

Because JavaFX looked-up colors (`-sk-*`) only resolve under a `.theme-dark` / `.theme-light`
class on the scene root (see `fengyu-common.css:17-48`), all notification colors collapse to
JavaFX defaults in **both** themes. Compare `PluginPreviewWindow.java:179`, which correctly calls
`Themes.applyTo(scene)` with an explanatory comment.

### Strict-audit findings (full project)

Beyond the popup bug, the audit found pervasive hardcoded colors that break the light theme or
bypass the token system:

| Category | Severity | Example |
|---|---|---|
| `StepWizard` indicator built on white tints | **Critical** — invisible idle dots in light theme | `IDLE_COLOR = "rgba(255,255,255,0.15)"` |
| Modal scene backdrops hardcoded near-black | High — black window behind light card | `SettingUi.java` `#0d0e11` (×4) |
| Dialog root backgrounds hardcoded dark slate | High — dark dialog in light theme | `EmailPlugin.java` `#1f2937` (×2) |
| Status text palette parallel to tokens | Medium — wrong green/red/amber, not theme-aware | `#4cd97b` / `#f25c5c` / `#f5a623` (15+ sites) |
| Black-based drop-shadows in CSS | Medium — muddy on white | `rgba(0,0,0,0.45/0.50)` |
| Status-soft fills hardcoded (not tokens) | Medium — wrong hue, no theme alpha | `.sk-notif-success` `rgba(76,217,123,0.15)` |
| Hardcoded accent hex | Low — coincidental match today | `AboutDialog.java:119` `#3574F0` |

---

## 2. Design Principles (existing, followed — not changed)

These are documented contracts in `docs/ui-design/05-theme-color-system.md` that this design
**extends**, not violates:

- **P5 — Colors live in CSS, never in `setStyle()`.** JavaFX inline `setStyle("-fx-text-fill:
  -sk-text;")` does NOT resolve looked-up colors. → Color via `.sk-*` utility class; geometry
  (padding/radius/font-size) may stay inline. A node may carry both.
- **Tokens are looked-up colors** declared under `.theme-dark` / `.theme-light` on the scene root.
  Switching theme = swapping one class; every `-sk-*` reference re-resolves with no reload.
- **Token count was 14.** The doc said "do not invent a 15th token name — extend via utility
  classes or propose a new token in the CSS first." This design formally **proposes new tokens in
  the CSS first** (the sanctioned escape hatch), raising the count to 19 and updating the doc.

---

## 3. New Theme-Aware Tokens & Utility Classes

### 3.1 New looked-up colors (in `fengyu-common.css`, under both theme blocks)

| Token | Dark | Light | Purpose |
|---|---|---|---|
| `-sk-shadow` | `rgba(0,0,0,0.45)` | `rgba(15,23,42,0.18)` | Card/dialog/notification drop-shadow (light = softer) |
| `-sk-scrim` | `rgba(0,0,0,0.50)` | `rgba(15,23,42,0.32)` | Modal dialog dimming backdrop |
| `-sk-success-soft` | `rgba(91,176,101,0.18)` | `rgba(60,145,74,0.14)` | Success soft-fill (mirrors `-sk-accent-soft`) |
| `-sk-warning-soft` | `rgba(240,167,50,0.18)` | `rgba(194,117,28,0.14)` | Warning soft-fill |
| `-sk-danger-soft` | `rgba(247,84,100,0.18)` | `rgba(229,57,53,0.14)` | Danger soft-fill |

Design notes:
- The `*-soft` tokens are derived from the existing `-sk-success/-sk-warning/-sk-danger` hex values
  (not the audit's wrong RGB triples), with the same dark/light alpha split as `-sk-accent-soft`
  (0.18 dark / 0.14 light). This makes the four status families consistent.
- Shadow/scrim light values use a low-saturation slate (`rgb(15,23,42)`) at low alpha instead of
  pure black, so shadows read as soft elevation rather than muddy smudges on `#FFFFFF`.

### 3.2 New utility classes (color-via-class, per P5)

| Class | CSS | Use for |
|---|---|---|
| `.sk-accent-text` | `-fx-text-fill: -sk-accent;` | Hyperlinks, accent-colored labels (Java-side, inline-safe) |
| `.sk-success-text` | `-fx-text-fill: -sk-success;` | Success status labels |
| `.sk-warning-text` | `-fx-text-fill: -sk-warning;` | Warning status labels |
| `.sk-danger-text` | `-fx-text-fill: -sk-danger;` | Error status labels |
| `.sk-scrim` | `-fx-background-color: -sk-scrim;` | Modal backdrop fills |

These join the existing `.sk-t1/.sk-t2/.sk-surface/.sk-outlined` family (`fengyu-common.css:116-124`).

### 3.3 Token count

After this change: **19 tokens** (14 existing + 5 new). The doc's token table, the count statement,
and the "do not invent" note are all updated to reflect the new semantic groups (shadow/scrim,
status-soft) and the rule that future additions follow the same propose-in-CSS-first process.

---

## 4. Implementation Tiers

### Tier 1 — Core popup bug (root cause)

| File:Line | Change |
|---|---|
| `GlassNotification.java:259-262` | Replace manual `scene.getStylesheets().add(...)` with `Themes.applyTo(scene);`. This stamps `.theme-dark`/`.theme-light` so all `-sk-*` tokens resolve. Fixes every toast/notify/confirm app-wide. |

### Tier 2 — CSS hardcoded colors → new tokens (3 target files)

| File:Line | Current | After |
|---|---|---|
| `fengyu-common.css:133` (`.sk-dialog` shadow) | `rgba(0,0,0,0.45)` | `-sk-shadow` |
| `fengyu-common.css:301` (`.sk-notif-root` shadow) | `rgba(0,0,0,0.50)` | `-sk-shadow` |
| `fengyu-common.css:312` (`.sk-notif-success` bg) | `rgba(76,217,123,0.15)` | `-sk-success-soft` |
| `fengyu-common.css:313` (`.sk-notif-warning` bg) | `rgba(245,166,35,0.15)` | `-sk-warning-soft` |
| `fengyu-common.css:314` (`.sk-notif-error` bg) | `rgba(242,92,92,0.15)` | `-sk-danger-soft` |

### Tier 3 — Java popup `setStyle` hardcoded colors

| File:Line | Current | After |
|---|---|---|
| `AboutDialog.java:48` (scrim) | `setStyle("-fx-background-color: rgba(0,0,0,0.35);")` | Add `.sk-scrim` class; remove inline bg color (keep geometry inline if any) |
| `AboutDialog.java:119` (link) | `setStyle("-fx-text-fill: #3574F0; ...")` | Add `.sk-accent-text` class; keep inline `-fx-font-size`/`-fx-border-color`/`-fx-padding` |

### Tier 4 — Project-wide hardcoded colors (the "strict" scope)

| Area | Files:Lines | Fix pattern |
|---|---|---|
| **Modal scene backdrops** | `SwissKitJSettingUi.java:1480,1588,1755,1899` (`#0d0e11` scene fill) | Replace with `-sk-bg`-derived fill via a `.sk-surface`/`.sk-scrim` class on the scene root (these dialogs already call `Themes.applyTo`, so tokens resolve). **Decision: use `-sk-bg`** (solid window base), not `-sk-scrim` — scrim is for transparent-stage modal dimming (AboutDialog); these are decorated windows that need an opaque base. |
| **Dialog root bg** | `EmailPlugin.java:504,626` (`#1f2937` root `setStyle`) | Use `.sk-dialog` class instead of inline dark slate |
| **Status text colors** (15+ sites) | `SwissKitJSettingUi.java:799,802,850,853,1853,1854,1867`; `EmailPlugin.java:292,296,306` (`#4cd97b`/`#f25c5c`/`#f5a623`) | Replace `setStyle("-fx-text-fill:#...")` with `.sk-success-text`/`.sk-danger-text`/`.sk-warning-text` classes |
| **Primary button** | `EmailPlugin.java:670` + shared `glassBtn` helper (`#3574F0`+`white`) | Use existing `.sk-btn-primary` class |
| **StepWizard indicator** | `StepWizard.java:84-86,178,215-216,218,220-221,223,230-231,233,238-239` | Rewrite indicator: tokenize `ACCENT`→`-sk-accent`, `DONE_COLOR`→`-sk-success`, idle dots/strokes/connectors from white-tints to `-sk-bg-selected`/`-sk-border` so they are visible on white; checkmark/idle number text via `.sk-t1`/`.sk-t3` |
| **ToggleSwitch** | `ToggleSwitch.java:26-27,40` (`Color.rgb(255,255,255,0.18)`, `#3574F0`, `Color.WHITE`) | Tokenize the track/knob fills: the `#3574F0` accent-on knob uses `-sk-accent`; the `Color.WHITE` knob text stays white (on-accent contrast, same precedent as `.sk-btn-primary`); the white-tint track idle fill becomes `-sk-bg-selected` so it shows on both themes. Implementer to confirm each line's role during the pass. |
| **CSS shadows (other files)** | `fengyu-preview.css:16,131,164`; `shell.css:169`; `builtin.css:91,125` | Black-based dropshadows → `-sk-shadow` |

### Out of scope (explicitly excluded)

- `RichTextEditor.java:136` `ColorPicker(Color.WHITE)` — user-selectable control default, not styling.
- `.sk-notif-ok` `white` text (`fengyu-common.css:319`) — contrast-on-accent text, correct in both themes (precedent: `.sk-btn-primary`, checkbox mark).
- `backup/` directory — pre-refactor, not on active classpath.

---

## 5. Verification

### Mechanical (automated)
- **Build:** `mvn -q compile` (or project build) — no Java errors, especially for the
  `StepWizard` rewrite and the API-module `Themes.applyTo()` change.
- **Grep gate (repeatable acceptance check):** re-run the audit's grep patterns against the
  in-scope files and confirm **zero** matches:
  `#3574F0`, `#4cd97b`, `#f25c5c`, `#f5a623`, `#0d0e11`, `#1f2937`, `rgba(0,0,0,`,
  and scope-applicable `new Color(`/`Color.web(`/`Color.rgb(`.
  (Suggested: a short shell snippet checked into the verification steps, not a permanent test.)

### Visual (manual, both themes)
| Popup/component | Trigger | Check in BOTH themes |
|---|---|---|
| Toast | Any `GlassNotification.toast()` path | Dark card on dark theme; white card on light — NOT JavaFX default white |
| Confirm | Destructive action → `confirm()` | Card + OK/Cancel buttons follow theme |
| About dialog | About menu | Scrim adapts (lighter in light theme); link uses accent token |
| StepWizard | Any wizard flow | Idle dots VISIBLE on white (biggest regression risk) |
| Email modals | Mass Config / Sent Log | Card uses `.sk-dialog`, not dark slate |
| Settings modals | Address Book / Tags / Excel import | Scene fill matches theme, not near-black |
| Status labels | Test-connection / send result | Green/red/amber match theme tokens |

### Theme-switch behavior
- Long-lived dialogs (Email/Settings modals): confirm tokens re-resolve on switch.
- Short-lived popups (toast): no requirement — they dismiss before a switch.

---

## 6. Documentation Updates

- `docs/ui-design/05-theme-color-system.md` (+ `docs/zh/ui-design/05-...`): add the 5 new tokens to
  the spec table, add the new utility classes to §3.2, update token count 14→19, and revise the
  "do not invent the 15th token" note to describe the sanctioned propose-in-CSS-first process now
  that the count has grown.
- `CHANGELOG.md`: entry under the v3.2.0 section noting the breaking token-set expansion
  (`-sk-shadow`, `-sk-scrim`, `-sk-*-soft`) and the popup-theme fix.

---

## 7. Risk & Sequencing Notes

- **Highest risk: `StepWizard`.** It is architected around white-tint idle states; tokenizing it is
  a partial rewrite of the indicator rendering, not a search-and-replace. Implement and visually
  verify it first within Tier 4, before declaring Tier 4 done.
- **Tier 1 is the headline fix** and is independent of all other tiers — it can ship alone if any
  later tier runs into trouble.
- **API vs app module:** `GlassNotification`, `StepWizard`, `Themes`, and `fengyu-common.css`
  live in `SwissKitJ-Api` (consumed by plugins); `AboutDialog`/`SettingUi`/`EmailPlugin` live in
  the `SwissKit` app module. The new tokens/classes land in the API CSS so both modules see them.
