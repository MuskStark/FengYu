# Sidebar "About" Button + Dialog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "About" item below Settings in the sidebar that opens a modal dialog showing version, build time, author, repository, documentation, and license.

**Architecture:** A new `BuildInfo` reads Maven-filtered `build-info.properties` (with a dev-run fallback). A new `AboutDialog` is a transparent, owner-sized modal `Stage` with a dimmed backdrop and a centered `.glass-dialog` card. `Sidebar` gains an `onAboutSelect` callback (parallel to `onSettingsSelect`); `MainWindow` wires it to `new AboutDialog(stage).show()`.

**Tech Stack:** Java 21, JavaFX, JUnit 5, Maven (resource filtering). Build via IntelliJ IDEA Maven tool window only — **no system `mvn`** (see CLAUDE.md). Bundled Maven binary: `/Users/phoebej/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn`.

**Spec:** `docs/superpowers/specs/2026-06-22-about-button-design.md`

**Key decisions baked in:**
- Display = modal dialog (transparent, owner-sized, dimmed backdrop; click-outside / × / Esc dismiss).
- License = **GNU GPL v3** (trust the `LICENSE` file; the README's "MIT" badge is stale and out of scope).
- Version + build time come from Maven-filtered `build-info.properties`; dev runs (IDEA, no filtering) show `(dev)` / `(dev build)`.
- i18n files are `messages.properties` (English default) + `messages_zh.properties` (Chinese) — there is **no** `messages_en.properties` in this repo.

---

## File Structure

**Create:**
- `SwissKit/src/main/java/fan/summer/ui/about/BuildInfo.java` — reads `/build-info.properties`; exposes `getVersion()` / `getBuildTime()` with dev fallback.
- `SwissKit/src/main/java/fan/summer/ui/about/AboutDialog.java` — modal glass card dialog.
- `SwissKit/src/test/java/fan/summer/ui/about/BuildInfoTest.java` — unit tests for `BuildInfo`.
- `SwissKit/src/main/resources/build-info.properties` — Maven-filtered template.
- `SwissKit/src/test/resources/build-info.properties` — static test fixture (shadows the main template on the test classpath).

**Modify:**
- `SwissKit/pom.xml` — enable resource filtering for `build-info.properties` only; set `maven.build.timestamp.format`.
- `SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java` — add `onAboutSelect` callback + About nav item below Settings.
- `SwissKit/src/main/java/fan/summer/ui/MainWindow.java` — wire `onAboutSelect` → `openAbout()` → `AboutDialog`.
- `SwissKit/src/main/resources/i18n/messages.properties` + `messages_zh.properties` — About i18n keys.

---

## Task 1: Build-info pipeline (pom filtering + BuildInfo + tests)

**Goal:** Maven injects `${project.version}` and `${maven.build.timestamp}` into `build-info.properties` at build time; `BuildInfo` reads it with a dev-run fallback. Full TDD on the reader logic.

**Files:**
- Modify: `SwissKit/pom.xml`
- Create: `SwissKit/src/main/resources/build-info.properties`
- Create: `SwissKit/src/main/java/fan/summer/ui/about/BuildInfo.java`
- Test: `SwissKit/src/test/java/fan/summer/ui/about/BuildInfoTest.java`
- Create: `SwissKit/src/test/resources/build-info.properties`

- [ ] **Step 1: Enable selective resource filtering in `pom.xml`**

The current `<resources>` block (around line 287) is a single unfiltered entry. **Do NOT filter all resources** — that corrupts binary assets (images/fonts). Replace the `<resources>` block with two entries — default unfiltered + a filtered include for `build-info.properties` only.

`replace_text_in_file` on `SwissKit/pom.xml` — oldText:
```xml
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>false</filtering>
            </resource>
        </resources>
```
newText:
```xml
        <resources>
            <!-- Default: all resources unfiltered, so binary assets (images, fonts) aren't corrupted. -->
            <resource>
                <directory>src/main/resources</directory>
                <filtering>false</filtering>
            </resource>
            <!-- build-info.properties is the only filtered resource: ${project.version} / ${maven.build.timestamp} are replaced at build time. -->
            <resource>
                <directory>src/main/resources</directory>
                <includes>
                    <include>build-info.properties</include>
                </includes>
                <filtering>true</filtering>
            </resource>
        </resources>
```

- [ ] **Step 2: Set the build-timestamp format in `pom.xml`**

Add this line as the **first child** inside the existing `<properties>` block (the one defining `<swisskit.api.version>`, `<javafx.version>`, etc.):
```xml
        <maven.build.timestamp.format>yyyy-MM-dd HH:mm z</maven.build.timestamp.format>
```

- [ ] **Step 3: Create the filtered `build-info.properties` template**

Create `SwissKit/src/main/resources/build-info.properties`:
```properties
app.version=${project.version}
build.time=${maven.build.timestamp}
```

- [ ] **Step 4: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ui/about/BuildInfoTest.java`:
```java
package fan.summer.ui.about;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class BuildInfoTest {

    @Test
    void valueReturnsRealValueWhenPresent() {
        Properties p = new Properties();
        p.setProperty("app.version", "3.1.0");
        assertEquals("3.1.0", new BuildInfo(p).value("app.version", BuildInfo.DEV_VERSION));
    }

    @Test
    void fallsBackWhenKeyMissing() {
        BuildInfo info = new BuildInfo(new Properties());
        assertEquals(BuildInfo.DEV_VERSION, info.value("app.version", BuildInfo.DEV_VERSION));
        assertEquals(BuildInfo.DEV_BUILD_TIME, info.value("build.time", BuildInfo.DEV_BUILD_TIME));
    }

    @Test
    void fallsBackWhenUnfilteredPlaceholderRemains() {
        Properties p = new Properties();
        p.setProperty("app.version", "${project.version}");
        p.setProperty("build.time", "${maven.build.timestamp}");
        BuildInfo info = new BuildInfo(p);
        assertEquals(BuildInfo.DEV_VERSION, info.value("app.version", BuildInfo.DEV_VERSION));
        assertEquals(BuildInfo.DEV_BUILD_TIME, info.value("build.time", BuildInfo.DEV_BUILD_TIME));
    }

    @Test
    void getVersionReadsClasspathFixture() {
        // src/test/resources/build-info.properties shadows the main template on the
        // test classpath, so INSTANCE loads the fixture (full path check).
        assertEquals("9.9.9-test", BuildInfo.getVersion());
        assertEquals("2026-01-01 00:00 UTC", BuildInfo.getBuildTime());
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home)"
MVN="/Users/phoebej/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
"$MVN" -o -f SwissKit/pom.xml test -Dtest=BuildInfoTest
```
Expected: COMPILATION FAILURE — `cannot find symbol: class BuildInfo`.

- [ ] **Step 6: Implement `BuildInfo`**

Create `SwissKit/src/main/java/fan/summer/ui/about/BuildInfo.java`:
```java
package fan.summer.ui.about;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads build metadata injected by Maven resource filtering from
 * {@code /build-info.properties} on the classpath. Exposes the app version and
 * the build timestamp. When the file is missing or still contains unfiltered
 * {@code ${...}} placeholders (i.e. launched from the IDE without Maven), the
 * accessors return {@code (dev)} / {@code (dev build)} so the UI never shows
 * raw placeholders.
 */
public final class BuildInfo {

    static final String DEV_VERSION = "(dev)";
    static final String DEV_BUILD_TIME = "(dev build)";
    private static final String RESOURCE = "/build-info.properties";

    static final BuildInfo INSTANCE = new BuildInfo(load(RESOURCE));

    private final Properties props;

    /** Test seam: build an info view over an explicit property set. */
    BuildInfo(Properties props) {
        this.props = props;
    }

    static Properties load(String resource) {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(resource)) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
            // unreadable metadata is equivalent to a dev build
        }
        return p;
    }

    public static String getVersion() {
        return INSTANCE.value("app.version", DEV_VERSION);
    }

    public static String getBuildTime() {
        return INSTANCE.value("build.time", DEV_BUILD_TIME);
    }

    String value(String key, String fallback) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank() || v.contains("${")) return fallback;
        return v;
    }
}
```

- [ ] **Step 7: Create the test fixture**

Create `SwissKit/src/test/resources/build-info.properties`:
```properties
app.version=9.9.9-test
build.time=2026-01-01 00:00 UTC
```

- [ ] **Step 8: Run the test to verify it passes**

```bash
export JAVA_HOME="$(/usr/libexec/java_home)"
MVN="/Users/phoebej/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
"$MVN" -o -f SwissKit/pom.xml test -Dtest=BuildInfoTest
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 9: Commit**

