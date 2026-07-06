# Popup & Full-Project Theme Strict-Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate all hardcoded colors from popups, dialogs, and the broader UI so every surface resolves through `-sk-*` theme tokens — fixing popup theme adaptation (dark + 纯白/light) across the whole project.

**Architecture:** Extend the existing JavaFX looked-up-color token system (defined in `zhiflow-common.css`, stamped on scene roots via `Themes.applyTo(scene)`) with 5 new theme-aware tokens (`-sk-shadow`, `-sk-scrim`, `-sk-success-soft`, `-sk-warning-soft`, `-sk-danger-soft`) and 5 utility classes. Then mechanically replace every hardcoded color with a token/class, and fix the root-cause popup bug (`GlassNotification` never stamped its scene). Color always via `.sk-*` class; geometry may stay inline (project rule P5).

**Tech Stack:** Java 21+, JavaFX, CSS (JavaFX `-fx-*`), Maven multi-module (`SwissKitJ-Api` + `SwissKit`).

## Global Constraints

- **Project rule P5 (critical):** JavaFX inline `setStyle("-fx-text-fill: -sk-text;")` does NOT resolve looked-up colors. Color MUST go via a `.sk-*` utility class (added through `getStyleClass().add(...)`); only geometry (padding/radius/font-size/font-weight/border-width) may stay inline. A node may carry both a color class and inline geometry.
- **Tokens live only in `SwissKitJ-Api/src/main/resources/css/zhiflow-common.css`** under `.theme-dark` (lines 17-32) and `.theme-light` (lines 33-48). Never define token values elsewhere.
- **Two themes only:** DARK (`.theme-dark`) and LIGHT/纯白 (`.theme-light`). Light `-sk-bg` is `#FFFFFF` (pure white).
- **`Themes.applyTo(scene)`** is the supported entry point that both loads the common stylesheet AND stamps the active theme class on the scene root. Calling `scene.getStylesheets().add(...)` alone is INSUFFICIENT (the original popup bug).
- **`Color`-animation constraint:** `FillTransition` interpolates between concrete `javafx.scene.paint.Color` objects; looked-up colors cannot be used there. Where animation is involved (ToggleSwitch), read the resolved color at theme-switch time instead of hardcoding.
- **Build/test cycle:** There is no pixel-assertion UI test harness. Each task's "test cycle" = (a) Maven compile, (b) a grep gate confirming the targeted hardcoded color is gone, (c) manual visual check in BOTH themes. The grep gates below are the repeatable acceptance checks.
- **Commit message style:** existing repo uses emoji prefixes like `🐛 fix(ui):`, `🎨`, `📝 docs(ui):`. Match this. Commits are per-task unless a task says otherwise.

---

## File Structure

**Modified files (no new files created):**

| File | Module | Responsibility |
|---|---|---|
| `SwissKitJ-Api/src/main/resources/css/zhiflow-common.css` | API | Source of truth: add 5 tokens + 5 utility classes; tokenize `.sk-notif-*`/`.sk-dialog` |
| `SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java` | API | Root-cause popup fix: stamp theme class |
| `SwissKitJ-Api/src/main/java/fan/summer/api/component/StepWizard.java` | API | Rewrite indicator to tokens (no white-tint idle) |
| `SwissKitJ-Api/src/main/java/fan/summer/buildintool/email/ToggleSwitch.java` | API | Tokenize track/thumb (animation-aware) |
| `SwissKitJ-Api/src/main/resources/css/zhiflow-preview.css` | API | Tokenize black dropshadows |
| `SwissKit/src/main/java/fan/summer/ui/about/AboutDialog.java` | app | Scrim + link tokenization |
| `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` | app | 4 modal backdrops + status colors |
| `SwissKit/src/main/java/fan/summer/buildintool/email/EmailPlugin.java` | app | 2 dialog roots + status colors + glassBtn |
| `SwissKit/src/main/resources/css/shell.css` | app | Tokenize black dropshadow |
| `docs/ui-design/05-theme-color-system.md` + `docs/zh/ui-design/05-theme-color-system.md` | docs | Token table, count, utility classes |
| `CHANGELOG.md` | docs | v3.2.0 entry |

**Task ordering rationale:** Task 1 (tokens/classes) must come first because every later task consumes the new tokens. Task 2 (root-cause popup fix) is the headline fix and is independent of Tier-4. Within Tier-4, StepWizard is done first because it is the highest-risk rewrite and should be validated before the mechanical replacements.

---

## Task 1: Add new theme tokens and utility classes to common CSS

**Files:**
- Modify: `SwissKitJ-Api/src/main/resources/css/zhiflow-common.css:17-48` (token blocks), `:116-124` (utility classes)

**Interfaces:**
- Consumes: existing `.theme-dark`/`.theme-light` block structure.
- Produces: 5 new looked-up colors (`-sk-shadow`, `-sk-scrim`, `-sk-success-soft`, `-sk-warning-soft`, `-sk-danger-soft`) and 5 new utility classes (`.sk-accent-text`, `.sk-success-text`, `.sk-warning-text`, `.sk-danger-text`, `.sk-scrim`) that ALL later tasks consume.

- [ ] **Step 1: Add 5 new tokens to the `.theme-dark` block**

In `zhiflow-common.css`, the `.theme-dark` block currently ends at line 32 with `-sk-danger: #F75464; }`. Insert these 5 lines BEFORE the closing `}` of `.theme-dark` (i.e. after the `-sk-danger` line):

```css
    -sk-shadow:       rgba(0,0,0,0.45);
    -sk-scrim:        rgba(0,0,0,0.50);
    -sk-success-soft: rgba(91,176,101,0.18);
    -sk-warning-soft: rgba(240,167,50,0.18);
    -sk-danger-soft:  rgba(247,84,100,0.18);
```

