# Plan: Settings UI Glassmorphism Consistency

## Context

The settings UI (`SwissKitJSettingUi.java`) has inline glass-like styles but the three dialog windows use solid `#1c1f26` backgrounds that clash with the main app's liquid glass design. The goal is to make every part of the settings UI match the glassmorphism aesthetic from `glass.css`.

## Approach

Add CSS classes to `glass.css`, then update the Java code to use them — replacing inline styles and the solid dialog backgrounds with glassmorphism.

---

## Step 1: Add CSS classes to `glass.css`

Append these classes after the `.section-title` block (after line 396):

- **`.glass-dialog`** — frosted glass panel for dialog `Stage` roots: `rgba(13,14,17,0.85)` bg, `rgba(255,255,255,0.10)` border, 12px radius, drop shadow. More opaque than the main window pane since dialogs have no orb layer behind them.
- **`.glass-field` + `.glass-field:focused`** — form inputs with accent glow on focus (matches search-bar pattern)
- **`.glass-field-label`** — 11px/`0.50` alpha label for form field labels
- **`.section-header`** — 15px/`0.92` alpha for larger section titles (distinct from existing `.section-title` at `0.28`)
- **`.glass-combo`** — ComboBox glass styling
- **`.glass-table`** — TableView with glass header, accent-colored selection rows, hover states, transparent placeholder
- **`.glass-checkbox`** — CheckBox with glass box styling and accent selected state
- **`.glass-btn-primary`** — accent-filled button with hover glow + lift
- **`.glass-btn-secondary`** — ghost button with hover state

## Step 2: Update UI helper methods

In `SwissKitJSettingUi.java` lines 937-1016, update the factory methods to use CSS classes:

1. **`glassBtn()`** (line 992) — use `glass-btn-primary` / `glass-btn-secondary` classes instead of inline strings
2. **`fieldStyle()`** (line 977) — use `glass-field` class
3. **`comboStyle()`** (line 985) — use `glass-combo` class
4. **`sectionTitle()`** (line 952) — use `section-header` class
5. **`subLabel()`** (line 958) — use `glass-field-label` class
6. **`labeled()`** (line 941) — use `glass-field-label` class for the label
7. **`textField()`** (line 964) — apply `glass-field` class when no custom style
8. **`passwordField()`** (line 971) — apply `glass-field` class

## Step 3: Fix dialog windows (core change)

Each of the 3 dialogs needs its root VBox background changed and scene fill set to dark:

### Address Book dialog (line ~403)
- Line 465: replace `setStyle("-fx-background-color: #1c1f26;")` with `getStyleClass().add("glass-dialog")`
- Line 418: replace `setStyle("-fx-background-color: transparent;")` with `getStyleClass().add("glass-table")` on the TableView
- After scene creation: set scene fill to `#0d0e11` so glass-dialog blends

### Add/Edit Address dialog (line ~476)
- Line 599: replace `setStyle("-fx-background-color: #1c1f26;")` with `getStyleClass().add("glass-dialog")`
- After scene creation: set scene fill to `#0d0e11`
- Text fields and tagCombo automatically benefit from Step 2 helper changes

### Manage Tags dialog (line ~610)
- Line 708: replace `setStyle("-fx-background-color: #1c1f26;")` with `getStyleClass().add("glass-dialog")`
- Line 625: replace `setStyle("-fx-background-color: transparent;")` with `getStyleClass().add("glass-table")`
- After scene creation: set scene fill to `#0d0e11`

## Step 4: Polish main settings container

- Line 72-79: add `glass-dialog` class, simplify inline style
- Line 729: plugin TableView — use `glass-table` class
- Lines 293-297: TLS/SSL checkboxes — use `glass-checkbox` class

## Step 5: Build verification

Run Maven build to verify no compilation errors:
```bash
mvn compile -pl SwissKit -am -DskipTests
```

If the build succeeds, the changes are structurally correct.

---

## Files modified

1. `SwissKit/src/main/resources/css/glass.css` — append 8 new CSS class blocks
2. `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` — update helpers (lines 937-1016), dialogs (lines 403-717), container (lines 70-143)