```
git add SwissKit/pom.xml \
        SwissKit/src/main/resources/build-info.properties \
        SwissKit/src/main/java/fan/summer/ui/about/BuildInfo.java \
        SwissKit/src/test/java/fan/summer/ui/about/BuildInfoTest.java \
        SwissKit/src/test/resources/build-info.properties
git commit -m "✨ feat(ui): BuildInfo reads Maven-filtered version + build time with dev fallback"
```

---

## Task 2: Add About i18n keys

**Goal:** Sidebar label + six field-label keys exist in both locale files before the dialog references them.

**Files:**
- Modify: `SwissKit/src/main/resources/i18n/messages.properties`
- Modify: `SwissKit/src/main/resources/i18n/messages_zh.properties`

- [ ] **Step 1: Append English keys to `messages.properties`**

Append after the last `sidebar.label.*` line (e.g. after `sidebar.label.settings=...`):
```
sidebar.label.about=About
about.title=About SwissKitJ
about.field.version=Version
about.field.buildTime=Build Time
about.field.author=Author
about.field.repository=Repository
about.field.documentation=Documentation
about.field.license=Open Source License
```

- [ ] **Step 2: Append Chinese keys to `messages_zh.properties`**

Append the matching keys:
```
sidebar.label.about=关于
about.title=关于 SwissKitJ
about.field.version=版本
about.field.buildTime=编译时间
about.field.author=作者
about.field.repository=仓库地址
about.field.documentation=文档地址
about.field.license=开源协议
```