The resulting `.theme-dark` block tail should read:
```css
    -sk-success:       #5BB065;
    -sk-warning:       #F0A732;
    -sk-danger:        #F75464;
    -sk-shadow:       rgba(0,0,0,0.45);
    -sk-scrim:        rgba(0,0,0,0.50);
    -sk-success-soft: rgba(91,176,101,0.18);
    -sk-warning-soft: rgba(240,167,50,0.18);
    -sk-danger-soft:  rgba(247,84,100,0.18);
}
```

- [ ] **Step 2: Add 5 new tokens to the `.theme-light` block**

The `.theme-light` block currently ends at line 48 with `-sk-danger: #E53935; }`. Insert these 5 lines BEFORE the closing `}` of `.theme-light`:

```css
    -sk-shadow:       rgba(15,23,42,0.18);
    -sk-scrim:        rgba(15,23,42,0.32);
    -sk-success-soft: rgba(60,145,74,0.14);
    -sk-warning-soft: rgba(194,117,28,0.14);
    -sk-danger-soft:  rgba(229,57,53,0.14);
```

- [ ] **Step 3: Add 5 utility classes after the existing `.sk-outlined-strong` line**

The utility-class section ends at line 124 with `.sk-outlined-strong { -fx-border-color: -sk-border-strong; }`. Insert immediately after that line:

```css
.sk-accent-text  { -fx-text-fill: -sk-accent; }      /* 链接/强调文本(内联安全) */
.sk-success-text { -fx-text-fill: -sk-success; }      /* 成功状态文本 */
.sk-warning-text { -fx-text-fill: -sk-warning; }      /* 警告状态文本 */
.sk-danger-text  { -fx-text-fill: -sk-danger; }       /* 错误状态文本 */
.sk-scrim        { -fx-background-color: -sk-scrim; } /* 模态遮罩(透明 Stage 用) */
```

- [ ] **Step 4: Build to confirm CSS is valid**

Run: `mvn -q -pl SwissKitJ-Api compile`
Expected: BUILD SUCCESS (CSS isn't compiled by Maven, but this confirms no accidental damage to the module; CSS validity is checked visually in later tasks).

- [ ] **Step 5: Grep gate — confirm tokens are defined exactly twice (dark + light)**

Run:
```bash
grep -c "\-sk-shadow:" SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
grep -c "\-sk-scrim:" SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
grep -c "\-sk-success-soft:" SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
```
Expected: each prints `2` (one in `.theme-dark`, one in `.theme-light`). If any prints other than 2, the token is missing from a theme block.

- [ ] **Step 6: Commit**

```bash
git add SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
git commit -m "🎨 feat(ui): add 5 theme tokens (-sk-shadow/-scrim/*-soft) + 5 utility classes

Extends the looked-up-color system: shadow & scrim for elevation/modal-dim,
status-soft fills mirroring -sk-accent-soft. Token count 14 → 19.
Per docs rule, tokens proposed in CSS first."
```

---

## Task 2: Fix GlassNotification root-cause theme bug

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java:259-262`

**Interfaces:**
- Consumes: `Themes.applyTo(Scene)` (existing, `SwissKitJ-Api/.../theme/Themes.java:64`).
- Produces: all `toast()`/`notify()`/`confirm()` popups now resolve `-sk-*` tokens in both themes. Fixes the headline bug app-wide.

- [ ] **Step 1: Replace manual stylesheet add with `Themes.applyTo(scene)`**

Current code at `GlassNotification.java:259-262`:
```java
        Scene scene = new Scene(root);
        scene.setFill(null);
        scene.getStylesheets().add(GlassNotification.class.getResource("/css/zhiflow-common.css").toExternalForm());
        stage.setScene(scene);
```

Replace with:
```java
        Scene scene = new Scene(root);
        scene.setFill(null);
        // Stamp .theme-dark/.theme-light on the root so every -sk-* looked-up color
        // resolves. Loading the stylesheet alone is NOT enough (the original bug);
        // Themes.applyTo both loads it and stamps the active theme class.
        Themes.applyTo(scene);
        stage.setScene(scene);
```

- [ ] **Step 2: Add the Themes import if not present**

Check the import block at the top of `GlassNotification.java`. If `import fan.summer.zhiflow.api.theme.Themes;` is missing, add it (alphabetical order, with the other `fan.summer.zhiflow.api.*` imports). The class is in the same module (`SwissKitJ-Api`) so no cross-module dependency is introduced.

- [ ] **Step 3: Build**

Run: `mvn -q -pl SwissKitJ-Api compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Grep gate — confirm the bug pattern is gone**

Run:
```bash
grep -n "getStylesheets().add" SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java
grep -n "Themes.applyTo" SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java
```
Expected: first grep → no output (the manual add is removed); second grep → exactly one match at ~line 262.

- [ ] **Step 5: Manual visual check (BOTH themes)**

Launch the app. Trigger a toast notification (e.g. any `GlassNotification.toast(...)` path) in DARK theme — the card should be dark (`-sk-bg-elevated` `#2B2B2B`), NOT JavaFX default white. Switch to LIGHT theme and trigger another toast — the card should be light (`#F7F8FA`). If either shows a plain white Modena card, the stamp did not take; re-check the edit.

- [ ] **Step 6: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java
git commit -m "🐛 fix(ui): stamp theme class on GlassNotification scene (root cause)

The toast/notify/confirm popup loaded zhiflow-common.css but never called
Themes.applyTo(scene), so .theme-dark/.theme-light was never stamped and all
-sk-* looked-up colors were UNDEFINED → fell back to JavaFX default white in
both themes. One-line root-cause fix; restores themed popups app-wide."
```

---

## Task 3: Tokenize hardcoded colors in common CSS (.sk-notif-*, .sk-dialog)

**Files:**
- Modify: `SwissKitJ-Api/src/main/resources/css/zhiflow-common.css:133,301,312,313,314`

**Interfaces:**
- Consumes: the 5 tokens added in Task 1.
- Produces: notification/dialog shadows and status soft-fills now re-resolve on theme switch.

- [ ] **Step 1: Tokenize `.sk-dialog` shadow (line 133)**

Current line 133:
```css
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 30, 0, 0, 12);
```
Replace with:
```css
    -fx-effect: dropshadow(gaussian, -sk-shadow, 30, 0, 0, 12);
