
# Plugin Preview Window — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a self-contained `PluginPreviewWindow` in SwissKitJ-Api so third-party developers can preview their plugin's UI inside a SwissKitJ-like shell without running the full application.

**Architecture:** A single public `PluginPreviewWindow` class with a Builder API creates a JavaFX Stage. Internally, package-private classes (`PreviewShell`, `PreviewSidebar`, `PreviewDetailPanel`, `PreviewToolCard`, `PreviewTitleBar`) assemble the shell layout that mimics the main app. CSS lives in `swisskit-preview.css`. All code stays within `fan.summer.api.preview` package in `SwissKitJ-Api`.

**Tech Stack:** Java 21, JavaFX (provided), existing `MdiIconUtil`/`Themes`/`IconStyle` from SwissKitJ-Api

---

### Task 1: Create preview CSS file

**Files:**
- Create: `SwissKitJ-Api/src/main/resources/css/swisskit-preview.css`

- [x] **Step 1: Write the CSS file**

Create `SwissKitJ-Api/src/main/resources/css/swisskit-preview.css`:

```css
/* ================================================================
   swisskit-preview.css — Plugin Preview Window shell styles
   Adapted subset of shell.css for the SwissKitJ-Api preview tool.
   swisskit-common.css provides the .glass-* utilities and variables.
   ================================================================ */

/* ── Root ──────────────────────────────────────────────────── */
.preview-root {
    -fx-background-color: rgba(13,14,17,0.72);
    -fx-background-radius: 20;
    -fx-border-radius: 20;
    -fx-border-color: rgba(255,255,255,0.10);
    -fx-border-width: 1;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 60, 0, 0, 20);
}

/* ── Title bar ─────────────────────────────────────────────── */
.preview-titlebar {
    -fx-background-color: rgba(255,255,255,0.025);
    -fx-border-color: rgba(255,255,255,0.10);
    -fx-border-width: 0 0 1 0;
    -fx-pref-height: 44px;
    -fx-min-height: 44px;
    -fx-padding: 0 16 0 16;
}
.preview-titlebar-title {
    -fx-text-fill: rgba(255,255,255,0.50);
    -fx-font-size: 13px;
    -fx-font-weight: 500;
}
.preview-titlebar-close {
    -fx-background-color: transparent;
    -fx-border-width: 0;
    -fx-text-fill: rgba(255,255,255,0.35);
    -fx-font-size: 14px;
    -fx-cursor: hand;
}
.preview-titlebar-close:hover {
    -fx-text-fill: rgba(255,255,255,0.85);
}

/* ── Search bar ────────────────────────────────────────────── */
.preview-search-bar {
    -fx-background-color: rgba(255,255,255,0.055);
    -fx-border-color: rgba(255,255,255,0.10);
    -fx-border-width: 1px;
    -fx-border-radius: 10px;
    -fx-background-radius: 10px;
    -fx-padding: 0 14 0 14;
    -fx-pref-height: 38px;
    -fx-spacing: 10px;
}
.preview-search-bar:focused-within {
    -fx-background-color: rgba(255,255,255,0.085);
    -fx-border-color: rgba(255,255,255,0.22);
    -fx-effect: dropshadow(gaussian, rgba(91,140,247,0.35), 10, 0, 0, 0);
}
.preview-search-field {
    -fx-background-color: transparent;
    -fx-border-width: 0;
    -fx-text-fill: rgba(255,255,255,0.92);
    -fx-prompt-text-fill: rgba(255,255,255,0.28);
    -fx-font-size: 13.5px;
}
.preview-search-field:focused { -fx-background-color: transparent; }

/* ── Sidebar ───────────────────────────────────────────────── */
.preview-sidebar {
    -fx-background-color: rgba(255,255,255,0.022);
    -fx-border-color: rgba(255,255,255,0.10);
    -fx-border-width: 0 1 0 0;
    -fx-pref-width: 190px;
    -fx-min-width: 180px;
    -fx-max-width: 220px;
    -fx-padding: 12 8 12 8;
    -fx-spacing: 2px;
}
.preview-nav-item {
    -fx-background-color: transparent;
    -fx-background-radius: 8px;
    -fx-border-color: transparent;
    -fx-border-radius: 8px;
    -fx-border-width: 1px;
    -fx-padding: 8 10 8 10;
    -fx-cursor: hand;
    -fx-alignment: center-left;
    -fx-spacing: 10px;
    -fx-pref-height: 34px;
}
.preview-nav-item-text {
    -fx-font-size: 13px;
    -fx-text-fill: rgba(255,255,255,0.55);
}
.preview-nav-item.active {
    -fx-background-color: rgba(91,140,247,0.18);
    -fx-border-color: rgba(91,140,247,0.25);
}
.preview-nav-item.active .preview-nav-item-text {
    -fx-text-fill: #5b8cf7;
    -fx-font-weight: 500;
}

/* ── Tool card ─────────────────────────────────────────────── */
.preview-tool-card {
    -fx-background-color: rgba(255,255,255,0.055);
    -fx-border-color: rgba(255,255,255,0.10);
    -fx-border-width: 1px;
    -fx-border-radius: 12px;
    -fx-background-radius: 12px;
    -fx-padding: 16 14 14 14;
    -fx-cursor: hand;
    -fx-pref-width: 152px;
    -fx-pref-height: 130px;
    -fx-spacing: 3px;
}
.preview-tool-card:hover {
    -fx-background-color: rgba(255,255,255,0.085);
    -fx-border-color: rgba(255,255,255,0.22);
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 18, 0, 0, 6);
    -fx-translate-y: -2px;
}
.preview-tool-name {
    -fx-text-fill: rgba(255,255,255,0.92);
    -fx-font-size: 13px;
    -fx-font-weight: 500;
}
.preview-tool-desc {
    -fx-text-fill: rgba(255,255,255,0.38);
    -fx-font-size: 11px;
    -fx-wrap-text: true;
}
.preview-tool-tag {
    -fx-background-color: rgba(255,255,255,0.06);
    -fx-text-fill: rgba(255,255,255,0.38);
    -fx-border-color: rgba(255,255,255,0.10);
    -fx-border-width: 1px;
    -fx-background-radius: 4px;
    -fx-border-radius: 4px;
    -fx-padding: 1 6 1 6;
    -fx-font-size: 10px;
}

/* ── Detail panel ──────────────────────────────────────────── */
.preview-detail-panel {
    -fx-background-color: rgba(20,22,28,1);
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-background-radius: 12 0 0 12;
    -fx-padding: 20 16 20 16;
    -fx-spacing: 10px;
    -fx-pref-width: 260px;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 18, 0, -4, 0);
}
.preview-launch-btn {
    -fx-background-color: #5b8cf7;
    -fx-text-fill: white;
    -fx-font-weight: 500;
    -fx-background-radius: 8px;
    -fx-border-radius: 8px;
    -fx-border-width: 0;
    -fx-pref-height: 36px;
    -fx-cursor: hand;
}
.preview-launch-btn:hover {
    -fx-background-color: #4a7bf5;
    -fx-effect: dropshadow(gaussian, rgba(91,140,247,0.4), 12, 0, 0, 3);
    -fx-translate-y: -1px;
}
.preview-launch-btn:pressed {
    -fx-translate-y: 0;
    -fx-effect: none;
}

/* ── Status bar ────────────────────────────────────────────── */
.preview-statusbar {
    -fx-background-color: rgba(255,255,255,0.015);
    -fx-border-color: rgba(255,255,255,0.10);
    -fx-border-width: 1 0 0 0;
    -fx-pref-height: 32px;
    -fx-min-height: 32px;
    -fx-padding: 0 16 0 16;
    -fx-spacing: 12px;
}

/* ── Back bar ──────────────────────────────────────────────── */
.preview-back-btn {
    -fx-text-fill: rgba(255,255,255,0.70);
    -fx-font-size: 13px;
    -fx-cursor: hand;
    -fx-padding: 4 10 4 0;
}
.preview-back-btn:hover {
    -fx-text-fill: rgba(255,255,255,1);
}
.preview-back-title {
    -fx-text-fill: rgba(255,255,255,0.90);
    -fx-font-size: 13px;
    -fx-font-weight: bold;
}

/* ── Empty state ───────────────────────────────────────────── */
.preview-empty-text {
    -fx-text-fill: rgba(255,255,255,0.28);
    -fx-font-size: 13px;
}
```