- [ ] **Step 3: Commit**

```
git add SwissKit/src/main/resources/i18n/messages.properties SwissKit/src/main/resources/i18n/messages_zh.properties
git commit -m "📝 i18n(ui): add About sidebar label + dialog field keys"
```

---

## Task 3: Implement `AboutDialog`

**Goal:** A modal glass-card dialog showing the six fields, with clickable repo/docs links and × / Esc / click-outside dismiss.

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ui/about/AboutDialog.java`

- [ ] **Step 1: Implement the dialog**

Create `SwissKit/src/main/java/fan/summer/ui/about/AboutDialog.java`:
```java
package fan.summer.ui.about;

import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.theme.Themes;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Desktop;
import java.net.URI;

/**
 * Modal "About SwissKitJ" dialog. A separate {@link Stage} sized to its owner
 * with a dimmed transparent backdrop (click to dismiss) and a centered
 * {@code .glass-dialog} card showing version, build time, author, repository,
 * documentation, and license. Repository / Documentation rows open the system
 * browser via {@link Desktop#browse}.
 */
public final class AboutDialog {

    private static final String REPOSITORY = "https://github.com/MuskStark/SwissKitJ";
    private static final String DOCUMENTATION = "https://muskstark.github.io/SwissKitJ/";
    private static final String AUTHOR = "MuskStark";
    private static final String LICENSE = "GNU GPL v3";

    private final Stage dialog;

    public AboutDialog(Stage owner) {
        dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.TRANSPARENT);

        StackPane root = new StackPane();
        // Dimmed backdrop; a click landing on the backdrop (not the card) closes.
        root.setStyle("-fx-background-color: rgba(0,0,0,0.35);");
        root.setOnMousePressed(e -> { if (e.getTarget() == root) dialog.close(); });

        Scene scene = new Scene(root, owner.getWidth(), owner.getHeight());
        scene.setFill(Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) dialog.close(); });
        Themes.applyTo(scene);
        dialog.setScene(scene);

        root.getChildren().add(buildCard());
    }

    /** Centers and shows the dialog; blocks (modal) until closed. */
    public void show() {
        dialog.centerOnScreen();
        dialog.showAndWait();
    }

    private VBox buildCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("glass-dialog");
        card.setMaxWidth(420);
        card.setMinWidth(380);

        card.getChildren().add(buildHeader());

        VBox rows = new VBox(8);
        rows.getChildren().addAll(
            row(I18n.get("about.field.version"),        BuildInfo.getVersion()),
            row(I18n.get("about.field.buildTime"),      BuildInfo.getBuildTime()),
            row(I18n.get("about.field.author"),         AUTHOR),
            linkRow(I18n.get("about.field.repository"),    REPOSITORY),
            linkRow(I18n.get("about.field.documentation"), DOCUMENTATION),
            row(I18n.get("about.field.license"),        LICENSE)
        );
        card.getChildren().add(rows);
        return card;
    }

    private HBox buildHeader() {
        Label title = new Label(I18n.get("about.title"));
        title.setStyle("-fx-text-fill: rgba(255,255,255,0.98); -fx-font-size: 17px; -fx-font-weight: bold;");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);

        Button close = new Button("×");
        close.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 16px; -fx-cursor: hand;");
        close.setOnAction(e -> dialog.close());

        HBox header = new HBox(0, title, close);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /** A label:value row where the value is plain text. */
    private HBox row(String key, String value) {
        Label v = new Label(value);
        v.setStyle("-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px;");
        v.setWrapText(true);
        v.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(v, Priority.ALWAYS);
        return new HBox(10, fieldKeyLabel(key), v);
    }

    /** A label:hyperlink row that opens the system browser. */
    private HBox linkRow(String key, String url) {
        Hyperlink link = new Hyperlink(url);
        link.setStyle("-fx-text-fill: #5b8cf7; -fx-font-size: 13px; -fx-border-color: transparent; -fx-padding: 0;");
        link.setOnAction(e -> browse(url));
        link.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(link, Priority.ALWAYS);
        return new HBox(10, fieldKeyLabel(key), link);
    }

    private Label fieldKeyLabel(String key) {
        Label l = new Label(key);
        l.setStyle("-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 12px; -fx-font-weight: bold;");
        l.setMinWidth(90);
        return l;
    }

    private static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // opening a browser is best-effort; never crash the dialog
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
export JAVA_HOME="$(/usr/libexec/java_home)"
MVN="/Users/phoebej/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
"$MVN" -o -f SwissKit/pom.xml compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```
git add SwissKit/src/main/java/fan/summer/ui/about/AboutDialog.java
git commit -m "✨ feat(ui): AboutDialog modal glass card with version/build/license info"
```