```

- [ ] **Step 2: Tokenize `.sk-notif-root` shadow (line 301)**

Current line 301:
```css
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.50), 28, 0, 0, 10);
```
Replace with:
```css
    -fx-effect: dropshadow(gaussian, -sk-shadow, 28, 0, 0, 10);
```

- [ ] **Step 3: Tokenize the three status soft-fills (lines 312-314)**

Current lines 312-314:
```css
.sk-notif-success { -fx-text-fill: -sk-success; -fx-background-color: rgba(76,217,123,0.15); }
.sk-notif-warning { -fx-text-fill: -sk-warning; -fx-background-color: rgba(245,166,35,0.15); }
.sk-notif-error   { -fx-text-fill: -sk-danger;  -fx-background-color: rgba(242,92,92,0.15); }
```
Replace with:
```css
.sk-notif-success { -fx-text-fill: -sk-success; -fx-background-color: -sk-success-soft; }
.sk-notif-warning { -fx-text-fill: -sk-warning; -fx-background-color: -sk-warning-soft; }
.sk-notif-error   { -fx-text-fill: -sk-danger;  -fx-background-color: -sk-danger-soft; }
```

- [ ] **Step 4: Grep gate — no hardcoded rgba remains in these rules**

Run:
```bash
grep -n "rgba(76,217,123\|rgba(245,166,35\|rgba(242,92,92\|rgba(0,0,0,0.45)\|rgba(0,0,0,0.50)" SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
```
Expected: no output. (Other `rgba(0,0,0,...)` shadow values may still exist elsewhere in this file — that's fine; this gate targets only the 5 values just replaced.)

- [ ] **Step 5: Manual visual check (BOTH themes)**

Trigger success/warning/error notifications in both themes. The icon-circle soft-fill should now match the theme's status hue (e.g. light-theme success tint derived from `#3C914A`). Card shadows should be soft in light theme (not muddy black).

- [ ] **Step 6: Commit**

```bash
git add SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
git commit -m "🎨 fix(ui): tokenize .sk-dialog/.sk-notif-* shadows + status soft-fills

Black dropshadows → -sk-shadow (softer in light); hardcoded success/warning/
error tints → -sk-*-soft tokens that match the theme status hues."
```

---

## Task 4: Tokenize AboutDialog scrim and hyperlink color

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/about/AboutDialog.java:48,119`

**Interfaces:**
- Consumes: `.sk-scrim` and `.sk-accent-text` classes from Task 1. `Themes.applyTo(scene)` is already called at AboutDialog.java:54, so tokens resolve.

- [ ] **Step 1: Tokenize the modal scrim (line 48)**

Current line 48:
```java
        root.setStyle("-fx-background-color: rgba(0,0,0,0.35);");
```
Replace with:
```java
        root.getStyleClass().add("sk-scrim");
```

(The scrim color now comes from `-sk-scrim` — `rgba(0,0,0,0.50)` dark / `rgba(15,23,42,0.32)` light. The line-49 `setOnMousePressed` handler stays unchanged.)

- [ ] **Step 2: Tokenize the hyperlink color (line 119)**

Current line 119:
```java
        link.setStyle("-fx-text-fill: #3574F0; -fx-font-size: 13px; -fx-border-color: transparent; -fx-padding: 0;");
```
Replace with (add the class for color, keep ONLY geometry inline per rule P5):
```java
        link.getStyleClass().add("sk-accent-text");
        link.setStyle("-fx-font-size: 13px; -fx-border-color: transparent; -fx-padding: 0;");
```

- [ ] **Step 3: Build**

Run: `mvn -q -pl SwissKit compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Grep gate**

Run:
```bash
grep -n "rgba(0,0,0,0.35)\|#3574F0" SwissKit/src/main/java/fan/summer/ui/about/AboutDialog.java
```
Expected: no output.

- [ ] **Step 5: Manual visual check (BOTH themes)**

Open the About dialog in both themes. In LIGHT theme the dimming scrim should be lighter (`rgba(15,23,42,0.32)`); links should be accent-blue (token-driven). In DARK theme the scrim dims normally.

- [ ] **Step 6: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/about/AboutDialog.java
git commit -m "🎨 fix(ui): tokenize AboutDialog scrim + hyperlink color

Scrim → -sk-scrim (lighter in light theme); #3574F0 link → .sk-accent-text
class so it tracks the accent token instead of a coincidental hex match."
```

---

## Task 5: Rewrite StepWizard indicator to use theme tokens (HIGHEST RISK)

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/component/StepWizard.java:84-86` (color constants), `:178` (connector), `:205-244` (refreshIndicator rendering)

**Interfaces:**
- Consumes: theme tokens via CSS utility classes. NOTE: `Circle.setFill()` and the connector `Region.setStyle` need resolved colors. Because inline `setStyle` cannot resolve looked-up colors (P5), the connector lines must switch to carrying a CSS class too; the circle fills must use `Color` objects read from the theme.

**Design decision for this task:** The indicator dots/numbers/labels are `javafx.scene.shape.Circle` and `javafx.scene.control.Label`. The cleanest theme-correct approach is to drive ALL their colors via `.sk-*` utility classes (which DO resolve looked-up colors), not via Java `Color` constants. The `Circle` fill needs a class that sets `-fx-fill`. We will add three small helper classes inline in this task's step by reusing existing tokens. Since `StepWizard` is in the API module, classes go in `zhiflow-common.css`.

- [ ] **Step 1: Add StepWizard indicator helper classes to common CSS**