- [x] **Step 2: Verify the file exists**

```bash
ls -la SwissKitJ-Api/src/main/resources/css/swisskit-preview.css
```

- [x] **Step 3: Commit**

```bash
git add SwissKitJ-Api/src/main/resources/css/swisskit-preview.css
git commit -m "✨ feat: add swisskit-preview.css stylesheet for PluginPreviewWindow"
```

---

### Task 2: Create PreviewTitleBar

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewTitleBar.java`

- [x] **Step 1: Write PreviewTitleBar**

```java
package fan.summer.api.preview;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Simplified title bar for the preview window.
 * Shows a title label and a close button. Supports drag-to-move.
 */
class PreviewTitleBar extends HBox {

    private double dragX, dragY;

    PreviewTitleBar(String title) {
        getStyleClass().add("preview-titlebar");
        setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("preview-titlebar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label closeBtn = new Label("✕");
        closeBtn.getStyleClass().add("preview-titlebar-close");

        getChildren().addAll(titleLabel, spacer, closeBtn);
        setPadding(new Insets(0, 16, 0, 16));
    }

    /** Wire the close button and drag-to-move after the Stage is available. */
    void bindStage(Stage stage, Runnable onClose) {
        Label closeBtn = (Label) getChildren().get(2);
        closeBtn.setOnMouseClicked(e -> {
            stage.close();
            if (onClose != null) onClose.run();
        });

        setOnMousePressed(e -> {
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
    }
}
```

- [x] **Step 2: Verify compilation**

```bash
mvn compile -f SwissKitJ-Api/pom.xml
```

- [x] **Step 3: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewTitleBar.java
git commit -m "✨ feat: add PreviewTitleBar for PluginPreviewWindow"
```

---

### Task 3: Create PreviewSidebar

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewSidebar.java`

- [x] **Step 1: Write PreviewSidebar**

```java
package fan.summer.api.preview;

import fan.summer.api.ToolCategory;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Simplified sidebar showing static category labels for visual context.
 * The "Plugins" category is always highlighted since we're previewing plugins.
 */
class PreviewSidebar extends VBox {

    PreviewSidebar() {
        getStyleClass().add("preview-sidebar");

        Label sectionLabel = new Label("CATEGORIES");
        sectionLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.30); -fx-font-size: 10px;" +
            "-fx-font-weight: bold; -fx-padding: 10 10 4 10;"
        );

        getChildren().add(sectionLabel);

        for (ToolCategory cat : ToolCategory.values()) {
            Label item = new Label(categoryDisplayName(cat));
            item.getStyleClass().add("preview-nav-item");
            Label text = new Label(categoryDisplayName(cat));
            text.getStyleClass().add("preview-nav-item-text");
            // Rebuild as HBox so the style class lands on the container
            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(10);
            row.getStyleClass().add("preview-nav-item");
            row.getChildren().add(text);
            getChildren().add(row);
        }
    }

    private static String categoryDisplayName(ToolCategory cat) {
        return switch (cat) {
            case DEV   -> "Developer Tools";
            case TEXT  -> "Text Processing";
            case IMAGE -> "Image Processing";
            case NET   -> "Network Tools";
            default    -> "Other Tools";
        };
    }
}
```

- [x] **Step 2: Verify compilation**

```bash
mvn compile -f SwissKitJ-Api/pom.xml
```

- [x] **Step 3: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewSidebar.java
git commit -m "✨ feat: add PreviewSidebar for PluginPreviewWindow"
```

---

### Task 4: Create PreviewToolCard

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewToolCard.java`

- [x] **Step 1: Write PreviewToolCard**

```java
package fan.summer.api.preview;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.SwissKitJPlugin;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Simplified tool card for the preview window.
 */
class PreviewToolCard extends VBox {