---

## Task 4: Wire the About button (Sidebar + MainWindow)

**Goal:** A clickable "About" nav item below Settings opens the dialog.

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java`
- Modify: `SwissKit/src/main/java/fan/summer/ui/MainWindow.java`

- [ ] **Step 1: Add the `onAboutSelect` callback + setter on `Sidebar`**

In `Sidebar.java`, next to the existing `onSettingsSelect` field (around line 47), add:
```java
    private Runnable onAboutSelect;
```

Next to `setOnSettingsSelect` (around line 76), add:
```java
    /**
     * Sets the runnable to execute when the user clicks the About item.
     *
     * @param handler the runnable to execute; may be null
     */
    public void setOnAboutSelect(Runnable handler) {
        LOG.debug("setOnAboutSelect callback set");
        this.onAboutSelect = handler;
    }
```

- [ ] **Step 2: Add the About nav item below Settings**

In `Sidebar.build()`, immediately after the existing `addSettingsItem("cog-outline", "sidebar.label.settings");` line (around line 126), add:
```java
        addAboutItem("information-outline", "sidebar.label.about");
```

- [ ] **Step 3: Add the `addAboutItem` helper**

Next to `addSettingsItem` (around line 156), add the parallel helper:
```java
    private void addAboutItem(String mdiIcon, String i18nKey) {
        String label = I18n.get(i18nKey);
        NavItem item = new NavItem("about", mdiIcon, label, 0, false);
        item.setOnMouseClicked(e -> {
            if (onAboutSelect != null) onAboutSelect.run();
        });
        content.getChildren().add(item);
        I18n.bind(item.textLabelProperty(), i18nKey);
    }
```

- [ ] **Step 4: Wire `onAboutSelect` in `MainWindow`**

In `MainWindow.java`, add the import:
```java
import fan.summer.ui.about.AboutDialog;
```

Next to the existing `sidebar.setOnSettingsSelect(this::openSettings);` line (around line 292), add:
```java
        sidebar.setOnAboutSelect(this::openAbout);
```

Next to the `openSettings()` method (around line 368), add:
```java
    private void openAbout() {
        new AboutDialog(stage).show();
    }
