# Resizable Window & Responsive Layout Design

## Goal

Make the main window freely resizable via edge/corner drag, and ensure all internal UI responds to window size changes in real-time.

## Decisions

| Decision | Choice |
|----------|--------|
| Resize mechanism | Edge + corner drag (8 directions, 6px hot-zone) |
| Sidebar response | Fixed width (200–260px), no scaling |
| Detail panel response | Fixed width (260px), overlay from right — keep existing behavior |
| Window size persistence | No — always start at 960×620 |

## Components

### 1. WindowResizeHelper (new class)

**File**: `fan.summer.ui.util.WindowResizeHelper`

A standalone utility class that attaches edge/corner resize detection to a `Stage` via a root `Region`.

**Mechanism**:
- Register `MOUSE_MOVED` and `MOUSE_PRESSED` + `MOUSE_DRAGGED` on the root node
- 6px hot-zone along all 4 edges and 4 corners
- Cursor changes: `N_RESIZE`, `S_RESIZE`, `E_RESIZE`, `W_RESIZE`, `NW_RESIZE`, `NE_RESIZE`, `SW_RESIZE`, `SE_RESIZE`
- During drag: directly mutate `stage.x`, `stage.y`, `stage.width`, `stage.height`
- Always respect `stage.minWidth` (800) and `stage.minHeight` (520)
- Does not conflict with TitleBar drag-to-move (titlebar is top-center, resize hot-zone is edges only)

**Public API**:
```java
public static void attach(Stage stage, Region root)
```

**Mounting** (in `SwissKitJApp.start()`, after `stage.show()`):
```java
WindowResizeHelper.attach(stage, mainWindow);
```

### 2. MainWindow Layout Fixes

**File**: `fan.summer.ui.MainWindow` — `buildScene()`

Current issue: `windowPane` (BorderPane) is placed in a StackPane with `Pos.TOP_LEFT`, so it does not stretch to fill the parent.

Fix:
- Remove `setAlignment(windowPane, Pos.TOP_LEFT)` — use default center alignment so StackPane stretches it
- Add `windowPane.setMaxWidth(Double.MAX_VALUE)` and `windowPane.setMaxHeight(Double.MAX_VALUE)` so the BorderPane can grow beyond its preferred size
- The `app-root` CSS class only sets transparent background, no size constraints — no CSS changes needed

### 3. ContentArea Responsive Grid

**File**: `fan.summer.ui.content.ContentArea`

Changes:
- `toolGrid.prefWrapLength`: change from fixed `600` to a dynamic binding. The wrapper VBox has `padding(8,16,16,16)`, so bind to `scrollPane.viewportBounds.widthProperty() - 32` (16px left + 16px right from wrapper padding). This ensures cards wrap at the correct boundary as the window resizes.
- `scrollPane` (tool grid ScrollPane): add `setMaxWidth(Double.MAX_VALUE)` and `setMaxHeight(Double.MAX_VALUE)` so it stretches inside the StackPane.
- `pageStack` alignment: remove `Pos.TOP_LEFT` constraint, let it fill naturally.
- `center` StackPane: add `setMaxWidth(Double.MAX_VALUE)` and `setMaxHeight(Double.MAX_VALUE)`.

### 4. Tool/Plugin View Compatibility

**No forced changes to existing tool views.** The `pageScrollPane` already has:
- `setFitToWidth(true)` + `setFitToHeight(true)` — ScrollPane tries to resize content to viewport
- `setMaxWidth(Double.MAX_VALUE)` + `setMaxHeight(Double.MAX_VALUE)` — ScrollPane can grow

Tools that already use `VBox.setVgrow(node, Priority.ALWAYS)` and `setMaxWidth(Double.MAX_VALUE)` (like AI Chat, Excel Splitter) will automatically fill the available space. Tools that don't will display correctly but won't stretch to full width — no layout breakage.

## Files Changed

| File | Change |
|------|--------|
| `fan/summer/ui/util/WindowResizeHelper.java` | **New** — resize detection + drag logic |
| `fan/summer/app/SwissKitJApp.java` | Add `WindowResizeHelper.attach(stage, mainWindow)` after `stage.show()` |
| `fan/summer/ui/MainWindow.java` | Fix `windowPane` to fill parent StackPane |
| `fan/summer/ui/content/ContentArea.java` | Dynamic `prefWrapLength` binding, fill constraints on scrollPanes and center stack |

## Out of Scope

- Window size/position persistence
- Sidebar collapse/expand on narrow windows
- Detail panel layout changes
- Modifying existing tool/plugin view code
- Maximizing the window (green traffic light toggle already works via `stage.setMaximized()`)