    PreviewToolCard(SwissKitJPlugin plugin, Consumer<SwissKitJPlugin> onSelect) {
        getStyleClass().add("preview-tool-card");
        setSpacing(3);
        setPadding(new Insets(16, 14, 14, 14));

        // Icon
        Color iconColor = plugin.getIconStyle().getColor();
        String fillStyle = String.format("-fx-fill: rgba(%d,%d,%d,1.0);",
                (int) (iconColor.getRed() * 255),
                (int) (iconColor.getGreen() * 255),
                (int) (iconColor.getBlue() * 255));

        Text iconText = MdiIconUtil.createIcon(plugin.getMdiIcon(), 45);
        iconText.setStyle(fillStyle);

        DropShadow glow = new DropShadow();
        glow.setColor(iconColor.deriveColor(0, 1, 1, 0.75));
        glow.setRadius(12);
        glow.setSpread(0.15);
        iconText.setEffect(glow);

        StackPane iconWrap = new StackPane(iconText);
        iconWrap.setPrefSize(48, 48);
        iconWrap.setMinSize(48, 48);

        // Name
        Label nameLabel = new Label(plugin.getName());
        nameLabel.getStyleClass().add("preview-tool-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        // Description
        Label descLabel = new Label(plugin.getDescription());
        descLabel.getStyleClass().add("preview-tool-desc");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(descLabel, Priority.ALWAYS);

        // Tag
        Label tag = new Label(plugin.getType().isPlugin() ? "Plugin" : "Built-in");
        tag.getStyleClass().add("preview-tool-tag");

        getChildren().addAll(iconWrap, nameLabel, descLabel, tag);

        // Hover scale
        ScaleTransition hoverIn = new ScaleTransition(Duration.millis(150), this);
        ScaleTransition hoverOut = new ScaleTransition(Duration.millis(150), this);
        hoverIn.setToX(1.03); hoverIn.setToY(1.03);
        hoverOut.setToX(1.0); hoverOut.setToY(1.0);

        setOnMouseEntered(e -> {
            hoverOut.stop(); hoverIn.play();
            glow.setRadius(20);
            glow.setSpread(0.25);
        });
        setOnMouseExited(e -> {
            hoverIn.stop(); hoverOut.play();
            glow.setRadius(12);
            glow.setSpread(0.15);
        });

        // Click → onSelect callback
        setOnMouseClicked(e -> onSelect.accept(plugin));
        setCursor(javafx.scene.Cursor.HAND);
    }
}
```

- [x] **Step 2: Verify compilation**

```bash
mvn compile -f SwissKitJ-Api/pom.xml
```

- [x] **Step 3: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewToolCard.java
git commit -m "✨ feat: add PreviewToolCard for PluginPreviewWindow"
```

---

### Task 5: Create PreviewDetailPanel

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewDetailPanel.java`

- [x] **Step 1: Write PreviewDetailPanel**

```java
package fan.summer.api.preview;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Simplified detail panel for the preview window.
 * Slides in from the right when a tool card is clicked.
 */
class PreviewDetailPanel extends VBox {