```

- [ ] **Step 5: Verify compilation + full test suite**

```bash
export JAVA_HOME="$(/usr/libexec/java_home)"
MVN="/Users/phoebej/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
"$MVN" -o -f SwissKit/pom.xml test
```
Expected: BUILD SUCCESS, all tests green (existing + the 4 `BuildInfoTest`).

- [ ] **Step 6: Commit**

```
git add SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java SwissKit/src/main/java/fan/summer/ui/MainWindow.java
git commit -m "✨ feat(ui): wire About sidebar item to the AboutDialog"
```

---

## Task 5: Manual verification

**Goal:** Confirm the dialog renders correctly both when launched from the IDE (dev fallback) and from the packaged fat JAR (real values), and that links open. No automated test — record observations.

- [ ] **Step 1: IDEA direct run (dev fallback)**

Run `fan.summer.app.SwissKitJApp` from IDEA. Click **About** in the sidebar.
Expected:
- A dimmed modal overlay appears with a centered glass card.
- Version shows `(dev)`, Build Time shows `(dev build)`.
- Author / Repository / Documentation / License rows are correct.
- Clicking **Repository** and **Documentation** opens the browser to the right URLs.
- × button, Esc, and clicking outside the card all close the dialog.

Record: ✓ / ✗ and notes.

- [ ] **Step 2: Packaged JAR run (real build values)**

Build + run:
```bash
export JAVA_HOME="$(/usr/libexec/java_home)"
MVN="/Users/phoebej/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
"$MVN" -o -f SwissKitJ-Api/pom.xml install -DskipTests
"$MVN" -o -f SwissKit/pom.xml package -DskipTests
"$JAVA_HOME/bin/java" -jar SwissKit/target/SwissKitJ-3.1.0.jar
```
Click **About**. Expected:
- Version = `3.1.0`.
- Build Time = a real timestamp in `yyyy-MM-dd HH:mm z` format matching the build.
- Everything else as in Step 1.

Record: ✓ / ✗ and notes.

- [ ] **Step 3: Record results in this plan**

Append a `## Smoke Results` section below with the date and per-step ✓/✗ + notes.

- [ ] **Step 4: Commit the recorded results**

```
git add docs/superpowers/plans/2026-06-22-about-button.md
git commit -m "✅ test(ui): record About dialog manual-verification results"
```

---

## Self-Review (completed by plan author)

**Spec coverage:**
- §1 modal dialog → Task 3 + Task 4 wiring. ✓
- §1 GPLv3 license value → Task 3 `LICENSE` constant. ✓
- §1 real version/build time → Task 1 filtering + Task 5 packaged-JAR check. ✓
- §2 content values (author/repo/docs) → Task 3 constants (verified against repo). ✓
- §3 `Sidebar` `onAboutSelect` + item → Task 4 Steps 1–3. ✓
- §3 `MainWindow` wiring → Task 4 Step 4. ✓
- §3 `AboutDialog` → Task 3. ✓
- §3 `BuildInfo` → Task 1. ✓
- §3 `build-info.properties` template + pom filtering → Task 1 Steps 1–3. ✓
- §3 i18n keys in both locale files → Task 2. ✓
- §5 dev-run fallback (missing / `${` placeholder) → `BuildInfo.value` + Task 1 tests `fallsBackWhenKeyMissing` / `fallsBackWhenUnfilteredPlaceholderRemains`. ✓
- §6 click-outside / Esc / × dismiss → `AboutDialog` root handler + Esc handler + × button. ✓
- §6 `Desktop.browse` failure tolerated → `AboutDialog.browse` try/catch. ✓
- §7 unit tests for `BuildInfo` → Task 1. ✓
- §7 manual verification (IDEA + JAR) → Task 5. ✓

**Placeholder scan:** No placeholders — every code step contains complete, compilable Java and exact commands. No "TBD"/"TODO"/"add error handling"/marker tokens.

**Type/name consistency:**
- `BuildInfo.{getVersion, getBuildTime, value, DEV_VERSION, DEV_BUILD_TIME, INSTANCE}` — identical across Task 1 (impl + test) and Task 3 (dialog reads `getVersion`/`getBuildTime`).
- `AboutDialog(Stage)` ctor + `show()` — identical in Task 3 (impl) and Task 4 (`new AboutDialog(stage).show()`).
- `Sidebar.setOnAboutSelect(Runnable)` + `addAboutItem(String,String)` — identical in Task 4 Steps 1–4.
- i18n keys (`sidebar.label.about`, `about.title`, `about.field.*`) — identical across Task 2 and Task 3 dialog reads.
- `MainWindow.stage` field (verified to exist at line 53) is the owner passed to `AboutDialog`.

**JavaFX layout-pitfall check (per CLAUDE.md):**
- No `setPrefWidth(Double.MAX_VALUE)`. ✓
- "Fill the rest" uses `setMaxWidth(Double.MAX_VALUE)` + `HBox.setHgrow(..., Priority.ALWAYS)`. ✓
- No `maxWidthProperty` bound to own width. ✓
- The card reuses the existing `.glass-dialog` class (no shell class like `.sidebar` repurposed). ✓
- Dialog opens its own Scene → calls `Themes.applyTo(scene)` (per CLAUDE.md). ✓

**Known follow-ups (out of scope):**
- Fix `README.md` MIT badge / "## License" to match the real GPLv3 `LICENSE`.
- Fix `README.md` "Java-17" badge → Java 21.
