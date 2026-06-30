# IDEA 2025 New UI Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-skin SwissKitJ from glassmorphism-dark to JetBrains IDEA 2025 New UI, with switchable dark/light themes, a collapsible sidebar, and native window chrome.

**Architecture:** JavaFX looked-up color tokens (`-sk-*`) defined per theme (`.theme-dark`/`.theme-light`) on the scene root; a `ThemeService` (API module) swaps the root class to switch themes with no stylesheet reload. Native `StageStyle.DECORATED` replaces the custom transparent window + `WindowResizeHelper`. CSS utility classes rename `.glass-*` → `.sk-*`.

**Tech Stack:** JavaFX 21, MyBatis + H2 (settings persistence), CommonMark (MarkdownRenderer WebView CSS), JUnit Jupiter (unit tests). Build via IntelliJ IDEA's built-in Maven only — **no system `mvn`** (see CLAUDE.md). Run tests through IDEA Maven tool window (SwissKitJ-Api → Lifecycle → test) or `mcp__idea__execute_terminal_command`.

**Spec:** `docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`
**Branch:** `v3.2.0` (already created, at `28bf328`)

---

## File Structure

**Create:**
- `SwissKitJ-Api/src/main/java/fan/summer/api/theme/ThemeService.java` — theme state holder + scene registration + listeners (no DB dependency)
- `SwissKitJ-Api/src/test/java/fan/summer/api/theme/ThemeServiceTest.java` — unit tests

**Modify:**
- `SwissKitJ-Api/src/main/java/fan/summer/api/theme/Themes.java` — `applyTo` delegates to `ThemeService.registerScene`
- `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` — token defs + rename `.glass-*`→`.sk-*` + flat IDEA restyle
- `SwissKit/src/main/resources/css/shell.css` — full New UI restyle, token-based
- `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` — `DECORATED`, drop transparent fill + `WindowResizeHelper`, read+apply theme at startup
- `SwissKit/src/main/java/fan/summer/ui/MainWindow.java` — drop orb layer / clip / top-highlight / `TitleBar`; slim entry animation
- `SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java` — collapsible (label ⇄ icon-strip) + footer (settings/about/theme toggle)
- `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` — expose `saveThemeSetting`; theme row in settings page
- `SwissKit/src/main/java/fan/summer/ai/util/MarkdownRenderer.java` — dark + light CSS, `render(md, Theme)`
- `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java` — theme-driven WebView bg + `onChange` re-render
- API + SwissKit Java files referencing `.glass-*` (rename to `.sk-*`): `StepWizard` (API), `EmailPlugin`, `PdfToolPlugin`, `OnlineStorePane`, `AboutDialog`

**Delete:**
- `SwissKit/src/main/java/fan/summer/ui/titlebar/TitleBar.java`
- `SwissKit/src/main/java/fan/summer/ui/util/WindowResizeHelper.java`

---

## Phase 1 — Theme Infrastructure (no visual change in dark)