    private static final double PANEL_WIDTH = 260;

    private final StackPane iconWrap   = new StackPane();
    private final Label     nameLabel  = new Label();
    private final Label     metaLabel  = new Label();
    private final Label     descLabel  = new Label();
    private final Label     versionVal = new Label();
    private final Label     typeVal    = new Label();
    private final Label     categoryVal = new Label();
    private final Button    launchBtn  = new Button("Launch Tool");
    private final Button    closeBtn   = new Button("✕");

    private Consumer<SwissKitJPlugin> onLaunch;
    private SwissKitJPlugin currentPlugin;
    private boolean panelOpen;

    PreviewDetailPanel() {
        getStyleClass().add("preview-detail-panel");
        setPrefWidth(PANEL_WIDTH);
        setMinWidth(PANEL_WIDTH);
        setMaxWidth(PANEL_WIDTH);
        setTranslateX(PANEL_WIDTH);

        buildUI();
        setVisible(false);
    }

    void setOnLaunch(Consumer<SwissKitJPlugin> handler) {
        this.onLaunch = handler;
    }

    void show(SwissKitJPlugin plugin) {
        this.currentPlugin = plugin;
        fillData(plugin);
        if (!panelOpen) slideIn();
    }

    void hide() {
        if (panelOpen) slideOut();
    }

    boolean isOpen() { return panelOpen; }

    private void buildUI() {
        iconWrap.setPrefSize(56, 56);
        iconWrap.setMinSize(56, 56);

        nameLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: rgba(255,255,255,0.92);");

        metaLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(255,255,255,0.28);");

        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 12.5px; -fx-text-fill: rgba(255,255,255,0.45);");

        launchBtn.getStyleClass().add("preview-launch-btn");
        launchBtn.setMaxWidth(Double.MAX_VALUE);
        launchBtn.setOnAction(e -> {
            if (onLaunch != null && currentPlugin != null)
                onLaunch.accept(currentPlugin);
        });

        closeBtn.setStyle(
            "-fx-background-color: transparent; -fx-border-width: 0;" +
            "-fx-text-fill: rgba(255,255,255,0.35); -fx-cursor: hand; -fx-font-size: 14px;"
        );
        closeBtn.setOnAction(e -> hide());
        closeBtn.setOnMouseEntered(e ->
            closeBtn.setStyle(closeBtn.getStyle() + "-fx-text-fill: rgba(255,255,255,0.85);"));
        closeBtn.setOnMouseExited(e ->
            closeBtn.setStyle(closeBtn.getStyle().replace("-fx-text-fill: rgba(255,255,255,0.85);", "")));

        HBox topRow = new HBox(closeBtn);
        topRow.setAlignment(Pos.CENTER_RIGHT);

        VBox propsBox = new VBox(6,
            propRow("Version", versionVal),
            propRow("Type", typeVal),
            propRow("Category", categoryVal)
        );
        VBox.setMargin(propsBox, new Insets(12, 0, 0, 0));

        setSpacing(10);
        setPadding(new Insets(16));
        getChildren().addAll(topRow, iconWrap, nameLabel, metaLabel, descLabel, launchBtn, propsBox);
    }

    private HBox propRow(String key, Label valLabel) {
        Label keyLabel = new Label(key);
        keyLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.30); -fx-font-size: 12px;");
        valLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px; " +
                          "-fx-font-family: 'SF Mono','Consolas',monospace;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new HBox(keyLabel, spacer, valLabel);
    }