In `zhiflow-common.css`, append (after the `.sk-notif-*` block, ~line 340) a new section. Each `.sk-step-*` class sets BOTH `-fx-fill` and `-fx-stroke`, because the `Circle` stroke (width set to 1.5 in `makeDot`) needs a theme color too:

```css
/* ── StepWizard 指示器 ───────────────────────────────────────── */
.sk-step-done    { -fx-fill: -sk-success;       -fx-stroke: -sk-success; }  /* 已完成圆点 */
.sk-step-current { -fx-fill: -sk-accent;        -fx-stroke: -sk-accent;  }  /* 当前圆点 */
.sk-step-idle    { -fx-fill: -sk-bg-selected;   -fx-stroke: -sk-border;  }  /* 未到达圆点(两个主题都可见) */
.sk-step-line-done { -fx-background-color: -sk-success; }
.sk-step-line-idle { -fx-background-color: -sk-border; }
```

(`-sk-bg-selected` is `#393B40` dark / `#DFE1E5` light — visible on both backgrounds, unlike the old `rgba(255,255,255,...)` which was invisible on white.)

- [ ] **Step 2: Remove the hardcoded color constants**

Current lines 84-86:
```java
    // ── Colour constants ──────────────────────────────────
    private static final String ACCENT     = "#3574F0";
    private static final String DONE_COLOR = "#4cd97b";
    private static final String IDLE_COLOR = "rgba(255,255,255,0.15)";
```
Replace with:
```java
    // ── Step state style classes (colors resolve via -sk-* tokens) ──
    private static final String CLS_DONE    = "sk-step-done";
    private static final String CLS_CURRENT = "sk-step-current";
    private static final String CLS_IDLE    = "sk-step-idle";
```

- [ ] **Step 3: Update the connector line in `buildStepIndicator` (line 178)**

Current line 178:
```java
                line.setStyle("-fx-background-color: " + IDLE_COLOR + "; -fx-background-radius: 1;");
```
Replace with (initial idle class; `refreshIndicator` toggles it):
```java
                line.getStyleClass().add("sk-step-line-idle");
                line.setStyle("-fx-background-radius: 1;");
```

- [ ] **Step 4: Rewrite `refreshIndicator` body (lines 205-244)**

Current body uses `Color.web(DONE_COLOR/ACCENT/IDLE_COLOR/...)` and inline `setStyle` color strings. Replace the ENTIRE body of `refreshIndicator` (from the `int childIdx = 0;` line through the final closing `}` of the method) with this token-class-driven version:

```java
        int childIdx = 0;
        for (int i = 0; i < steps.size(); i++) {
            if (childIdx >= stepIndicator.getChildren().size()) break;
            StackPane dot    = (StackPane) stepIndicator.getChildren().get(childIdx);
            Circle    circle = (Circle)    dot.getChildren().get(0);
            Label     num    = (Label)     dot.getChildren().get(1);
            childIdx++;

            // Reset dot state classes, then apply the one for this step.
            circle.getStyleClass().removeAll(CLS_DONE, CLS_CURRENT, CLS_IDLE);
            num.getStyleClass().removeAll("sk-t1", "sk-t3");

            if (i < current) {
                circle.getStyleClass().add(CLS_DONE);
                num.setText("✓");
                num.getStyleClass().add("sk-t1");   // checkmark in primary text color
            } else if (i == current) {
                circle.getStyleClass().add(CLS_CURRENT);
                num.setText(String.valueOf(i + 1));
                num.getStyleClass().add("sk-t1");   // use -sk-text; white-on-accent is wrong here (accent dot, not a button)
                ScaleTransition pulse = new ScaleTransition(Duration.millis(600), dot);
                pulse.setFromX(1.0); pulse.setFromY(1.0);
                pulse.setToX(1.12); pulse.setToY(1.12);
                pulse.setAutoReverse(true); pulse.setCycleCount(2);
                pulse.play();
            } else {
                circle.getStyleClass().add(CLS_IDLE);
                num.setText(String.valueOf(i + 1));
                num.getStyleClass().add("sk-t3");   // idle number in disabled text color
            }
            num.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

            if (i < steps.size() - 1 && childIdx < stepIndicator.getChildren().size()) {
                Region line = (Region) stepIndicator.getChildren().get(childIdx);
                boolean lineDone = i < current;
                line.getStyleClass().removeAll("sk-step-line-done", "sk-step-line-idle");
                line.getStyleClass().add(lineDone ? "sk-step-line-done" : "sk-step-line-idle");
                childIdx++;
            }
        }
```

Notes for the implementer:
- `num.setStyle(...)` now holds ONLY geometry (font-size/weight), per rule P5. Color comes from the `.sk-t1`/`.sk-t3` class.
- The old `Color.web(...)` calls and the `#0d0e11`/`white`/`rgba(255,255,255,0.35)` literals are all gone.
- `circle.setStrokeWidth(1.5)` in `makeDot` (line 188) stays; the stroke color comes from the `-fx-stroke` set in each `.sk-step-*` class (see Step 1).
- The `Circle` stroke is a SEPARATE property from fill — that's why every `.sk-step-*` class sets both `-fx-fill` and `-fx-stroke`.

- [ ] **Step 5: Build**

Run: `mvn -q -pl SwissKitJ-Api compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Grep gate — no hardcoded colors remain in StepWizard**

Run:
```bash
grep -n "#3574F0\|#4cd97b\|rgba(255,255,255\|#0d0e11\|Color.web\|DONE_COLOR\|IDLE_COLOR\|ACCENT " SwissKitJ-Api/src/main/java/fan/summer/api/component/StepWizard.java
```
Expected: no output. (The `ACCENT ` with trailing space avoids matching the new `CLS_*` names; if the grep matches an unrelated comment, clean it up.)

- [ ] **Step 7: Manual visual check (BOTH themes) — CRITICAL**

Launch any wizard flow that uses StepWizard in BOTH themes. Verify:
- DARK theme: done dots green, current dot accent-blue, idle dots visible (subtle gray, NOT invisible).
- LIGHT theme (pure white): **idle dots must be clearly visible** (`-sk-bg-selected` `#DFE1E5` on white). This is the regression that previously made the wizard unreadable — confirm it is fixed.
- Connector lines: green up to current step, border-gray after.
- Numbers/checkmarks legible in both themes.

