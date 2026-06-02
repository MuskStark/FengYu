# Resizable Window & Responsive Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable edge/corner drag-to-resize for the undecorated JavaFX window and make internal UI respond to window size changes in real-time.

**Architecture:** A standalone `WindowResizeHelper` utility handles all resize detection (8 directions, 6px hot-zone) and stage mutation. Layout fixes in `MainWindow` and `ContentArea` ensure the scene graph stretches to fill available space. The sidebar and detail panel keep fixed widths; only the content area stretches.

**Tech Stack:** JavaFX 21+, no new dependencies.

**Build:** Use IDEA MCP tools (`mcp__idea__build_project`) for compilation. No system Maven available.

---

## File Structure

| File | Responsibility | Status |
|------|---------------|--------|
| `SwissKit/src/main/java/fan/summer/ui/util/WindowResizeHelper.java` | Edge/corner detection + stage resize on drag | **New** |
| `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` | Mount resize helper after window display | Modify |
| `SwissKit/src/main/java/fan/summer/ui/MainWindow.java` | Fix windowPane to fill parent StackPane | Modify |
| `SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java` | Dynamic wrap length, fill constraints | Modify |

---

### Task 1: Create WindowResizeHelper

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ui/util/WindowResizeHelper.java`

- [ ] **Step 1: Create the WindowResizeHelper class**

```java
package fan.summer.ui.util;