    private void fillData(SwissKitJPlugin p) {
        Color color = p.getIconStyle().getColor();
        String fillStyle = String.format("-fx-fill: rgba(%d,%d,%d,1.0);",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));

        Text newIcon = MdiIconUtil.createIcon(p.getMdiIcon(), 50);
        newIcon.setStyle(fillStyle);

        DropShadow glow = new DropShadow();
        glow.setColor(color.deriveColor(0, 1, 1, 0.75));
        glow.setRadius(14);
        glow.setSpread(0.18);
        newIcon.setEffect(glow);

        iconWrap.getChildren().setAll(newIcon);

        nameLabel.setText(p.getName());
        metaLabel.setText("v" + p.getVersion() + " · " + p.getType().getId());
        descLabel.setText(p.getDescription());
        versionVal.setText(p.getVersion());
        typeVal.setText(p.getType().getId());
        categoryVal.setText(categoryName(p.getCategory()));
    }

    private static String categoryName(ToolCategory cat) {
        return switch (cat) {
            case DEV   -> "Developer Tools";
            case TEXT  -> "Text Processing";
            case IMAGE -> "Image Processing";
            case NET   -> "Network Tools";
            default    -> "Other Tools";
        };
    }

    private void slideIn() {
        panelOpen = true;
        setVisible(true);
        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(translateXProperty(), PANEL_WIDTH),
                new KeyValue(opacityProperty(), 0)
            ),
            new KeyFrame(Duration.millis(300),
                new KeyValue(translateXProperty(), 0, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(opacityProperty(), 1, Interpolator.EASE_OUT)
            )
        );
        tl.play();
    }

    private void slideOut() {
        panelOpen = false;
        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(translateXProperty(), 0),
                new KeyValue(opacityProperty(), 1)
            ),
            new KeyFrame(Duration.millis(250),
                new KeyValue(translateXProperty(), PANEL_WIDTH, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(opacityProperty(), 0, Interpolator.EASE_IN)
            )
        );
        tl.setOnFinished(e -> setVisible(false));
        tl.play();
    }
}
```

- [x] **Step 2: Verify compilation**

```bash
mvn compile -f SwissKitJ-Api/pom.xml
```

- [x] **Step 3: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewDetailPanel.java
git commit -m "✨ feat: add PreviewDetailPanel for PluginPreviewWindow"
```

---

### Task 6: Create PreviewShell

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewShell.java`

- [x] **Step 1: Write PreviewShell**

```java
package fan.summer.api.preview;

import fan.summer.api.SwissKitJPlugin;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the full shell layout: title bar, search bar, sidebar, content area,
 * detail panel, status bar. The public PluginPreviewWindow delegates to this.
 */
class PreviewShell extends BorderPane {

    private final List<SwissKitJPlugin> plugins = new ArrayList<>();
    private SwissKitJPlugin activePlugin;

    private final PreviewDetailPanel detailPanel = new PreviewDetailPanel();
    private final FlowPane            toolGrid    = new FlowPane();
    private final StackPane           pageStack   = new StackPane();
    private final HBox                backBar;
    private final TextField           searchField = new TextField();
    private final Label               statusLabel = new Label();
    private final PreviewTitleBar  titleBar;
    private final Node             sidebarNode;
    private final Node             searchBarNode;
    private final Node             statusBarNode;

    private final boolean showSidebar;
    private final boolean showSearchBar;
    private final boolean showStatusBar;
    private final boolean showDetailPanel;
    private final Runnable onClose;

    PreviewShell(List<SwissKitJPlugin> plugins, String title,
                 boolean showSidebar, boolean showSearchBar,
                 boolean showStatusBar, boolean showDetailPanel,
                 Runnable onClose) {
        this.plugins.addAll(plugins);
        this.showSidebar = showSidebar;
        this.showSearchBar = showSearchBar;
        this.showStatusBar = showStatusBar;
        this.showDetailPanel = showDetailPanel;
        this.onClose = onClose;

        getStyleClass().add("preview-root");
        setStyle(
            "-fx-background-color: rgba(13,14,17,0.72);" +
            "-fx-background-radius: 20;" +
            "-fx-border-radius: 20;" +
            "-fx-border-color: rgba(255,255,255,0.10);" +
            "-fx-border-width: 1;"
        );

        titleBar = new PreviewTitleBar(title);
        backBar = buildBackBar();
        backBar.setVisible(false);
        backBar.setManaged(false);

        sidebarNode = new PreviewSidebar();
        searchBarNode = buildSearchBar();
        statusBarNode = buildStatusBar();

        buildLayout();
        wireEvents();
    }