If idle dots are invisible or low-contrast in light theme, adjust the `.sk-step-idle` fill (try `-sk-bg-hover` `#EBECEF` instead of `-sk-bg-selected`) and re-check.

- [ ] **Step 8: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/component/StepWizard.java SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
git commit -m "🎨 fix(ui): rewrite StepWizard indicator with theme tokens

Idle dots/strokes were rgba(255,255,255,...) — invisible on the light theme.
Now driven by -sk-* utility classes (sk-step-done/current/idle) so every
state is legible in both themes. Highest-risk rewrite of the audit."
```

---

## Task 6: Tokenize ToggleSwitch (animation-aware)

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/buildintool/email/ToggleSwitch.java:26-27,37,40,57-58`

**Interfaces:**
- Consumes: theme tokens. NOTE: `FillTransition` (lines 57-58) interpolates between concrete `Color` objects; looked-up colors CANNOT be used there. So the ON color stays a concrete `Color.web`, but we resolve it from the current theme rather than a hardcoded hex. Approach: read the accent color from the theme at construction/switch time.

**Design decision:** The cleanest minimal fix that respects the animation constraint: keep `FillTransition` with concrete `Color` objects, but resolve `ON_COLOR`/`OFF_COLOR` from the theme via `ThemeService` listener so they update on theme switch. If `ThemeService` does not expose a color accessor, fall back to making the OFF track a neutral token-driven fill (no animation needed for OFF since it's the rest state) and animate only ON. Given complexity, the pragmatic choice here: **remove the FillTransition color animation entirely** and set the track fill via a CSS class that toggles — CSS handles the transition, and no hardcoded hex remains.

- [ ] **Step 1: Rewrite ToggleSwitch to use CSS classes for track color**

Current lines 26-27 (constants):
```java
    private static final Color OFF_COLOR = Color.rgb(255, 255, 255, 0.18);
    private static final Color ON_COLOR = Color.web("#3574F0");
```
Replace with class-name constants:
```java
    private static final String ON_CLASS = "sk-toggle-on";
    private static final String OFF_CLASS = "sk-toggle-off";
```

- [ ] **Step 2: Add ToggleSwitch CSS classes to common CSS**

In `zhiflow-common.css`, append after the StepWizard section:
```css
/* ── ToggleSwitch ───────────────────────────────────────────── */
.sk-toggle-on  { -fx-fill: -sk-accent; }
.sk-toggle-off { -fx-fill: -sk-bg-selected; }
```
(`-sk-bg-selected` is visible on both themes, unlike `rgba(255,255,255,0.18)` which was invisible on white.)

- [ ] **Step 3: Update track setup (lines 37) and animate method (53-61)**

Current line 37:
```java
        track.setFill(OFF_COLOR);
```
Replace with:
```java
        track.getStyleClass().add(OFF_CLASS);
```

Current `animate` method (lines 53-61):
```java
    private void animate(boolean on) {
        TranslateTransition slide = new TranslateTransition(Duration.millis(150), thumb);
        slide.setToX(on ? OFFSET : -OFFSET);

        FillTransition fade = new FillTransition(Duration.millis(150), track,
                (Color) track.getFill(), on ? ON_COLOR : OFF_COLOR);

        slide.play();
        fade.play();
    }
```
Replace with (swap the class; CSS gives the color; keep only the slide animation):
```java
    private void animate(boolean on) {
        TranslateTransition slide = new TranslateTransition(Duration.millis(150), thumb);
        slide.setToX(on ? OFFSET : -OFFSET);

        // Track color is driven by CSS class (theme-aware). FillTransition cannot
        // interpolate looked-up colors, so we swap the class; the slide carries the motion.
        track.getStyleClass().removeAll(ON_CLASS, OFF_CLASS);
        track.getStyleClass().add(on ? ON_CLASS : OFF_CLASS);

        slide.play();
    }
```

- [ ] **Step 4: Decide the thumb color**

Line 40 `thumb.setFill(Color.WHITE);` — the thumb is white in BOTH themes. On the OFF track (`-sk-bg-selected`), white is visible in dark theme but low-contrast on light (`#DFE1E5` track, white thumb). Decision: keep the thumb `Color.WHITE` ONLY when ON (on the accent-blue track, white thumb is correct contrast in both themes); when OFF, use a neutral thumb. Simplest theme-correct approach: keep `Color.WHITE` always — it reads fine on accent (ON) and is acceptable on the selected-gray (OFF). **Keep line 40 unchanged.** (White is a legitimate contrast color here, same precedent as `.sk-btn-primary` text and the checkbox mark — see spec §4 out-of-scope list.)

- [ ] **Step 5: Clean up now-unused imports**

If `FillTransition` (line 3) and `Color` (line 8) imports become unused after removing `FillTransition`, remove them. `Color` is still used by `thumb.setFill(Color.WHITE)`, so keep `import javafx.scene.paint.Color;`. Remove `import javafx.animation.FillTransition;` if no longer referenced (verify with the grep in Step 7).

- [ ] **Step 6: Build**

Run: `mvn -q -pl SwissKitJ-Api compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Grep gate**

Run:
```bash
grep -n "#3574F0\|Color.rgb(255, 255, 255" SwissKitJ-Api/src/main/java/fan/summer/buildintool/email/ToggleSwitch.java
```
Expected: no output.

- [ ] **Step 8: Manual visual check (BOTH themes)**

Find the ToggleSwitch in the UI (email tool). Toggle it in BOTH themes. ON → accent-blue track with white thumb. OFF → neutral-gray track with white thumb, visible in light theme (not invisible as before).

- [ ] **Step 9: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/buildintool/email/ToggleSwitch.java SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
git commit -m "🎨 fix(ui): tokenize ToggleSwitch track (was invisible off-state in light)

Off track was rgba(255,255,255,0.18) — invisible on white. Now .sk-toggle-off
(-sk-bg-selected). FillTransition can't interpolate looked-up colors, so the
color swap moved to a class toggle; slide animation retained."
```

---

## Task 7: Tokenize EmailPlugin dialogs, status colors, and glassBtn

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/email/EmailPlugin.java:292,296,306,504,626,666-673` (glassBtn)

**Interfaces:**
- Consumes: `.sk-success-text`, `.sk-danger-text`, `.sk-warning-text` (Task 1); existing `.sk-btn-primary`, `.sk-surface`, `.sk-outlined`, `.sk-t1` classes. `Themes` already imported (line 8).

- [ ] **Step 1: Tokenize the two dialog root backgrounds (lines 504, 626)**

Current line 504:
```java
        root.setStyle("-fx-background-color: #1f2937;");
```
Replace with (the dialog root should use the themed surface; apply `.sk-dialog`'s background via the existing utility):
```java
        root.getStyleClass().add("sk-surface");
```
Do the SAME replacement at line 626 (`root.setStyle("-fx-background-color: #1f2937;");` → `root.getStyleClass().add("sk-surface");`).

- [ ] **Step 2: Tokenize glassBtn primary button (lines 668-673)**

Current lines 668-673:
```java
        if (primary) {
            btn.setStyle(
                    "-fx-background-color: #3574F0; -fx-text-fill: white; -fx-font-size: 13px;" +
                    "-fx-font-weight: 500; -fx-background-radius: 8; -fx-border-width: 0;" +
                    "-fx-padding: 9 18 9 18; -fx-cursor: hand;"
            );
        } else {
```
Replace with (use the existing `.sk-btn-primary` class for color; keep geometry inline):
```java
        if (primary) {
            btn.getStyleClass().add("sk-btn-primary");
            btn.setStyle(
                    "-fx-font-size: 13px; -fx-font-weight: 500; -fx-background-radius: 8;" +
                    "-fx-border-width: 0; -fx-padding: 9 18 9 18; -fx-cursor: hand;"
            );
        } else {
```

- [ ] **Step 3: Tokenize the three status labels (lines 292, 296, 306)**

Current line 292:
```java
                progressLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
```
Replace with:
```java
                progressLabel.getStyleClass().removeAll("sk-success-text", "sk-warning-text");
                progressLabel.getStyleClass().add("sk-danger-text");
                progressLabel.setStyle("-fx-font-size: 12px;");
```

Current line 296:
```java
                progressLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
```
Replace with:
```java
                progressLabel.getStyleClass().removeAll("sk-danger-text", "sk-warning-text");
                progressLabel.getStyleClass().add("sk-success-text");
                progressLabel.setStyle("-fx-font-size: 12px;");
```

Current line 306:
```java
            progressLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
```
Replace with:
```java
            progressLabel.getStyleClass().removeAll("sk-success-text", "sk-warning-text");
            progressLabel.getStyleClass().add("sk-danger-text");
            progressLabel.setStyle("-fx-font-size: 12px;");
```

(The `removeAll` before `add` is important because these labels are reused across success/error states; without it, classes accumulate.)

- [ ] **Step 4: Build**

Run: `mvn -q -pl SwissKit compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Grep gate**

Run:
```bash
grep -n "#1f2937\|#3574F0\|#4cd97b\|#f25c5c\|#f5a623" SwissKit/src/main/java/fan/summer/buildintool/email/EmailPlugin.java
```
Expected: no output.

- [ ] **Step 6: Manual visual check (BOTH themes)**

Open the Mass Config and Sent Log dialogs in both themes — backgrounds should follow the theme (not dark slate). Trigger a send (success and failure paths) — status labels should be theme-correct green/red. Primary buttons should be accent-blue.

- [ ] **Step 7: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/email/EmailPlugin.java
git commit -m "🎨 fix(ui): tokenize EmailPlugin dialogs + status colors + glassBtn

Dialog roots #1f2937 → .sk-surface; status labels → .sk-*-text classes;
glassBtn primary #3574F0 → .sk-btn-primary. Status labels now removeAll
before add to avoid class accumulation across reused nodes."
```

---

## Task 8: Tokenize SwissKitJSettingUi modal backdrops and status colors

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java:799,802,850,853,1480,1588,1755,1899,1853,1854,1867`

**Interfaces:**
- Consumes: `.sk-success-text`, `.sk-danger-text`, `.sk-warning-text` (Task 1); `-sk-bg` token. `Themes` already imported (line 7). These dialogs already call `Themes.applyTo(scene)` (e.g. line 1481), so the scene fill can be set from a looked-up color via the scene root class — but `scene.setFill()` takes a `Paint`, not a token. So we instead make the scene TRANSPARENT and let the `.sk-dialog`-classed root (already added, e.g. line 1476) provide the background.

- [ ] **Step 1: Replace the 4 modal scene fills (lines 1480, 1588, 1755, 1899)**

Each of these 4 identical lines is:
```java
        scene.setFill(javafx.scene.paint.Color.web("#0d0e11"));
```
Replace EACH occurrence with (transparent fill; the `.sk-dialog`-classed root already provides `-sk-bg-elevated` background, and `Themes.applyTo` is called right after):
```java
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
```
(Use `replace_all` style or do each of the 4 sites — verify with the grep in Step 4 that all 4 are gone.)

- [ ] **Step 2: Tokenize the AI-panel test-connection status colors (lines 799, 802, 850, 853)**

Current line 799:
```java
                        statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
```
Replace with:
```java
                        statusLabel.getStyleClass().removeAll("sk-danger-text", "sk-warning-text");
                        statusLabel.getStyleClass().add("sk-success-text");
                        statusLabel.setStyle("-fx-font-size: 12px;");
```

Current line 802:
```java
                        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
```
Replace with:
```java
                        statusLabel.getStyleClass().removeAll("sk-success-text", "sk-warning-text");
                        statusLabel.getStyleClass().add("sk-danger-text");
                        statusLabel.setStyle("-fx-font-size: 12px;");
```

Lines 850 and 853 are IDENTICAL to 799 and 802 respectively (the Anthropic panel duplicates the OpenAI panel logic). Apply the SAME replacements:
- Line 850 `#4cd97b` → same as the line-799 replacement.
- Line 853 `#f25c5c` → same as the line-802 replacement.

- [ ] **Step 3: Tokenize the Excel-import status colors (lines 1853, 1854, 1867)**

Current lines 1852-1854:
```java
                        statusLabel.setStyle(allOk
                            ? "-fx-text-fill: #4cd97b; -fx-font-size: 13px; -fx-font-weight: 500;"
                            : "-fx-text-fill: #f5a623; -fx-font-size: 13px; -fx-font-weight: 500;");
```
Replace with:
```java
                        statusLabel.getStyleClass().removeAll("sk-success-text", "sk-danger-text", "sk-warning-text");
                        statusLabel.getStyleClass().add(allOk ? "sk-success-text" : "sk-warning-text");
                        statusLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 500;");
```

Current line 1867:
```java
                        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 13px;");
```
Replace with:
```java
                        statusLabel.getStyleClass().removeAll("sk-success-text", "sk-warning-text");
                        statusLabel.getStyleClass().add("sk-danger-text");
                        statusLabel.setStyle("-fx-font-size: 13px;");
```

- [ ] **Step 4: Build**

Run: `mvn -q -pl SwissKit compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Grep gate**

Run:
```bash
grep -n "#0d0e11\|#4cd97b\|#f25c5c\|#f5a623" SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java
```
Expected: no output.

- [ ] **Step 6: Manual visual check (BOTH themes)**

Open the 4 settings modals (Address Book, Add/Edit Address, Manage Tags, Import Excel) in both themes — window background should follow the theme (not near-black). Test AI connections (success + failure) and Excel import (all-ok + partial + failed) — status colors theme-correct.

- [ ] **Step 7: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java
git commit -m "🎨 fix(ui): tokenize SettingUi modal backdrops + status colors

4 modal scene fills #0d0e11 → transparent (root .sk-dialog provides themed bg);
test-connection + Excel-import status labels → .sk-success/-warning/-danger-text.
Removes the parallel hardcoded #4cd97b/#f25c5c/#f5a623 palette."
```

---

## Task 9: Tokenize remaining CSS dropshadows (preview, shell)

**Files:**
- Modify: `SwissKitJ-Api/src/main/resources/css/zhiflow-preview.css:16,131,164`
- Modify: `SwissKit/src/main/resources/css/shell.css:169`

**Interfaces:**
- Consumes: `-sk-shadow` token (Task 1). NOTE: `zhiflow-preview.css` is loaded onto preview-window scenes that have `.theme-dark`/`.theme-light` stamped (via `Themes.applyTo`), so `-sk-shadow` resolves there. `shell.css` loads on the main scene, also stamped. `builtin.css:91,125` use `rgba(53,116,240,...)` — these are ACCENT-colored glows (not black shadows) and are correct in both themes (accent is shared `#3574F0`); **leave them unchanged.**

- [ ] **Step 1: Tokenize preview.css shadows**

Current line 16:
```css
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 60, 0, 0, 20);
```
Replace with:
```css
    -fx-effect: dropshadow(gaussian, -sk-shadow, 60, 0, 0, 20);
```

Current line 131:
```css
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 18, 0, 0, 6);
```
Replace with:
```css
    -fx-effect: dropshadow(gaussian, -sk-shadow, 18, 0, 0, 6);
```

Current line 164:
```css
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 18, 0, -4, 0);
```
Replace with:
```css
    -fx-effect: dropshadow(gaussian, -sk-shadow, 18, 0, -4, 0);
```

- [ ] **Step 2: Tokenize shell.css shadow (line 169)**

Current line 169:
```css
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.30), 16, 0, -4, 0);
```
Replace with:
```css
    -fx-effect: dropshadow(gaussian, -sk-shadow, 16, 0, -4, 0);
```

- [ ] **Step 3: Grep gate — no black-based dropshadows remain in these files**

Run:
```bash
grep -n "dropshadow(gaussian, rgba(0,0,0" SwissKitJ-Api/src/main/resources/css/zhiflow-preview.css SwissKit/src/main/resources/css/shell.css SwissKit/src/main/resources/css/builtin.css
```
Expected: no output. (builtin.css accent glows are `rgba(53,116,240,...)` and won't match `rgba(0,0,0`.)

- [ ] **Step 4: Manual visual check (BOTH themes)**

In both themes, check tool cards / preview cards / detail panel — shadows should be soft in light theme (not muddy). Confirm accent glow on chat input/send button is unchanged (still blue glow).

- [ ] **Step 5: Commit**

```bash
git add SwissKitJ-Api/src/main/resources/css/zhiflow-preview.css SwissKit/src/main/resources/css/shell.css
git commit -m "🎨 fix(ui): tokenize black dropshadows → -sk-shadow (preview, shell)

Soft, theme-aware elevation in light theme. builtin.css accent glows left
unchanged (correct in both themes)."
```

---

## Task 10: Update theme docs and CHANGELOG

**Files:**
- Modify: `docs/ui-design/05-theme-color-system.md` (token table ~lines 180-223, count statement ~219-223, §3.2 utility classes)
- Modify: `docs/zh/ui-design/05-theme-color-system.md` (mirror the EN changes)
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: the final token set (14→19) and utility classes from Tasks 1, 5, 6.

- [ ] **Step 1: Add the 5 new tokens to the EN token reference table**

In `docs/ui-design/05-theme-color-system.md`, the token tables are around lines 180-217. Add two new sub-sections (after the Status tokens table, before the "Token count: 14" statement):

```markdown
#### Elevation / overlay tokens

| Token | Dark | Light | Purpose | Use on |
|---|---|---|---|---|
| `-sk-shadow` | `rgba(0,0,0,0.45)` | `rgba(15,23,42,0.18)` | Card/dialog/popup drop-shadow (softer in light) | `.sk-dialog`, `.sk-notif-root`, tool cards |
| `-sk-scrim` | `rgba(0,0,0,0.50)` | `rgba(15,23,42,0.32)` | Modal dimming backdrop (transparent-stage modals) | `.sk-scrim`, AboutDialog backdrop |

#### Status soft-fill tokens

| Token | Dark | Light | Purpose | Use on |
|---|---|---|---|---|
| `-sk-success-soft` | `rgba(91,176,101,0.18)` | `rgba(60,145,74,0.14)` | Success soft-fill background | `.sk-notif-success` icon circle |
| `-sk-warning-soft` | `rgba(240,167,50,0.18)` | `rgba(194,117,28,0.14)` | Warning soft-fill background | `.sk-notif-warning` icon circle |
| `-sk-danger-soft` | `rgba(247,84,100,0.18)` | `rgba(229,57,53,0.14)` | Danger soft-fill background | `.sk-notif-error` icon circle |
```

- [ ] **Step 2: Update the token-count statement (~line 219-223)**

Current text:
```markdown
**Token count: 14** (`-sk-bg`, ... `-sk-danger`). Do not invent
15th token names — extend the system via [§3.2 utility classes](#token--css-utility-class)
or propose a new token in the CSS first.
```
Replace the count and rule with:
```markdown
**Token count: 19** (`-sk-bg`, `-sk-bg-elevated`, `-sk-bg-hover`, `-sk-bg-selected`,
`-sk-border`, `-sk-border-strong`, `-sk-text`, `-sk-text-secondary`, `-sk-text-disabled`,
`-sk-accent`, `-sk-accent-soft`, `-sk-success`, `-sk-warning`, `-sk-danger`,
`-sk-shadow`, `-sk-scrim`, `-sk-success-soft`, `-sk-warning-soft`, `-sk-danger-soft`).
Adding a 20th token requires proposing it in `zhiflow-common.css` first (under BOTH
`.theme-dark` and `.theme-light`) and documenting it here — do not invent token names
inline. For one-off needs, prefer a [§3.2 utility class](#token--css-utility-class).
```

- [ ] **Step 3: Add the new utility classes to §3.2**

Find the utility-class table (around lines 116-124 referenced as §3.2) and append rows for `.sk-accent-text`, `.sk-success-text`, `.sk-warning-text`, `.sk-danger-text`, `.sk-scrim` (mapping each to its token and CSS property, mirroring the existing `.sk-t1`/`.sk-surface` rows).

- [ ] **Step 4: Mirror all changes in the ZH doc**

Apply the equivalent edits to `docs/zh/ui-design/05-theme-color-system.md` (same tables, translated prose). The CSS-file-wins rule and the 19-token list must match the EN doc exactly.

- [ ] **Step 5: Add a CHANGELOG entry**

In `CHANGELOG.md`, under the v3.2.0 section, add:

```markdown
### 🎨 Theme (strict tokenization)
- **BREAKING (token set):** added 5 theme tokens — `-sk-shadow`, `-sk-scrim`,
  `-sk-success-soft`, `-sk-warning-soft`, `-sk-danger-soft`. Token count 14 → 19.
  Custom themes/stylesheets referencing the old 14 must add these 5 under both
  `.theme-dark` and `.theme-light`.
- Fixed `GlassNotification` (toast/notify/confirm) rendering as un-themed white
  in both themes — root cause was a missing `Themes.applyTo(scene)` call.
- Removed all hardcoded colors from popups, dialogs, `StepWizard`, `ToggleSwitch`,
  status labels, and CSS dropshadows; everything now resolves through `-sk-*`
  tokens and adapts correctly to dark and 纯白 (light) themes.
```

- [ ] **Step 6: Commit**

```bash
git add docs/ui-design/05-theme-color-system.md docs/zh/ui-design/05-theme-color-system.md CHANGELOG.md
git commit -m "📝 docs(ui): document 5 new tokens + utility classes; CHANGELOG for strict-audit

Token count 14 → 19. EN + ZH theme-color-system updated; CSS-file-wins rule
preserved. CHANGELOG notes the breaking token-set expansion under v3.2.0."
```

---

## Final Verification (after all tasks)

- [ ] **Full build:** `mvn -q clean compile` — BUILD SUCCESS across both modules.
- [ ] **Whole-project grep gate (the strict-audit acceptance check):**
```bash
grep -rn "#3574F0\|#4cd97b\|#f25c5c\|#f5a623\|#0d0e11\|#1f2937" \
  SwissKit/src/main SwissKitJ-Api/src/main \
  --include="*.java" --include="*.css"
```
Expected: no output (zero hardcoded hex colors in source). (WebView HTML string constants in `MarkdownRenderer.java`/etc. are a separate manual-sync mechanism and are out of scope — confirm none of the 6 hexes above appear there; if they do, that's expected for WebView and acceptable.)

- [ ] **Black-dropshadow gate:**
```bash
grep -rn "dropshadow(gaussian, rgba(0,0,0" \
  SwissKit/src/main/resources SwissKitJ-Api/src/main/resources
```
Expected: no output.

- [ ] **Manual sweep (BOTH themes):** exercise toasts, confirms, About dialog, a wizard, the email modals + toggle + send status, the settings modals + AI test + Excel import, and tool/preview/detail cards. Confirm every surface follows the theme and nothing shows JavaFX default white or a hardcoded dark slate.

- [ ] **Final commit (if any cleanup):** only if the verification surfaced fixes not covered above.