import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Attaches edge and corner resize behaviour to an undecorated JavaFX Stage.
 * <p>
 * Call {@link #attach(Stage, Region)} once after the stage is shown. The helper
 * registers mouse listeners on the root region for an 8-direction resize hot-zone
 * (6 px from each edge/corner). Dragging mutates the stage's position and size
 * directly, respecting minWidth / minHeight constraints.
 */
public final class WindowResizeHelper {

    private static final int BORDER_WIDTH = 6;

    private WindowResizeHelper() {}

    /**
     * Attaches resize detection to the given stage via the root region.
     *
     * @param stage the Stage to resize
     * @param root  the root Region of the scene (mouse events are captured here)
     */
    public static void attach(Stage stage, Region root) {
        ResizeHandler handler = new ResizeHandler(stage, root);
        root.setOnMouseMoved(handler::onMove);
        root.setOnMousePressed(handler::onPress);
        root.setOnMouseDragged(handler::onDrag);
        root.setOnMouseReleased(handler::onRelease);
    }

    // ── Internal state ────────────────────────────────────────

    private enum Direction {
        NONE, N, S, E, W, NW, NE, SW, SE
    }

    private static final class ResizeHandler {
        private final Stage stage;
        private final Region root;

        private Direction direction = Direction.NONE;
        private double dragStartX, dragStartY;   // screen coords at press
        private double startStageX, startStageY;  // stage position at press
        private double startStageW, startStageH;  // stage size at press

        ResizeHandler(Stage stage, Region root) {
            this.stage = stage;
            this.root = root;
        }

        // ── Mouse moved: update cursor ──────────────────────────

        void onMove(MouseEvent e) {
            if (stage.isMaximized()) {
                root.setCursor(Cursor.DEFAULT);
                return;
            }
            Direction d = detect(e);
            root.setCursor(cursorFor(d));
        }

        // ── Mouse pressed: lock start state ─────────────────────

        void onPress(MouseEvent e) {
            if (stage.isMaximized()) return;
            Direction d = detect(e);
            if (d == Direction.NONE) return;
            direction = d;
            dragStartX = e.getScreenX();
            dragStartY = e.getScreenY();
            startStageX = stage.getX();
            startStageY = stage.getY();
            startStageW = stage.getWidth();
            startStageH = stage.getHeight();
            e.consume();
        }

        // ── Mouse dragged: resize / move stage ──────────────────

        void onDrag(MouseEvent e) {
            if (direction == Direction.NONE) return;
            if (stage.isMaximized()) return;

            double dx = e.getScreenX() - dragStartX;
            double dy = e.getScreenY() - dragStartY;
            double minWidth  = stage.getMinWidth();
            double minHeight = stage.getMinHeight();

            double newX = startStageX;
            double newY = startStageY;
            double newW = startStageW;
            double newH = startStageH;

            switch (direction) {
                case N -> {
                    newH = startStageH - dy;
                    if (newH < minHeight) { newH = minHeight; dy = startStageH - minHeight; }
                    newY = startStageY + dy;
                }
                case S -> {
                    newH = startStageH + dy;
                    if (newH < minHeight) newH = minHeight;
                }
                case W -> {
                    newW = startStageW - dx;
                    if (newW < minWidth) { newW = minWidth; dx = startStageW - minWidth; }
                    newX = startStageX + dx;
                }
                case E -> {
                    newW = startStageW + dx;
                    if (newW < minWidth) newW = minWidth;
                }
                case NW -> {
                    newW = startStageW - dx;
                    newH = startStageH - dy;
                    if (newW < minWidth) { newW = minWidth; dx = startStageW - minWidth; }
                    if (newH < minHeight) { newH = minHeight; dy = startStageH - minHeight; }
                    newX = startStageX + dx;
                    newY = startStageY + dy;
                }
                case NE -> {
                    newW = startStageW + dx;
                    newH = startStageH - dy;
                    if (newW < minWidth) newW = minWidth;
                    if (newH < minHeight) { newH = minHeight; dy = startStageH - minHeight; }
                    newY = startStageY + dy;
                }
                case SW -> {
                    newW = startStageW - dx;
                    newH = startStageH + dy;
                    if (newW < minWidth) { newW = minWidth; dx = startStageW - minWidth; }
                    if (newH < minHeight) newH = minHeight;
                    newX = startStageX + dx;
                }
                case SE -> {
                    newW = startStageW + dx;
                    newH = startStageH + dy;
                    if (newW < minWidth) newW = minWidth;
                    if (newH < minHeight) newH = minHeight;
                }
                default -> { /* NONE — do nothing */ }
            }

            // Clamp so window doesn't go off-screen left/top
            newX = Math.max(0, newX);
            newY = Math.max(0, newY);

            stage.setX(newX);
            stage.setY(newY);
            stage.setWidth(newW);
            stage.setHeight(newH);
            e.consume();
        }

        // ── Mouse released: reset ───────────────────────────────

        void onRelease(MouseEvent e) {
            direction = Direction.NONE;
        }

        // ── Direction detection ─────────────────────────────────

        private Direction detect(MouseEvent e) {
            double w = root.getWidth();
            double h = root.getHeight();
            double x = e.getX();
            double y = e.getY();
            int b = BORDER_WIDTH;

            boolean top    = y < b;
            boolean bottom = y > h - b;
            boolean left   = x < b;
            boolean right  = x > w - b;

            if (top    && left)  return Direction.NW;
            if (top    && right) return Direction.NE;
            if (bottom && left)  return Direction.SW;
            if (bottom && right) return Direction.SE;
            if (top)    return Direction.N;
            if (bottom) return Direction.S;
            if (left)   return Direction.W;
            if (right)  return Direction.E;
            return Direction.NONE;
        }

        private static Cursor cursorFor(Direction d) {
            return switch (d) {
                case N, S  -> Cursor.V_RESIZE;
                case E, W  -> Cursor.H_RESIZE;
                case NW, SE -> Cursor.NW_RESIZE;
                case NE, SW -> Cursor.NE_RESIZE;
                default     -> Cursor.DEFAULT;
            };
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Use IDEA MCP: `mcp__idea__build_project` with projectPath `/Users/phoebej/Develop/Java/SwissKitJ`
Expected: Build succeeds with no errors.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/util/WindowResizeHelper.java
git commit -m "✨ feat(ui): add WindowResizeHelper for edge/corner drag resize"
```

---

### Task 2: Mount WindowResizeHelper in SwissKitJApp

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` (after line 128, `stage.show()`)

- [ ] **Step 1: Add import and attach call**

Add import at top of file with other imports:
```java
import fan.summer.ui.util.WindowResizeHelper;
```

Insert after `stage.show();` (line 128), before the plugin loader start:
```java
        // ── Window resize (edge/corner drag) ────────────────
        WindowResizeHelper.attach(stage, mainWindow);
```

- [ ] **Step 2: Build to verify compilation**

Use IDEA MCP: `mcp__idea__build_project` with projectPath `/Users/phoebej/Develop/Java/SwissKitJ`
Expected: Build succeeds.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java
git commit -m "✨ feat(ui): mount WindowResizeHelper on main window"
```

---

### Task 3: Fix MainWindow layout to fill parent

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/MainWindow.java` — `buildScene()` method

- [ ] **Step 1: Update buildScene() — windowPane fill + alignment**

In `buildScene()`, after the `windowPane` BorderPane is created (line 123), add max-size constraints:

```java
        windowPane.setMaxWidth(Double.MAX_VALUE);
        windowPane.setMaxHeight(Double.MAX_VALUE);
```

Remove the TOP_LEFT alignment for windowPane (line 164):
```java
        // BEFORE:
        setAlignment(windowPane, Pos.TOP_LEFT);
        // AFTER: (remove this line entirely)
```

The StackPane default center alignment will stretch the BorderPane to fill. Keep the `setAlignment(topHighlight, Pos.CENTER)` line — it is correct.

- [ ] **Step 2: Build to verify compilation**

Use IDEA MCP: `mcp__idea__build_project` with projectPath `/Users/phoebej/Develop/Java/SwissKitJ`
Expected: Build succeeds.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/MainWindow.java
git commit -m "🐛 fix(ui): make windowPane fill parent StackPane for responsive layout"
```

---

### Task 4: Make ContentArea responsive

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java`

- [ ] **Step 1: Dynamic prefWrapLength binding in buildScrollPane()**

In `buildScrollPane()` method (around line 272–293), replace the fixed `setPrefWrapLength(600)`:

```java
        // BEFORE:
        toolGrid.setPrefWrapLength(600);

        // AFTER:
        toolGrid.prefWrapLengthProperty().bind(
            sp.viewportBoundsProperty().map(b -> b.getWidth() - 32)
        );
```

Note: This bind statement must come **after** the `ScrollPane sp = new ScrollPane(wrapper);` line. Move the `toolGrid` wrap-length setup to after `sp` is created, or restructure slightly:

```java
    private ScrollPane buildScrollPane() {
        // Tool grid
        toolGrid.setHgap(10);
        toolGrid.setVgap(10);
        toolGrid.setPadding(new Insets(16));

        VBox wrapper = new VBox(
            sectionHeader("content.section.frequent", ""),
            toolGrid
        );
        wrapper.setPadding(new Insets(8, 16, 16, 16));
        wrapper.setSpacing(0);

        ScrollPane sp = new ScrollPane(wrapper);
        sp.getStyleClass().add("content-scroll");
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        sp.setMaxWidth(Double.MAX_VALUE);
        sp.setMaxHeight(Double.MAX_VALUE);

        // Dynamic wrap length: viewport width minus 32px wrapper padding (16 left + 16 right)
        toolGrid.prefWrapLengthProperty().bind(
            sp.viewportBoundsProperty().map(b -> b.getWidth() - 32)
        );

        return sp;
    }
```

- [ ] **Step 2: Fix pageStack alignment and center fill in buildLayout()**

In `buildLayout()` (around line 173–194), make two changes:

1. Remove the `pageStack.setAlignment(Pos.TOP_LEFT)` line (line 188):
```java
        // BEFORE:
        pageStack.setAlignment(Pos.TOP_LEFT);
        // AFTER: (remove this line)
```

2. Add fill constraints to the center StackPane:
```java
        StackPane center = new StackPane(pageStack, detailPanel);
        center.setMaxWidth(Double.MAX_VALUE);
        center.setMaxHeight(Double.MAX_VALUE);
        StackPane.setAlignment(detailPanel, Pos.TOP_RIGHT);
        detailPanel.setPickOnBounds(false);
        setCenter(center);
```

- [ ] **Step 3: Build to verify compilation**

Use IDEA MCP: `mcp__idea__build_project` with projectPath `/Users/phoebej/Develop/Java/SwissKitJ`
Expected: Build succeeds.

- [ ] **Step 4: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java
git commit -m "✨ feat(ui): responsive tool grid and content area fill"
```

---

### Task 5: Manual verification

**Files:** None — runtime testing only.

- [ ] **Step 1: Run the application**

Run via IDEA MCP: `mcp__idea__execute_run_configuration` or run the main class `fan.summer.Launcher`.

- [ ] **Step 2: Verify resize behaviour**

Check all of the following:
1. Move mouse to any edge (top/bottom/left/right) — cursor changes to resize arrow
2. Move mouse to any corner — cursor changes to diagonal resize arrow
3. Drag each edge — window resizes in that direction
4. Drag each corner — window resizes in both directions
5. Shrink to minimum — window stops at 800×520
6. Title bar drag still moves the window (no conflict)
7. Maximize via green traffic light still works
8. Demaximize via green traffic light restores previous size

- [ ] **Step 3: Verify responsive layout**

Check all of the following:
1. Window at 960×620 — tool grid shows cards in normal layout
2. Stretch window wider — more cards fit on one row, grid reflows
3. Stretch window taller — more rows visible without scrolling
4. Shrink window — cards wrap to fewer per row
5. Open AI Chat — chat view stretches to fill content area width
6. Open Settings — settings page fills content area
7. Open Plugin Store — store page fills content area
8. Click a tool card — detail panel slides in from right at same fixed width
9. Launch a tool — tool view fills the content area

- [ ] **Step 4: Fix any issues found and commit**

If any issues are found during testing, fix them and commit with an appropriate message.