    void close() {
        for (SwissKitJPlugin p : plugins) {
            try {
                if (p == activePlugin) p.onDeactivate();
                p.onUnload();
            } catch (Exception ignored) {}
        }
        if (onClose != null) onClose.run();
    }

    // ── Layout ─────────────────────────────────────────────────

    private void buildLayout() {
        // Title bar (always visible)
        setTop(titleBar);

        // Body
        HBox body = new HBox();
        if (showSidebar) {
            body.getChildren().add(sidebarNode);
        }

        // Center: back bar + search bar + content
        VBox center = new VBox();
        center.getChildren().add(backBar);
        if (showSearchBar) {
            center.getChildren().add(searchBarNode);
            VBox.setMargin(searchBarNode, new Insets(12, 16, 0, 16));
        }

        // Content stack
        toolGrid.setHgap(10);
        toolGrid.setVgap(10);
        toolGrid.setPadding(new Insets(16));
        toolGrid.setPrefWrapLength(600);

        ScrollPane gridScroll = new ScrollPane(toolGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        gridScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        pageStack.getChildren().add(gridScroll);
        pageStack.setAlignment(Pos.TOP_LEFT);

        StackPane centerPane = new StackPane(pageStack);
        if (showDetailPanel) {
            centerPane.getChildren().add(detailPanel);
            StackPane.setAlignment(detailPanel, Pos.TOP_RIGHT);
            detailPanel.setPickOnBounds(false);
        }

        VBox.setVgrow(centerPane, Priority.ALWAYS);
        center.getChildren().add(centerPane);
        HBox.setHgrow(center, Priority.ALWAYS);
        body.getChildren().add(center);

        // Status bar
        VBox mainArea = new VBox(body);
        if (showStatusBar) {
            mainArea.getChildren().add(statusBarNode);
        }
        VBox.setVgrow(body, Priority.ALWAYS);

        setCenter(mainArea);

        // Show initial tool cards
        refreshToolGrid();
    }

    void bindStage(Scene scene) {
        javafx.stage.Stage stage = (javafx.stage.Stage) scene.getWindow();
        titleBar.bindStage(stage, onClose);
    }

    // ── Search bar ──────────────────────────────────────────────

    private Node buildSearchBar() {
        Label searchIcon = new Label("🔍");
        searchIcon.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(255,255,255,0.28);");

        searchField.getStyleClass().add("preview-search-field");
        searchField.setPromptText("Search tools...");
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshToolGrid());

        HBox bar = new HBox(10, searchIcon, searchField);
        bar.getStyleClass().add("preview-search-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(38);
        return bar;
    }

    // ── Back bar ────────────────────────────────────────────────

    private HBox buildBackBar() {
        Label backBtn = new Label("← Back");
        backBtn.getStyleClass().add("preview-back-btn");
        backBtn.setOnMouseClicked(e -> showToolGrid());

        Label sep = new Label("/");
        sep.setStyle("-fx-text-fill: rgba(255,255,255,0.25); -fx-font-size: 13px; -fx-padding: 4 6 4 0;");

        Label titleLabel = new Label();
        titleLabel.getStyleClass().add("preview-back-title");

        HBox bar = new HBox(6, backBtn, sep, titleLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(38);
        return bar;
    }

    // ── Status bar ──────────────────────────────────────────────

    private Node buildStatusBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("preview-statusbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.28); -fx-font-size: 12px;");
        bar.getChildren().add(statusLabel);
        updateStatus();
        return bar;
    }

    private void updateStatus() {
        statusLabel.setText(plugins.size() + " plugin(s) loaded");
    }

    // ── Event wiring ────────────────────────────────────────────

    private void wireEvents() {
        detailPanel.setOnLaunch(plugin -> {
            activePlugin = plugin;
            try { plugin.onActivate(); } catch (Exception ignored) {}
            showPluginView(plugin);
        });
    }

    // ── Tool grid ───────────────────────────────────────────────

    private void refreshToolGrid() {
        toolGrid.getChildren().clear();
        String query = searchField.getText().trim().toLowerCase();

        List<SwissKitJPlugin> filtered = plugins.stream()
            .filter(p -> query.isEmpty()
                || p.getName().toLowerCase().contains(query)
                || p.getDescription().toLowerCase().contains(query))
            .toList();

        for (SwissKitJPlugin p : filtered) {
            PreviewToolCard card = new PreviewToolCard(p, this::onCardSelect);
            card.setPrefWidth(152);
            card.setPrefHeight(130);
            toolGrid.getChildren().add(card);
        }

        if (filtered.isEmpty()) {
            Label empty = new Label("No matching plugins found");
            empty.getStyleClass().add("preview-empty-text");
            empty.setPadding(new Insets(40, 0, 0, 0));
            toolGrid.getChildren().add(empty);
        }
    }

    private void onCardSelect(SwissKitJPlugin plugin) {
        if (showDetailPanel) {
            detailPanel.show(plugin);
        } else {
            // No detail panel — launch directly
            activePlugin = plugin;
            try { plugin.onActivate(); } catch (Exception ignored) {}
            showPluginView(plugin);
        }
    }

    // ── Page transitions ────────────────────────────────────────

    private void showPluginView(SwissKitJPlugin plugin) {
        detailPanel.hide();

        Label titleLabel = (Label) backBar.lookup(".preview-back-title");
        if (titleLabel != null) titleLabel.setText(plugin.getName());
        backBar.setVisible(true);
        backBar.setManaged(true);

        Node view;
        try {
            view = plugin.createView();
        } catch (Exception e) {
            Label errorLabel = new Label("Error creating view:\n" + e.getMessage());
            errorLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px; -fx-padding: 20;");
            errorLabel.setWrapText(true);
            view = errorLabel;
        }

        ScrollPane pageScroll = new ScrollPane(view);
        pageScroll.setFitToWidth(true);
        pageScroll.setFitToHeight(true);
        pageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pageScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        pageScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        crossFadeTo(pageScroll);
    }

    private void showToolGrid() {
        if (activePlugin != null) {
            try { activePlugin.onDeactivate(); } catch (Exception ignored) {}
            activePlugin = null;
        }
        backBar.setVisible(false);
        backBar.setManaged(false);

        // Rebuild grid scroll
        ScrollPane gridScroll = new ScrollPane(toolGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        gridScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        crossFadeTo(gridScroll);
    }

    private void crossFadeTo(Node next) {
        Node current = pageStack.getChildren().isEmpty()
            ? null : pageStack.getChildren().get(0);

        next.setOpacity(0);
        if (!pageStack.getChildren().contains(next))
            pageStack.getChildren().add(next);
        next.toFront();

        FadeTransition fadeIn = new FadeTransition(Duration.millis(220), next);
        fadeIn.setToValue(1);

        if (current != null && current != next) {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(180), current);
            fadeOut.setToValue(0);
            Node finalCurrent = current;
            fadeOut.setOnFinished(e -> pageStack.getChildren().remove(finalCurrent));
            new javafx.animation.ParallelTransition(fadeOut, fadeIn).play();
        } else {
            fadeIn.play();
        }
    }
}
```

- [x] **Step 2: Verify compilation**

```bash
mvn compile -f SwissKitJ-Api/pom.xml
```

- [x] **Step 3: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/preview/PreviewShell.java
git commit -m "✨ feat: add PreviewShell for PluginPreviewWindow"
```

---

### Task 7: Create PluginPreviewWindow (Public API)

**Files:**
- Create: `SwissKitJ-Api/src/main/java/fan/summer/api/preview/PluginPreviewWindow.java`

- [x] **Step 1: Write PluginPreviewWindow with Builder**

```java
package fan.summer.api.preview;

import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.theme.Themes;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Preview window for third-party plugin developers.
 *
 * <p>Displays a SwissKitJ-like shell with the plugin's UI embedded,
 * so developers can verify appearance and behavior before deploying.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // From a JAR file
 * PluginPreviewWindow.configure()
 *     .withJar(Path.of("build/libs/my-plugin.jar"))
 *     .launch();
 *
 * // From a plugin instance
 * PluginPreviewWindow.configure()
 *     .withPlugin(new MyPlugin())
 *     .launch();
 *
 * // Full configuration
 * PluginPreviewWindow.configure()
 *     .withPlugin(myPlugin)
 *     .title("My Plugin — Preview")
 *     .windowSize(900, 600)
 *     .showSidebar(false)
 *     .showStatusBar(true)
 *     .launch();
 * }</pre>
 *
 * <p>Note: JavaFX must be initialized before calling {@code launch()}.
 * Call from the JavaFX Application thread or inside {@code Application.start()}.</p>
 */
public final class PluginPreviewWindow {

    private Path jarPath;
    private SwissKitJPlugin pluginInstance;
    private String title = "Plugin Preview";
    private double width = 960;
    private double height = 620;
    private boolean showSidebar = true;
    private boolean showSearchBar = true;
    private boolean showStatusBar = true;
    private boolean showDetailPanel = true;

    private PluginPreviewWindow() {}

    /** Begin configuration. */
    public static PluginPreviewWindow configure() {
        return new PluginPreviewWindow();
    }

    /** Load a plugin from a JAR file via ServiceLoader. */
    public PluginPreviewWindow withJar(Path jarPath) {
        this.jarPath = jarPath;
        return this;
    }

    /** Use an already-instantiated plugin. Takes precedence over {@link #withJar(Path)}. */
    public PluginPreviewWindow withPlugin(SwissKitJPlugin plugin) {
        this.pluginInstance = plugin;
        return this;
    }

    /** Window title. Default: "Plugin Preview". */
    public PluginPreviewWindow title(String title) {
        this.title = title;
        return this;
    }

    /** Window size. Default: 960 × 620. */
    public PluginPreviewWindow windowSize(double width, double height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** Show/hide the sidebar. Default: true. */
    public PluginPreviewWindow showSidebar(boolean show) {
        this.showSidebar = show;
        return this;
    }

    /** Show/hide the search bar. Default: true. */
    public PluginPreviewWindow showSearchBar(boolean show) {
        this.showSearchBar = show;
        return this;
    }

    /** Show/hide the status bar. Default: true. */
    public PluginPreviewWindow showStatusBar(boolean show) {
        this.showStatusBar = show;
        return this;
    }

    /** Show/hide the detail panel. Default: true. */
    public PluginPreviewWindow showDetailPanel(boolean show) {
        this.showDetailPanel = show;
        return this;
    }

    /**
     * Create the Stage and show the preview window.
     * Must be called from the JavaFX Application thread.
     */
    public void launch() {
        List<SwissKitJPlugin> loadedPlugins = new ArrayList<>();
        URLClassLoader classLoader = null;

        // Resolve plugins
        if (pluginInstance != null) {
            loadedPlugins.add(pluginInstance);
        } else if (jarPath != null) {
            try {
                classLoader = new URLClassLoader(
                    new java.net.URL[]{jarPath.toUri().toURL()},
                    getClass().getClassLoader()
                );
                ServiceLoader<SwissKitJPlugin> sl = ServiceLoader.load(SwissKitJPlugin.class, classLoader);
                for (SwissKitJPlugin p : sl) {
                    loadedPlugins.add(p);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load plugin from JAR: " + jarPath, e);
            }
        } else {
            throw new IllegalStateException("Either withJar() or withPlugin() must be configured before launch()");
        }

        if (loadedPlugins.isEmpty()) {
            if (classLoader != null) {
                try { classLoader.close(); } catch (Exception ignored) {}
            }
            throw new IllegalStateException("No SwissKitJPlugin implementation found" +
                (jarPath != null ? " in JAR: " + jarPath : ""));
        }

        // Build the window
        final URLClassLoader finalCl = classLoader;
        final List<SwissKitJPlugin> finalPlugins = List.copyOf(loadedPlugins);

        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(title);
        stage.setWidth(width);
        stage.setHeight(height);

        PreviewShell shell = new PreviewShell(
            finalPlugins, title,
            showSidebar, showSearchBar, showStatusBar, showDetailPanel,
            () -> {
                if (finalCl != null) {
                    try { finalCl.close(); } catch (Exception ignored) {}
                }
            }
        );

        Scene scene = new Scene(shell, width, height);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().addAll(
            Themes.commonStylesheetUrl(),
            PluginPreviewWindow.class.getResource("/css/swisskit-preview.css").toExternalForm()
        );

        stage.setScene(scene);

        // Wire title bar close button and drag-to-move now that we have the stage
        shell.bindStage(scene);

        stage.setOnCloseRequest(e -> shell.close());
        stage.show();
    }
}
```

- [x] **Step 2: Verify compilation**

```bash
mvn compile -f SwissKitJ-Api/pom.xml
```

- [x] **Step 3: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/preview/PluginPreviewWindow.java
git commit -m "✨ feat: add PluginPreviewWindow — self-contained plugin preview for third-party developers"
```

---

### Task 8: Build & verify the full project

**Files:** (none — verification only)

- [x] **Step 1: Install the API module**

```bash
mvn install -f SwissKitJ-Api/pom.xml -DskipTests
```

Expected: BUILD SUCCESS

- [x] **Step 2: Build the full project**

```bash
mvn clean package -DskipTests
```

Expected: BUILD SUCCESS across all modules

- [x] **Step 3: Verify the API JAR contains the new classes and CSS**

```bash
jar tf SwissKitJ-Api/target/SwissKitJ-Api-*.jar | grep -E "(preview|swisskit-preview)"
```

Expected output includes:
```
fan/summer/api/preview/PluginPreviewWindow.class
fan/summer/api/preview/PreviewShell.class
fan/summer/api/preview/PreviewSidebar.class
fan/summer/api/preview/PreviewDetailPanel.class
fan/summer/api/preview/PreviewToolCard.class
fan/summer/api/preview/PreviewTitleBar.class
css/swisskit-preview.css
```

- [x] **Step 4: Commit if any build fixes were needed**

```bash
git status
```