### Task 1.1: Create `ThemeService` + unit test

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/theme/ThemeService.java`
- Create: `SwissKitJ-Api/src/test/java/fan/summer/api/theme/ThemeServiceTest.java`

- [ ] **Step 1: Write the failing test**

`SwissKitJ-Api/src/test/java/fan/summer/api/theme/ThemeServiceTest.java`:
```java
package fan.summer.api.theme;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ThemeServiceTest {

    @Test
    void defaultThemeIsDark() {
        ThemeService.set(ThemeService.Theme.DARK); // reset
        assertEquals(ThemeService.Theme.DARK, ThemeService.current());
    }

    @Test
    void setNotifiesListeners() {
        AtomicReference<ThemeService.Theme> seen = new AtomicReference<>();
        ThemeService.onChange(seen::set);
        ThemeService.set(ThemeService.Theme.LIGHT);
        assertEquals(ThemeService.Theme.LIGHT, seen.get());
        assertEquals(ThemeService.Theme.LIGHT, ThemeService.current());
        // restore
        ThemeService.set(ThemeService.Theme.DARK);
    }

    @Test
    void setNullIsNoOp() {
        ThemeService.set(ThemeService.Theme.DARK);
        ThemeService.set(null);
        assertEquals(ThemeService.Theme.DARK, ThemeService.current());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run via IDEA Maven (SwissKitJ-Api → test) or `mcp__idea__execute_terminal_command`.
Expected: compile error — `ThemeService` does not exist.

- [ ] **Step 3: Write the implementation**

`SwissKitJ-Api/src/main/java/fan/summer/api/theme/ThemeService.java`:
```java
package fan.summer.api.theme;

import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds the current UI theme (DARK / LIGHT), applies it to registered scenes
 * via a style class on the scene root, and notifies listeners on change.
 *
 * <p>This class lives in the API module and has no database dependency. The
 * host application is responsible for loading/persisting the user's choice
 * and calling {@link #set(Theme)}. Looked-up color tokens ({@code -sk-*}) are
 * defined per theme in {@code swisskit-common.css} under {@code .theme-dark}
 * and {@code .theme-light}; swapping the root class re-resolves every token.
 *
 * @since 3.2.0
 */
public final class ThemeService {

    /** Supported themes. */
    public enum Theme { DARK, LIGHT }

    private static volatile Theme current = Theme.DARK;

    private static final List<Scene> SCENES = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Theme>> LISTENERS = new CopyOnWriteArrayList<>();

    private ThemeService() {}

    /** @return the currently active theme (never null). */
    public static Theme current() { return current; }

    /**
     * Switches the active theme: re-stamps the theme class on every registered
     * scene root and fires listeners. {@code null} is ignored.
     */
    public static void set(Theme theme) {
        if (theme == null) return;
        current = theme;
        String cls = (theme == Theme.DARK) ? "theme-dark" : "theme-light";
        for (Scene s : SCENES) {
            if (s.getRoot() != null) applyClass(s.getRoot(), cls);
        }
        for (Consumer<Theme> l : LISTENERS) {
            try { l.accept(theme); } catch (Exception ignored) { /* listener faults must not break switching */ }
        }
    }

    /**
     * Loads the common stylesheet into the scene (delegates to {@link Themes})
     * and stamps the current theme class on its root. Idempotent.
     */
    public static void registerScene(Scene scene) {
        if (scene == null) return;
        Themes.applyTo(scene);
        if (!SCENES.contains(scene)) SCENES.add(scene);
        if (scene.getRoot() != null) {
            applyClass(scene.getRoot(), current == Theme.DARK ? "theme-dark" : "theme-light");
        }
    }

    /** Registers a listener fired on every {@link #set(Theme)} call. */
    public static void onChange(Consumer<Theme> listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    private static void applyClass(Parent root, String themeClass) {
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add(themeClass);
    }
}
```

If the build fails because `junit-jupiter` is not on the API module test classpath, add to `SwissKitJ-Api/pom.xml` `<dependencies>`:
```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.2</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Run test to verify it passes**

Run SwissKitJ-Api tests via IDEA Maven.
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**
```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/theme/ThemeService.java \
        SwissKitJ-Api/src/test/java/fan/summer/api/theme/ThemeServiceTest.java \
        SwissKitJ-Api/pom.xml
git commit -m "✨ feat(theme): add ThemeService (dark/light switch, scene registration)"
```

---

### Task 1.2: Add token definitions to `swisskit-common.css`

**Files:**
- Modify: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (replace the `.root { ... }` block at lines 12–39)

- [ ] **Step 1: Replace the existing `.root` token block**

Replace the block currently starting at `.root {` (lines 12–39) with:
```css
/* ── 主题 token(looked-up color)────────────────────────────────
   在 Scene 根节点上以 .theme-dark / .theme-light 形式声明;
   切主题 = ThemeService 换根上的 class,子节点自动重算。      */
.root {
    -fx-font-family: "SF Pro Text", "Inter", "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    -fx-font-size: 13px;
}
.theme-dark {
    -sk-bg:            #1E1E1E;
    -sk-bg-elevated:   #2B2B2B;
    -sk-bg-hover:      #363636;
    -sk-bg-selected:   #393B40;
    -sk-border:        #3C3F41;
    -sk-border-strong: #555555;
    -sk-text:          #D0D0D0;
    -sk-text-secondary:#9AA0A6;
    -sk-text-disabled: #6B6F73;
    -sk-accent:        #3574F0;
    -sk-accent-soft:   rgba(53,116,240,0.18);
    -sk-success:       #5BB065;
    -sk-warning:       #F0A732;
    -sk-danger:        #F75464;
}
.theme-light {
    -sk-bg:            #FFFFFF;
    -sk-bg-elevated:   #F7F8FA;
    -sk-bg-hover:      #EBECEF;
    -sk-bg-selected:   #DFE1E5;
    -sk-border:        #DADCE0;
    -sk-border-strong: #C9CDD3;
    -sk-text:          #1E1E1E;
    -sk-text-secondary:#5A5D60;
    -sk-text-disabled: #A0A4A8;
    -sk-accent:        #3574F0;
    -sk-accent-soft:   rgba(53,116,240,0.14);
    -sk-success:       #3C914A;
    -sk-warning:       #C2751C;
    -sk-danger:        #E53935;
}
```

- [ ] **Step 2: Verify build + dark still looks identical**

The dark token values intentionally differ slightly from the old rgba palette, but no component references `-sk-*` yet, so **no visual change occurs** here. Confirm the app still launches and renders the existing dark UI.

- [ ] **Step 3: Commit**
```bash
git add SwissKitJ-Api/src/main/resources/css/swisskit-common.css
git commit -m "🎨 feat(theme): define -sk-* token set (dark/light) in common.css"
```

---

### Task 1.3: Delegate `Themes.applyTo` + apply theme at startup

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/theme/Themes.java:54-60` (`applyTo` body)
- Modify: `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` (startup: read theme, register scene)

- [ ] **Step 1: Make `Themes.applyTo` delegate to `ThemeService.registerScene`**

In `Themes.java`, replace the `applyTo(Scene scene)` method body with a delegation:
```java
    /**
     * Applies the common theme stylesheet to the given scene if not already present,
     * and stamps the current theme class on its root.
     *
     * <p>Delegates to {@link ThemeService#registerScene(Scene)}. Plugins that open
     * their own {@code Stage}/{@code Scene} should call this so they inherit the
     * active theme. Plugins embedded in the main window do not need to call it.
     *
     * @param scene the {@link Scene} to apply the theme to; ignored if {@code null}
     */
    public static void applyTo(Scene scene) {
        ThemeService.registerScene(scene);
    }
```
Keep the existing `commonStylesheetUrl()` / `COMMON_CSS` — `ThemeService.registerScene` calls back into `Themes.applyTo` for stylesheet loading, so extract the stylesheet-load logic into a tiny private method to avoid infinite recursion:

Final `Themes.java` body (replace whole class):
```java
package fan.summer.api.theme;

import javafx.scene.Scene;

/**
 * SwissKitJ theme stylesheet loading utility. Loads the shared common stylesheet
 * and applies the active theme. See {@link ThemeService} for theme state.
 *
 * @since 1.0
 */
public final class Themes {

    /** Resource path of the shared common stylesheet within the API JAR. */
    public static final String COMMON_CSS = "/css/swisskit-common.css";

    private Themes() {}

    /** @return the external form URL of the shared common stylesheet. */
    public static String commonStylesheetUrl() {
        return Themes.class.getResource(COMMON_CSS).toExternalForm();
    }

    /**
     * Applies the common stylesheet + active theme to the given scene.
     * Idempotent. Delegates to {@link ThemeService#registerScene(Scene)}.
     */
    public static void applyTo(Scene scene) {
        ThemeService.registerScene(scene);
    }

    /** Loads the common stylesheet onto the scene if not already present (no theme stamping). */
    static void loadCommonStylesheet(Scene scene) {
        if (scene == null) return;
        String url = commonStylesheetUrl();
        if (!scene.getStylesheets().contains(url)) {
            scene.getStylesheets().add(url);
        }
    }
}
```
Then fix `ThemeService.registerScene` to call `Themes.loadCommonStylesheet(scene)` instead of `Themes.applyTo(scene)` (avoids mutual recursion). In `ThemeService.java`, replace the line `Themes.applyTo(scene);` inside `registerScene` with:
```java
        Themes.loadCommonStylesheet(scene);
```

- [ ] **Step 2: Read + apply theme at startup in `SwissKitJApp`**

In `SwissKitJApp.java`, add imports:
```java
import fan.summer.api.theme.ThemeService;
```
After the `if ("zh".equals(savedLang)) { ... }` i18n block (around line 85), add theme loading:
```java
        // ── Theme (dark default, persisted) ────────────────────────
        String savedTheme = readThemeFromDb();
        ThemeService.set("light".equalsIgnoreCase(savedTheme)
            ? ThemeService.Theme.LIGHT : ThemeService.Theme.DARK);
```
After `stage.setScene(scene);` (around line 126) — and **before** `stage.show()` — register the scene so the theme class is stamped:
```java
        ThemeService.registerScene(scene);
```
Add the reader method (mirror of `readLanguageFromDb`) near the bottom of the class:
```java
    /** Reads the saved theme preference; "light" → light, anything else → dark. */
    private String readThemeFromDb() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey("theme");
            if (entity != null) return entity.getSettingValue();
        } catch (Exception e) {
            log.debug("Could not read theme setting", e);
        }
        return "dark";
    }
```

- [ ] **Step 3: Build + launch, verify dark still identical**

Build via `mcp__idea__build_project`. Launch the app. Expected: identical dark UI (theme class `theme-dark` is on the root but nothing consumes `-sk-*` yet).

- [ ] **Step 4: Commit**
```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/theme/Themes.java \
        SwissKitJ-Api/src/main/java/fan/summer/api/theme/ThemeService.java \
        SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java
git commit -m "✨ feat(theme): apply persisted theme on startup, register main scene"
```

---

## Phase 2 — Token-ize + restyle common components; rename `.glass-*` → `.sk-*`

### Task 2.1: Rewrite `swisskit-common.css` (tokens + `.sk-*` flat components)

**Files:**
- Modify: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (everything below the token block)

- [ ] **Step 1: Replace the entire component section** (from the `/* ── 内容区滚动面板 */` comment onward, i.e. everything after the `.theme-light { ... }` block) with the following. This renames every `.glass-*` → `.sk-*` and rewrites values to tokens / flat IDEA style:

```css
/* ── 内容区滚动面板(纤细 4px) ──────────────────────────────── */
.content-scroll .scroll-bar:vertical { -fx-pref-width: 4px; }
.content-scroll .scroll-bar:horizontal { -fx-pref-height: 4px; }
.content-scroll .scroll-bar .thumb {
    -fx-background-color: -sk-text-disabled;
    -fx-background-radius: 4px;
}
.content-scroll .scroll-bar .thumb:hover,
.content-scroll .scroll-bar .thumb:pressed {
    -fx-background-color: -sk-text-secondary;
}
.content-scroll .scroll-bar .track,
.content-scroll .scroll-bar .track-background,
.content-scroll .increment-button,
.content-scroll .decrement-button {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
}
.content-scroll .increment-arrow,
.content-scroll .decrement-arrow { -fx-shape: " "; -fx-padding: 0; }

/* ── 全局滚动条 ──────────────────────────────────────────────── */
.scroll-bar:vertical { -fx-pref-width: 8px; }
.scroll-bar:horizontal { -fx-pref-height: 8px; }
.scroll-bar { -fx-background-color: transparent; }
.scroll-bar .track,
.scroll-bar .track-background { -fx-background-color: transparent; -fx-border-color: transparent; }
.scroll-bar .thumb {
    -fx-background-color: -sk-text-disabled;
    -fx-background-radius: 6px;
    -fx-border-color: transparent;
}
.scroll-bar .thumb:hover,
.scroll-bar .thumb:pressed { -fx-background-color: -sk-text-secondary; }
.scroll-bar .increment-button,
.scroll-bar .decrement-button { -fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; }
.scroll-bar .increment-arrow,
.scroll-bar .decrement-arrow { -fx-shape: " "; -fx-padding: 0; }
.scroll-pane,
.scroll-pane > .viewport,
.scroll-pane .corner { -fx-background-color: transparent; }

/* ── 进度条(纤细 6px) ───────────────────────────────────────── */
.progress-bar { -fx-pref-height: 6px; }
.progress-bar > .track { -fx-background-color: -sk-bg-hover; -fx-background-radius: 3px; -fx-border-color: transparent; }
.progress-bar > .bar { -fx-background-color: -sk-accent; -fx-background-radius: 3px; -fx-background-insets: 0; -fx-padding: 0; }
.progress-bar:indeterminate > .bar {
    -fx-background-color: linear-gradient(to right,
        rgba(53,116,240,0.0) 0%, rgba(53,116,240,1.0) 50%, rgba(53,116,240,0.0) 100%);
}
.progress-bar.success > .bar { -fx-background-color: -sk-success; }
.progress-bar.danger  > .bar { -fx-background-color: -sk-danger; }

/* ── 区块标题 ─────────────────────────────────────────────────── */
.section-title  { -fx-text-fill: -sk-text-secondary; -fx-font-size: 11px; -fx-font-weight: bold; }
.section-header { -fx-text-fill: -sk-text; -fx-font-size: 15px; -fx-font-weight: 500; }

/* ── 对话框(独立 Stage) ──────────────────────────────────────── */
.sk-dialog {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-border-radius: 10px;
    -fx-background-radius: 10px;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 30, 0, 0, 12);
}

/* ── 输入字段 ────────────────────────────────────────────────── */
.sk-field {
    -fx-background-color: -sk-bg;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-border-radius: 6px;
    -fx-background-radius: 6px;
    -fx-text-fill: -sk-text;
    -fx-font-size: 13px;
    -fx-padding: 8 12 8 12;
}
.sk-field:focused {
    -fx-border-color: -sk-accent;
    -fx-background-color: -sk-bg-elevated;
}
.sk-field-label { -fx-text-fill: -sk-text-secondary; -fx-font-size: 11px; -fx-font-weight: bold; }

/* ── TabPane ─────────────────────────────────────────────────── */
.sk-tab-pane { -fx-background-color: transparent; -fx-tab-min-width: 100px; -fx-tab-max-height: 36px; }
.sk-tab-pane .tab-header-area { -fx-padding: 4 8 0 8; }
.sk-tab-pane .tab-header-area .tab-header-background {
    -fx-background-color: transparent;
    -fx-border-color: -sk-border;
    -fx-border-width: 0 0 1 0;
}
.sk-tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-radius: 6px 6px 0 0;
    -fx-border-radius: 6px 6px 0 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-padding: 8 20 8 20;
    -fx-cursor: hand;
}
.sk-tab-pane .tab .tab-label { -fx-text-fill: -sk-text-secondary; -fx-font-size: 13px; -fx-font-weight: 500; }
.sk-tab-pane .tab:hover { -fx-background-color: -sk-bg-hover; }
.sk-tab-pane .tab:selected {
    -fx-background-color: -sk-bg-selected;
    -fx-border-color: transparent transparent -sk-accent transparent;
    -fx-border-width: 0 0 2px 0;
}
.sk-tab-pane .tab:selected .tab-label { -fx-text-fill: -sk-text; }
.sk-tab-pane .tab:selected .focus-indicator { -fx-border-color: transparent; }

/* ── 下拉框 ──────────────────────────────────────────────────── */
.sk-combo {
    -fx-background-color: -sk-bg;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-border-radius: 6px;
    -fx-background-radius: 6px;
    -fx-text-fill: -sk-text;
    -fx-font-size: 13px;
}
.sk-combo .list-cell { -fx-text-fill: -sk-text; -fx-background-color: transparent; }
.sk-combo .arrow-button { -fx-background-color: transparent; -fx-border-color: transparent; }
.sk-combo .arrow { -fx-background-color: -sk-text-secondary; }

.combo-box-popup .list-view,
.choice-box .context-menu {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-background-radius: 8px;
    -fx-border-radius: 8px;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.40), 16, 0, 0, 6);
}
.combo-box-popup .list-view .list-cell,
.choice-box .context-menu .menu-item .label {
    -fx-text-fill: -sk-text;
    -fx-background-color: transparent;
    -fx-padding: 6 12 6 12;
}
.combo-box-popup .list-view .list-cell:filled:hover,
.choice-box .context-menu .menu-item:hover .label {
    -fx-text-fill: -sk-text;
    -fx-background-color: -sk-bg-hover;
}
.combo-box-popup .list-view .list-cell:filled:selected,
.combo-box-popup .list-view .list-cell:filled:selected:hover {
    -fx-text-fill: -sk-accent;
    -fx-background-color: -sk-bg-selected;
}

/* ── ContextMenu ─────────────────────────────────────────────── */
.context-menu {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-background-radius: 8px;
    -fx-border-radius: 8px;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.40), 16, 0, 0, 6);
}
.context-menu .menu-item { -fx-background-color: transparent; -fx-border-color: transparent; }
.context-menu .menu-item .label { -fx-text-fill: -sk-text; -fx-padding: 6 12 6 12; }
.context-menu .menu-item:hover { -fx-background-color: -sk-bg-hover; -fx-border-color: transparent; }
.context-menu .menu-item:hover .label { -fx-text-fill: -sk-text; }
.context-menu .separator .line { -fx-border-color: -sk-border; -fx-border-width: 1 0 0 0; }

/* ── 表格 ────────────────────────────────────────────────────── */
.sk-table {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-border-radius: 8px;
    -fx-background-radius: 8px;
    -fx-table-cell-border-color: transparent;
}
.sk-table .column-header-background { -fx-background-color: -sk-bg-hover; }
.sk-table .column-header { -fx-background-color: transparent; -fx-border-color: -sk-border; -fx-border-width: 0 0 1 0; }
.sk-table .column-header .label { -fx-text-fill: -sk-text-secondary; -fx-font-size: 12px; -fx-font-weight: 500; }
.sk-table .table-cell { -fx-text-fill: -sk-text; -fx-font-size: 13px; -fx-border-color: transparent; -fx-padding: 6 10 6 10; }
.sk-table .table-row-cell { -fx-background-color: transparent; -fx-border-color: transparent; -fx-table-cell-border-color: transparent; }
.sk-table .table-row-cell:selected { -fx-background-color: -sk-bg-selected; }
.sk-table .table-row-cell:selected .table-cell { -fx-text-fill: -sk-accent; }
.sk-table .table-row-cell:hover { -fx-background-color: -sk-bg-hover; }
.sk-table .placeholder .label { -fx-text-fill: -sk-text-disabled; }

/* ── 复选框 ──────────────────────────────────────────────────── */
.sk-checkbox { -fx-text-fill: -sk-text; -fx-font-size: 13px; }
.sk-checkbox .box {
    -fx-background-color: -sk-bg;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-border-radius: 4px;
    -fx-background-radius: 4px;
}
.sk-checkbox:selected .box { -fx-background-color: -sk-accent; -fx-border-color: -sk-accent; }
.sk-checkbox:selected .mark { -fx-background-color: white; }

/* ── 主按钮 ──────────────────────────────────────────────────── */
.sk-btn-primary {
    -fx-background-color: -sk-accent;
    -fx-text-fill: white;
    -fx-font-size: 13px;
    -fx-font-weight: 500;
    -fx-background-radius: 6px;
    -fx-border-width: 0;
    -fx-padding: 8 18 8 18;
    -fx-cursor: hand;
}
.sk-btn-primary:hover { -fx-background-color: derive(-sk-accent, -8%); }
.sk-btn-primary:pressed { -fx-background-color: derive(-sk-accent, -16%); }

/* ── 次按钮 ──────────────────────────────────────────────────── */
.sk-btn-secondary {
    -fx-background-color: -sk-bg-hover;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-text-fill: -sk-text;
    -fx-font-size: 13px;
    -fx-background-radius: 6px;
    -fx-border-radius: 6px;
    -fx-padding: 8 18 8 18;
    -fx-cursor: hand;
}
.sk-btn-secondary:hover { -fx-background-color: -sk-bg-selected; -fx-border-color: -sk-border-strong; }

/* ── 通知框 ──────────────────────────────────────────────────── */
.sk-notif-root {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-background-radius: 10px;
    -fx-border-radius: 10px;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.50), 28, 0, 0, 10);
    -fx-pref-width: 420px;
    -fx-max-width: 480px;
}
.sk-notif-icon {
    -fx-font-size: 22px;
    -fx-min-width: 32px; -fx-min-height: 32px; -fx-pref-width: 32px; -fx-pref-height: 32px;
    -fx-alignment: center;
    -fx-background-radius: 16px; -fx-border-radius: 16px;
}
.sk-notif-info    { -fx-text-fill: -sk-accent;  -fx-background-color: -sk-accent-soft; }
.sk-notif-success { -fx-text-fill: -sk-success; -fx-background-color: rgba(76,217,123,0.15); }
.sk-notif-warning { -fx-text-fill: -sk-warning; -fx-background-color: rgba(245,166,35,0.15); }
.sk-notif-error   { -fx-text-fill: -sk-danger;  -fx-background-color: rgba(242,92,92,0.15); }
.sk-notif-message { -fx-fill: -sk-text; -fx-font-size: 13.5px; -fx-line-spacing: 2px; }
.sk-notif-btn-bar { -fx-padding: 0; }
.sk-notif-ok {
    -fx-background-color: -sk-accent;
    -fx-text-fill: white;
    -fx-font-size: 12.5px;
    -fx-font-weight: 500;
    -fx-background-radius: 6px;
    -fx-border-radius: 6px;
    -fx-border-width: 0;
    -fx-padding: 6 20 6 20;
    -fx-cursor: hand;
}
.sk-notif-ok:hover { -fx-background-color: derive(-sk-accent, -8%); }
.sk-notif-cancel {
    -fx-background-color: -sk-bg-hover;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-text-fill: -sk-text-secondary;
    -fx-font-size: 12.5px;
    -fx-background-radius: 6px;
    -fx-border-radius: 6px;
    -fx-padding: 6 20 6 20;
    -fx-cursor: hand;
}
.sk-notif-cancel:hover { -fx-background-color: -sk-bg-selected; -fx-text-fill: -sk-text; }
```

- [ ] **Step 2: Build** via `mcp__idea__build_project`. Expected: clean.

- [ ] **Step 3: Commit**
```bash
git add SwissKitJ-Api/src/main/resources/css/swisskit-common.css
git commit -m "♻️ refactor(ui): token-ize + flatten common components, rename glass-*→sk-*"
```

---

### Task 2.2: Update API module Java references `.glass-*` → `.sk-*`

**Files:**
- Modify: any API module `.java` calling `getStyleClass().add("glass-...")` or holding a `"glass-..."` literal

- [ ] **Step 1: Find every API reference**

Run via `mcp__idea__search_in_files_by_text` searchText=`glass-` directory=`SwissKitJ-Api/src/main`. Note every file + line. (Known: `StepWizard` uses glass utility classes — confirm the full list before editing.)

- [ ] **Step 2: Replace each `"glass-..."` literal with the renamed class**

Apply the mapping from spec §7:
| old | new |
|---|---|
| `glass-dialog` | `sk-dialog` |
| `glass-field` / `glass-field-label` | `sk-field` / `sk-field-label` |
| `glass-tab-pane` | `sk-tab-pane` |
| `glass-combo` | `sk-combo` |
| `glass-table` | `sk-table` |
| `glass-checkbox` | `sk-checkbox` |
| `glass-btn-primary` / `glass-btn-secondary` | `sk-btn-primary` / `sk-btn-secondary` |
| `glass-notif-*` | `sk-notif-*` |

For each hit, edit the Java string literal, e.g. `cb.getStyleClass().add("glass-checkbox");` → `cb.getStyleClass().add("sk-checkbox");`. Edit by exact line.

- [ ] **Step 3: Verify no `glass-` remains in the API module**

Run: `mcp__idea__search_in_files_by_text` searchText=`glass-` directory=`SwissKitJ-Api/src`. Expected: zero hits (excluding any `.css` comments — those were already renamed in Task 2.1).

- [ ] **Step 4: Build + commit**
```bash
# build via IDEA Maven: SwissKitJ-Api → install (skips tests optional)
git add -A SwissKitJ-Api
git commit -m "♻️ refactor(ui): rename glass-*→sk-* usages in API module"
```

---

### Task 2.3: Update SwissKit module Java references `.glass-*` → `.sk-*`

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/email/EmailPlugin.java:347,358` (`glass-checkbox`)
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/pdftool/PdfToolPlugin.java:39` (`glass-tab-pane`)
- Modify: `SwissKit/src/main/java/fan/summer/ui/store/OnlineStorePane.java:130` (`glass-combo`)
- Modify: `SwissKit/src/main/java/fan/summer/ui/about/AboutDialog.java:68` (`glass-dialog`) + the javadoc mention at `:27`
- Modify: `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` — every `"glass-..."` literal (see Task 2.1 search hits at lines 222, 372, 456, 708, 709, 1090, 1181, 1185, 1334, 1372, 1425, 1472, 1532, 1575, 1597, 1699, 1841, 2028, 2043, 2060, 2065, 2067) plus the constant at `:2060` `FIELD_STYLE_CLASS = "glass-field"`.

- [ ] **Step 1: Rename the constant first**

In `SwissKitJSettingUi.java:2060`:
```java
    private static final String FIELD_STYLE_CLASS = "sk-field";
```

- [ ] **Step 2: Replace every remaining `"glass-..."` literal in SwissKit module**

Use the mapping table from Task 2.2 step 2. For each search hit, edit the exact line. (The two lines 708/709 use `setAll("glass-btn-primary"/"glass-btn-secondary")` — rename both sides.)

- [ ] **Step 3: Verify no `glass-` remains in SwissKit main source**

Run: `mcp__idea__search_in_files_by_text` searchText=`glass-` directory=`SwissKit/src/main/java`. Expected: zero hits.

- [ ] **Step 4: Build + commit**
```bash
git add -A SwissKit/src/main/java
git commit -m "♻️ refactor(ui): rename glass-*→sk-* usages in SwissKit module"
```

---

## Phase 3 — Shell CSS: New UI restyle (token-based)

### Task 3.1: Rewrite `shell.css`

**Files:**
- Modify: `SwissKit/src/main/resources/css/shell.css` (full rewrite)

- [ ] **Step 1: Replace the entire file contents with:**

```css
/* ================================================================
   shell.css — SwissKitJ 主程序壳层样式(IDEA 2025 New UI)
   共性 token 与组件见 API 模块的 swisskit-common.css。
   ================================================================ */

/* ── 窗口根 ──────────────────────────────────────────────────── */
.app-root { -fx-background-color: -sk-bg; }

/* ── 侧栏 ──────────────────────────────────────────────────── */
.sidebar {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 0 1 0 0;
    -fx-pref-width: 200px;
    -fx-min-width: 200px;
    -fx-padding: 0;
    -fx-spacing: 0;
}
/* 折叠态:纯图标条 */
.sidebar.collapsed {
    -fx-pref-width: 48px;
    -fx-min-width: 48px;
}
.sidebar.collapsed .sidebar-section-label,
.sidebar.collapsed .nav-item-text,
.sidebar.collapsed .nav-badge { -fx-opacity: 0; }
.sidebar.collapsed .nav-item { -fx-alignment: center; -fx-padding: 8 0 8 0; }

.sidebar-scroll { -fx-background-color: transparent; -fx-background: transparent; -fx-padding: 10 8 10 8; }
.sidebar-scroll > .viewport { -fx-background-color: transparent; }
.sidebar-scroll > .scroll-bar:vertical { -fx-background-color: transparent; -fx-opacity: 0.3; -fx-pref-width: 4px; }
.sidebar-scroll:hover > .scroll-bar:vertical { -fx-opacity: 0.6; }
.sidebar-scroll > .scroll-bar:vertical .thumb { -fx-background-color: -sk-text-disabled; -fx-background-radius: 2; }
.sidebar-scroll > .scroll-bar:vertical .track { -fx-background-color: transparent; }
.sidebar-scroll > .scroll-bar:vertical .increment-button,
.sidebar-scroll > .scroll-bar:vertical .decrement-button { -fx-padding: 0; -fx-background-color: transparent; -fx-min-height: 0; -fx-pref-height: 0; }

.sidebar-section-label { -fx-text-fill: -sk-text-secondary; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 10 10 4 10; }

/* 折叠按钮 */
.sidebar-collapse-btn {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-text-fill: -sk-text-secondary;
    -fx-font-size: 14px;
    -fx-cursor: hand;
    -fx-padding: 6 10 6 10;
}
.sidebar-collapse-btn:hover { -fx-text-fill: -sk-text; -fx-background-color: -sk-bg-hover; -fx-background-radius: 6; }

/* 导航项:扁平,选中用中性灰 + 左侧 accent 细条 */
.nav-item {
    -fx-background-color: transparent;
    -fx-background-radius: 6px;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-padding: 7 10 7 12;
    -fx-cursor: hand;
    -fx-alignment: center-left;
    -fx-spacing: 10px;
    -fx-pref-height: 32px;
}
.nav-item-icon { -fx-fill: -sk-text-secondary; -fx-min-width: 18px; -fx-alignment: center; }
.nav-item-text { -fx-font-size: 13px; -fx-text-fill: -sk-text-secondary; }
.nav-item:hover { -fx-background-color: -sk-bg-hover; }
.nav-item:hover .nav-item-text { -fx-text-fill: -sk-text; }
.nav-item.active {
    -fx-background-color: -sk-bg-selected;
    -fx-border-color: transparent transparent transparent -sk-accent;
    -fx-border-width: 0 0 0 3px;
}
.nav-item.active .nav-item-text { -fx-text-fill: -sk-text; -fx-font-weight: 500; }

.nav-badge {
    -fx-background-color: -sk-bg-selected;
    -fx-text-fill: -sk-text-secondary;
    -fx-background-radius: 20px;
    -fx-padding: 1 7 1 7;
    -fx-font-size: 10px;
    -fx-font-weight: bold;
}
.nav-badge-new { -fx-background-color: rgba(76,217,123,0.18); -fx-text-fill: -sk-success; }

.sidebar-divider { -fx-border-color: -sk-border; -fx-border-width: 1 0 0 0; -fx-pref-height: 1px; -fx-padding: 4 4 4 4; }

/* ── 搜索栏(IDEA Search Everywhere 胶囊) ───────────────────── */
.search-bar {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-border-radius: 999px;
    -fx-background-radius: 999px;
    -fx-padding: 0 14 0 14;
    -fx-pref-height: 34px;
    -fx-spacing: 10px;
}
.search-bar:focused-within {
    -fx-background-color: -sk-bg;
    -fx-border-color: -sk-accent;
}
.search-field {
    -fx-background-color: transparent;
    -fx-border-width: 0;
    -fx-text-fill: -sk-text;
    -fx-prompt-text-fill: -sk-text-disabled;
    -fx-font-size: 13px;
}
.search-field:focused { -fx-background-color: transparent; }

/* ── 工具卡片(扁平、克制 hover) ──────────────────────────── */
.tool-card {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-border-radius: 8px;
    -fx-background-radius: 8px;
    -fx-padding: 14 14 14 14;
    -fx-cursor: hand;
    -fx-pref-width: 152px;
    -fx-pref-height: 128px;
    -fx-spacing: 3px;
}
.tool-card:hover { -fx-background-color: -sk-bg-hover; -fx-border-color: -sk-border-strong; }

.tool-icon-wrap {
    -fx-pref-width: 48px; -fx-pref-height: 48px; -fx-min-width: 48px; -fx-min-height: 48px;
    -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 0;
    -fx-alignment: center;
}

/* 图标配色 — 颜色注入到 Text 节点上,glow 由 Java 代码通过 DropShadow 设置 */
.ic-blue   { }
.ic-purple { }
.ic-teal   { }
.ic-amber  { }
.ic-red    { }
.ic-pink   { }
.ic-gray   { }

.tool-name { -fx-text-fill: -sk-text; -fx-font-size: 13px; -fx-font-weight: 500; }
.tool-desc { -fx-text-fill: -sk-text-secondary; -fx-font-size: 11px; -fx-wrap-text: true; }
.tool-tag {
    -fx-background-color: -sk-bg-hover;
    -fx-text-fill: -sk-text-secondary;
    -fx-border-color: -sk-border;
    -fx-border-width: 1px;
    -fx-background-radius: 4px;
    -fx-border-radius: 4px;
    -fx-padding: 1 6 1 6;
    -fx-font-size: 10px;
}
.tool-tag-plugin { -fx-background-color: rgba(76,217,123,0.12); -fx-text-fill: -sk-success; -fx-border-color: rgba(76,217,123,0.20); }

/* ── 详情面板 ─────────────────────────────────────────────────── */
.detail-panel {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 0 0 0 1;
    -fx-background-radius: 0;
    -fx-padding: 20 16 20 16;
    -fx-spacing: 10px;
    -fx-pref-width: 260px;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.30), 16, 0, -4, 0);
}
.detail-launch-btn {
    -fx-background-color: -sk-accent;
    -fx-text-fill: white;
    -fx-font-weight: 500;
    -fx-background-radius: 6px;
    -fx-border-radius: 6px;
    -fx-border-width: 0;
    -fx-pref-height: 34px;
    -fx-cursor: hand;
}
.detail-launch-btn:hover { -fx-background-color: derive(-sk-accent, -8%); }
.detail-launch-btn:pressed { -fx-background-color: derive(-sk-accent, -16%); }

/* ── 状态栏 ──────────────────────────────────────────────────── */
.statusbar {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 1 0 0 0;
    -fx-pref-height: 28px;
    -fx-min-height: 28px;
    -fx-padding: 0 16 0 16;
    -fx-spacing: 12px;
}
.status-text {
    -fx-text-fill: -sk-text-secondary;
    -fx-font-size: 12px;
    -fx-font-family: "SF Mono", "Consolas", "Microsoft YaHei", monospace;
}

/* ── 插件商店 ─────────────────────────────────────────────── */
.store-search {
    -fx-background-color: -sk-bg;
    -fx-border-color: -sk-border;
    -fx-border-width: 1;
    -fx-border-radius: 6;
    -fx-background-radius: 6;
    -fx-text-fill: -sk-text;
    -fx-prompt-text-fill: -sk-text-disabled;
    -fx-font-size: 12.5px;
    -fx-padding: 8 12 8 12;
}
.store-search:focused { -fx-border-color: -sk-accent; }
.store-card {
    -fx-background-color: -sk-bg-elevated;
    -fx-border-color: -sk-border;
    -fx-border-width: 1;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
}
.store-card:hover { -fx-border-color: -sk-accent; -fx-background-color: -sk-bg-hover; }
.store-card-name { -fx-text-fill: -sk-text; -fx-font-size: 13.5px; -fx-font-weight: bold; }
.store-badge { -fx-text-fill: -sk-text-secondary; -fx-font-size: 10px; -fx-background-color: -sk-bg-hover; -fx-background-radius: 5; -fx-padding: 2 7 2 7; }
.store-card-desc { -fx-text-fill: -sk-text-secondary; -fx-font-size: 11.5px; }
.store-install-btn {
    -fx-background-color: -sk-accent;
    -fx-text-fill: white;
    -fx-font-size: 12px;
    -fx-font-weight: bold;
    -fx-background-radius: 6;
    -fx-border-width: 0;
    -fx-padding: 7 0 7 0;
    -fx-cursor: hand;
}
.store-install-btn.installed {
    -fx-background-color: rgba(76,217,123,0.15);
    -fx-text-fill: -sk-success;
    -fx-border-color: rgba(76,217,123,0.30);
    -fx-border-width: 1;
    -fx-border-radius: 6;
    -fx-cursor: default;
}
.store-install-btn.update {
    -fx-background-color: rgba(240,169,58,0.15);
    -fx-text-fill: -sk-warning;
    -fx-border-color: rgba(240,169,58,0.30);
    -fx-border-width: 1;
    -fx-border-radius: 6;
}
```

- [ ] **Step 2: Build + launch (dark).** Expected: flat dark IDEA-style shell; sidebar/cards/search restyled; no `#5b8cf7` blue, selection now gray + accent left bar. (Window chrome + orb removal land in Phase 4, so the window may still be transparent/rounded until then — that's fine.)

- [ ] **Step 3: Commit**
```bash
git add SwissKit/src/main/resources/css/shell.css
git commit -m "🎨 feat(ui): restyle shell to IDEA New UI (tokens, flat, gray selection)"
```

---

### Task 3.2: Clean inline `#5b8cf7` from Java

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java:266,269,297,305` (NavItem inline icon fill)
- Modify: `SwissKit/src/main/java/fan/summer/ai/util/MarkdownRenderer.java:61` (`a { color: #5b8cf7; }`) — handled fully in Phase 7, but change the literal now to `#3574F0`

- [ ] **Step 1: Replace `NavItem` inline icon colors with accent token**

In `Sidebar.java`, the `NavItem` constructor sets `iconNode.setStyle("-fx-fill: rgba(255,255,255,0.75);")` and `setActive()` sets `"-fx-fill: #5b8cf7;"`. Inline `setStyle` overrides CSS, so use the accent hex directly. Replace all `#5b8cf7` literals with `#3574F0` and `rgba(255,255,255,0.75)` icon fills with `-sk-text-secondary` value `#9AA0A6` (note: inline style can't reference looked-up colors, so use the dark hex; acceptable — icons in dark mode). Specifically:
- constructor `MdiIconUtil.createIcon(mdiIcon, 16, "-fx-fill: #9AA0A6;")`
- hover enter `iconNode.setStyle("-fx-fill: #D0D0D0;")`
- hover exit `iconNode.setStyle("-fx-fill: #9AA0A6;")`
- `setActive(true)` `iconNode.setStyle("-fx-fill: #3574F0;")`
- `setActive(false)` `iconNode.setStyle("-fx-fill: #9AA0A6;")`

> Note: inline-styled icon colors won't follow light theme. This is acceptable for v3.2.0 dark-default; if a fully theme-reactive icon is desired later, move the fill to CSS via the existing `.nav-item-icon`/`.active` selectors and drop the inline `setStyle`. Leave a `// TODO(theme)` comment.

- [ ] **Step 2: Fix MarkdownRenderer link color**

`MarkdownRenderer.java:61` change `a { color: #5b8cf7; }` → `a { color: #3574F0; }`. (Full theme-aware CSS lands in Phase 7.)

- [ ] **Step 3: Verify no `#5b8cf7` or `91,140,247` remains in source**

Run searches via `mcp__idea__search_in_files_by_text`:
- searchText=`5b8cf7` → expected: 0 hits in `SwissKit/src` and `SwissKitJ-Api/src`
- searchText=`91,140,247` → expected: 0 hits

- [ ] **Step 4: Build + commit**
```bash
git add SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java \
        SwissKit/src/main/java/fan/summer/ai/util/MarkdownRenderer.java
git commit -m "🎨 refactor(ui): replace inline #5b8cf7 with #3574F0 / dark palette"
```

---

## Phase 4 — Window architecture (native chrome, drop orbs/TitleBar)

### Task 4.1: `SwissKitJApp` → `StageStyle.DECORATED`, drop `WindowResizeHelper`

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java`

- [ ] **Step 1: Change stage style + remove transparent fill + drop resize helper**

In `SwissKitJApp.java`:
- Remove import `import javafx.scene.paint.Color;` (if now unused) — keep if still referenced.
- Remove import `import fan.summer.ui.util.WindowResizeHelper;`
- Line 110: delete `scene.setFill(Color.TRANSPARENT);`
- Line 124: replace `stage.initStyle(StageStyle.TRANSPARENT);` with `stage.initStyle(StageStyle.DECORATED);`
- Line 133: delete `WindowResizeHelper.attach(stage);` and its preceding comment line.

- [ ] **Step 2: Remove now-unused `StageStyle` import only if unused** — it stays used by `StageStyle.DECORATED`, so keep `import javafx.stage.StageStyle;`.

- [ ] **Step 3: Build.** Expected: clean.

- [ ] **Step 4: Commit** (defer to Task 4.4 so Phase 4 lands as one coherent commit — or commit now; prefer one commit at end of phase).

---

### Task 4.2: Delete `WindowResizeHelper.java`

**Files:**
- Delete: `SwissKit/src/main/java/fan/summer/ui/util/WindowResizeHelper.java`

- [ ] **Step 1: Delete the file**
```bash
git rm SwissKit/src/main/java/fan/summer/ui/util/WindowResizeHelper.java
```

- [ ] **Step 2: Verify no remaining references**

Run `mcp__idea__search_in_files_by_text` searchText=`WindowResizeHelper` directory=`SwissKit/src`. Expected: 0 hits (the import + attach call were removed in Task 4.1).

---

### Task 4.3: `MainWindow` — drop orb layer, clip, top-highlight, `TitleBar`; slim animation

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/MainWindow.java`

- [ ] **Step 1: Remove TitleBar usage**

- Remove import `import fan.summer.ui.titlebar.TitleBar;`
- Remove field `private final TitleBar titleBar;`
- In constructor, remove `titleBar = new TitleBar(stage, this::openSettings);` (keep `stage` field — needed by `AboutDialog`).
- Remove `openSettings` reference from TitleBar; settings/about/theme are wired via Sidebar in Task 5.1/6.1. Keep `openSettings()` / `openAbout()` methods.

- [ ] **Step 2: Rewrite `buildScene()` to drop glass shell**

Replace the whole `buildScene()` method with:
```java
    private void buildScene() {
        windowPane = new BorderPane();
        windowPane.setMaxWidth(Double.MAX_VALUE);
        windowPane.setMaxHeight(Double.MAX_VALUE);
        windowPane.getStyleClass().add("app-root");

        // Body: sidebar + content area
        HBox body = new HBox(sidebar, contentArea);
        HBox.setHgrow(contentArea, Priority.ALWAYS);
        body.setMinHeight(0);
        windowPane.setCenter(body);

        // Status bar
        HBox statusBar = buildStatusBar();
        BorderPane.setAlignment(statusBar, Pos.BOTTOM_LEFT);
        windowPane.setBottom(statusBar);

        getChildren().setAll(windowPane);
    }
```
Remove the now-unused imports: `javafx.scene.shape.Rectangle`, `javafx.scene.shape.Circle` (only if no longer used — `Circle` is still used in `buildStatusBar()` for the activity dot, so keep `Circle`; drop `Rectangle`).

- [ ] **Step 3: Delete orb methods**

Delete the methods `buildOrbLayer()` and `orb(...)` entirely. Remove now-unused imports: `javafx.animation.ScaleTransition`, `javafx.animation.TranslateTransition`, `javafx.animation.Interpolator` (check each — keep any still used by `playEntryAnimation`/`startClock`).

- [ ] **Step 4: Slim the entry animation to a fade**

Replace `playEntryAnimation()` with:
```java
    private void playEntryAnimation() {
        windowPane.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(250), windowPane);
        ft.setToValue(1);
        ft.setOnFinished(e -> windowPane.setOpacity(1));
        ft.play();
    }
```
Remove unused imports if `ScaleTransition`/`TranslateTransition`/`Interpolator`/`ParallelTransition` are no longer referenced (`FadeTransition` stays).

- [ ] **Step 5: Build + launch.** Expected: standard rectangular OS-decorated window (macOS traffic lights native), no orbs, flat dark shell, status bar at bottom.

---

### Task 4.4: Delete `TitleBar.java` + verify

**Files:**
- Delete: `SwissKit/src/main/java/fan/summer/ui/titlebar/TitleBar.java`

- [ ] **Step 1: Delete the file**
```bash
git rm SwissKit/src/main/java/fan/summer/ui/titlebar/TitleBar.java
```

- [ ] **Step 2: Verify no references**

Run `mcp__idea__search_in_files_by_text` searchText=`TitleBar` directory=`SwissKit/src`. Expected: 0 hits.

- [ ] **Step 3: Build + launch; commit the whole phase**
```bash
git add -A SwissKit
git commit -m "♻️ refactor(ui): native window chrome, drop TitleBar/WindowResizeHelper/orbs"
```

---

## Phase 5 — Collapsible sidebar

### Task 5.1: Sidebar collapse (label ⇄ icon-strip) + footer

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java`

- [ ] **Step 1: Add collapse state + toggle button + footer slot**

In `Sidebar.java`:
- Add fields (note `collapseBtn` must be a field so `toggleCollapse()` can reach it):
```java
    private boolean collapsed = readCollapsedPref();
    private Label collapseBtn;
```
- In `build()`, add a collapse toggle button at the top of `content` (before the AI section label):
```java
        collapseBtn = new Label(collapsed ? "»" : "«");
        collapseBtn.getStyleClass().add("sidebar-collapse-btn");
        collapseBtn.setMaxWidth(Double.MAX_VALUE);
        collapseBtn.setOnMouseClicked(e -> toggleCollapse());
        content.getChildren().add(collapseBtn);
```
- Add methods:
```java
    private void toggleCollapse() {
        collapsed = !collapsed;
        getStyleClass().toggle("collapsed");
        collapseBtn.setText(collapsed ? "»" : "«");
        SwissKitJSettingUi.saveSettingAsync("sidebar.collapsed", collapsed ? "true" : "false", null);
    }

    private boolean readCollapsedPref() {
        // best-effort; defaults false. Reads synchronously once at construction.
        try {
            String v = SwissKitJSettingUi.getSetting("sidebar.collapsed");
            return "true".equalsIgnoreCase(v);
        } catch (Exception e) {
            return false;
        }
    }
```
- In the constructor, after `build();`, apply the initial collapsed class:
```java
        if (collapsed) {
            getStyleClass().add("collapsed");
        }
```

- [ ] **Step 2: Expose generic settings accessors in `SwissKitJSettingUi`**

`SwissKitJSettingUi` already has `private static void saveSettingAsync(String key, String value, Runnable onSuccess)` (line 846). Widen it to `public`. Also add a public synchronous getter (the class already has a `settingsCache`; reuse `loadAiSetting`/cache pattern). Add:
```java
    public static String getSetting(String key) {
        ensureCacheLoaded();
        return settingsCache.getOrDefault(key, null);
    }
```
(Make `saveSettingAsync` `public static` — change only the modifier.)

- [ ] **Step 3: Add a CSS rule so collapsed section labels/badges hide** — already added in shell.css Task 3.1 (`.sidebar.collapsed .sidebar-section-label/.nav-item-text/.nav-badge { -fx-opacity: 0 }`). Verify it's present.

- [ ] **Step 4: Build + launch; click « to collapse/expand.** Expected: sidebar shrinks to 48px icon strip, labels hidden, persists across restart.

- [ ] **Step 5: Commit**
```bash
git add SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java \
        SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java
git commit -m "✨ feat(ui): collapsible sidebar (label ⇄ icon-strip, persisted)"
```

---

## Phase 6 — Theme toggle UI + persistence

### Task 6.1: Theme toggle button (sidebar footer) + Settings entry

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java` (add footer with settings/about/theme)
- Modify: `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` (add `saveThemeSetting` + theme row in settings page)
- Modify: `SwissKit/src/main/java/fan/summer/ui/MainWindow.java` (no-op — settings/about already wired to sidebar via `setOnSettingsSelect`/`setOnAboutSelect`; verify)

- [ ] **Step 1: Add theme toggle to the Sidebar footer**

In `Sidebar.build()`, the settings/about items are already added via `addSettingsItem` / `addAboutItem`. Add a theme toggle NavItem after them:
```java
        addThemeToggleItem();
```
New method:
```java
    private NavItem themeItem;
    private void addThemeToggleItem() {
        boolean dark = ThemeService.current() == ThemeService.Theme.DARK;
        themeItem = new NavItem("theme", dark ? "weather-night" : "weather-sunny",
                dark ? I18n.get("sidebar.label.theme.dark") : I18n.get("sidebar.label.theme.light"),
                0, false);
        themeItem.setOnMouseClicked(e -> {
            ThemeService.Theme next = (ThemeService.current() == ThemeService.Theme.DARK)
                ? ThemeService.Theme.LIGHT : ThemeService.Theme.DARK;
            applyTheme(next);
        });
        content.getChildren().add(themeItem);
    }

    private void applyTheme(ThemeService.Theme theme) {
        ThemeService.set(theme);
        boolean dark = theme == ThemeService.Theme.DARK;
        themeItem.setIcon(dark ? "weather-night" : "weather-sunny");
        SwissKitJSettingUi.saveThemeSetting(dark ? "dark" : "light");
    }
```
(`NavItem` needs an `setIcon(String mdiIcon)` method — add it; see Step 2.) Add imports: `import fan.summer.api.theme.ThemeService;`

- [ ] **Step 2: Add `NavItem.setIcon`**

In `Sidebar.NavItem`, the `iconNode` is a `Text` from `MdiIconUtil.createIcon`. Add:
```java
        public void setIcon(String mdiIcon) {
            Text t = MdiIconUtil.createIcon(mdiIcon, 16,
                active ? "-fx-fill: #3574F0;" : "-fx-fill: #9AA0A6;");
            t.getStyleClass().add("nav-item-icon");
            getChildren().set(getChildren().indexOf(iconNode), t);
            iconNode = t;
        }
```
(Change `iconNode` field from `final` to non-final if needed.)

- [ ] **Step 3: Add i18n keys**

Append to `SwissKit/src/main/resources/i18n/messages.properties`:
```
sidebar.label.theme.dark=Dark Theme
sidebar.label.theme.light=Light Theme
```
And to `messages_zh.properties`:
```
sidebar.label.theme.dark=深色主题
sidebar.label.theme.light=浅色主题
```

- [ ] **Step 4: Add `saveThemeSetting` + a theme row in Settings**

In `SwissKitJSettingUi.java`, add:
```java
    public static void saveThemeSetting(String code) {
        saveSettingAsync("theme", code, null);
    }
```
In the settings page layout (near the language combo around line 222), add a theme combo mirroring the language combo (Dark/Light), calling `saveThemeSetting` on change and `ThemeService.set(...)` immediately. Use the existing `.sk-combo` class.

- [ ] **Step 5: Build + launch; toggle theme in sidebar footer and in Settings.** Expected: whole app switches dark↔light live, persists across restart.

- [ ] **Step 6: Commit**
```bash
git add -A SwissKit
git commit -m "✨ feat(theme): dark/light toggle in sidebar + settings, persisted"
```

---

## Phase 7 — WebView theming (AI Chat)

### Task 7.1: `MarkdownRenderer` dark + light CSS

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/util/MarkdownRenderer.java`

- [ ] **Step 1: Write a failing test**

`SwissKit/src/test/java/fan/summer/ai/util/MarkdownRendererTest.java`:
```java
package fan.summer.ai.util;

import fan.summer.api.theme.ThemeService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {
    @Test
    void darkRenderUsesDarkBackground() {
        String html = MarkdownRenderer.render("# hi", ThemeService.Theme.DARK);
        assertTrue(html.contains("#1e1e2e"), "dark html should embed dark bg");
    }

    @Test
    void lightRenderUsesLightBackground() {
        String html = MarkdownRenderer.render("# hi", ThemeService.Theme.LIGHT);
        assertTrue(html.contains("#ffffff"), "light html should embed light bg");
    }
}
```

- [ ] **Step 2: Run test → fails** (`render(String, Theme)` overload doesn't exist).

- [ ] **Step 3: Add themed CSS + overload**

In `MarkdownRenderer.java`, split the single `CSS` string into `DARK_CSS` and `LIGHT_CSS`. Keep the existing dark CSS verbatim (background `#1e1e2e`, text `rgba(255,255,255,0.98)`, link `#3574F0`) as `DARK_CSS`. Add `LIGHT_CSS` identical except: `color: #1E1E1E;`, `background: #ffffff;`, `pre { background: #F7F8FA; border: 1px solid #DADCE0; }`, `p code { background: #EBECEF; }`, `blockquote { border-left: 3px solid #C9CDD3; color: #5A5D60; }`, `th,td { border: 1px solid #DADCE0; }`.

Add the explicit-theme overload and refactor the no-arg method:
```java
    public static String render(String markdown) {
        return render(markdown, ThemeService.current());
    }

    public static String render(String markdown, ThemeService.Theme theme) {
        String css = (theme == ThemeService.Theme.LIGHT) ? LIGHT_CSS : DARK_CSS;
        Node document = PARSER.parse(markdown == null ? "" : markdown);
        return "<html><head><meta charset=\"utf-8\"><style>" + css + "</style></head><body>"
             + RENDERER.render(document) + "</body></html>";
    }
```
Do the same for `renderPlain` (add `renderPlain(String, Theme)` overload; no-arg delegates via `ThemeService.current()`). Update the javadoc reference to `#1e1e2e` to mention both themes.

- [ ] **Step 4: Run test → passes.**

- [ ] **Step 5: Commit**
```bash
git add SwissKit/src/main/java/fan/summer/ai/util/MarkdownRenderer.java \
        SwissKit/src/test/java/fan/summer/ai/util/MarkdownRendererTest.java
git commit -m "✨ feat(ai): MarkdownRenderer dark/light CSS, render(md, Theme)"
```

---

### Task 7.2: `AiChatPlugin` theme-driven WebView bg + live re-render

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java:689,730`

- [ ] **Step 1: Replace hardcoded `#1e1e2e` WebView backgrounds with theme-driven value**

At lines 689 and 730 the WebView container uses `-fx-background-color: #1e1e2e;`. Replace with a value derived from the active theme (inline style can't read tokens, so compute):
```java
String webviewBg = (ThemeService.current() == ThemeService.Theme.LIGHT) ? "#ffffff" : "#1e1e2e";
```
and use `"-fx-background-color: " + webviewBg + ";" + ...` in those two `setStyle` calls. Add import `import fan.summer.api.theme.ThemeService;`.

- [ ] **Step 2: Re-render existing messages on theme change**

Where the chat view is built, register a listener that re-renders the conversation when the theme flips:
```java
ThemeService.onChange(t -> javafx.application.Platform.runLater(this::rerenderConversation));
```
Implement `rerenderConversation()` to re-wrap stored messages via `MarkdownRenderer.render(md)` (now theme-aware) and update the WebView bg. (Locate the existing render call site in `AiChatPlugin` and extract a small `rerenderConversation()` method that iterates the current message list and rebuilds the HTML.) Keep the previously-stored raw markdown (not pre-rendered HTML) so re-render is lossless.

- [ ] **Step 3: Build + launch; open AI Chat, send a message, toggle theme.** Expected: chat background + rendered markdown switch dark↔light live.

- [ ] **Step 4: Commit**
```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java
git commit -m "✨ feat(ai): theme-driven WebView bg + live re-render on theme change"
```

---

## Phase 8 — Verification & cleanup

### Task 8.1: Global grep assertions

- [ ] **Step 1: No `glass-` anywhere in main source**

Run `mcp__idea__search_in_files_by_text` searchText=`glass-` across `SwissKit/src` and `SwissKitJ-Api/src`. Expected: 0 hits. (The external plugin repo is handled separately — note in CHANGELOG.)

- [ ] **Step 2: No legacy accent color**

searchText=`5b8cf7` → 0 hits. searchText=`91,140,247` → 0 hits.

- [ ] **Step 3: No `1e1e2e` outside `MarkdownRenderer`**

searchText=`1e1e2e` → only hits inside `MarkdownRenderer.java` (DARK_CSS) and its test. `AiChatPlugin` must use the computed `webviewBg`.

---

### Task 8.2: Full build + manual verification matrix

- [ ] **Step 1: Build both modules** via IDEA Maven: `SwissKitJ-Api → install -DskipTests`, then `SwissKit → package -DskipTests`. Expected: BUILD SUCCESS, fat JAR produced.

- [ ] **Step 2: Launch the fat JAR** (`java -jar SwissKit/target/SwissKitJ-3.2.0.jar`). Verify:

| Check | Expected |
|---|---|
| Window chrome | Native OS title bar + close/min/max (macOS traffic lights) |
| Default theme | Dark |
| Sidebar « toggle | Collapses to 48px icon strip, expands back; persists on restart |
| Sidebar selection | Neutral gray highlight + left blue accent bar |
| Theme toggle (footer ☀/☾) | Whole app flips dark↔light live |
| Settings theme combo | Same effect, persisted |
| Components in light theme | Fields, combos, tables, checkboxes, buttons, dialogs, notifications all readable, no white-on-white |
| AI Chat | Markdown + WebView bg follow theme; toggle re-renders |
| Resize / drag / maximize | Native, works on macOS |
| `isMaximized()` macOS bug | Gone (WindowResizeHelper deleted) |

- [ ] **Step 3: Commit any final polish, then final commit**
```bash
git add -A
git commit -m "✅ chore: New UI redesign verification complete (v3.2.0)"
```

---

### Task 8.3: Documentation + breaking-change notice

- [ ] **Step 1: Update CHANGELOG.md** — add `## [3.2.0]` section at top listing: New UI redesign, dark/light theme, collapsible sidebar, native window chrome. **Prominently mark `.glass-*` → `.sk-*` as a BREAKING CHANGE for plugin authors** with the rename table (spec §7).

- [ ] **Step 2: Update CLAUDE.md** — replace the "Three-layer CSS structure" table notes that reference glassmorphism: `swisskit-common.css` now defines `-sk-*` tokens + `.sk-*` components (not `.glass-*`); mention `ThemeService` + theme classes. Update the "Theming" paragraph. Add `.sk-*` to the component naming. Note `StageStyle.DECORATED` + no `WindowResizeHelper`.

- [ ] **Step 3: Commit**
```bash
git add CHANGELOG.md CLAUDE.md
git commit -m "📝 docs: CHANGELOG + CLAUDE.md for New UI v3.2.0 (glass→sk breaking)"
```

---

## Notes for the executor

- **No system `mvn`.** All Maven runs go through IDEA's built-in Maven (`mcp__idea__build_project` / `mcp__idea__execute_terminal_command` or the Maven tool window). See CLAUDE.md + the `maven-test-recipe` memory.
- **Build order matters:** API module must be `install`-ed before the SwissKit module packages (it depends on the API JAR at runtime via the fat JAR).
- **JavaFX layout pitfalls:** review CLAUDE.md "JavaFX Layout Pitfalls" before any layout edit. Pitfall #6 (`isMaximized()` lying on transparent macOS stages) is auto-resolved by Phase 4.
- **External plugin repo** (`MuskStark/SwissKiJ-Plugin`) is a separate repo; the `.glass-*` → `.sk-*` rename there is tracked as a follow-up, not part of this plan. Flag it in the CHANGELOG.
- **Theme-reactive inline icon colors** (Sidebar `NavItem`) use dark-palette hexes and won't follow light theme for v3.2.0; acceptable per spec (dark default). Tagged `// TODO(theme)`.
