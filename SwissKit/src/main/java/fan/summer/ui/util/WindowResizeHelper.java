package fan.summer.ui.util;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attaches edge and corner resize behaviour to an undecorated JavaFX Stage.
 * <p>
 * Call {@link #attach(Stage)} once after the stage is shown. The helper
 * registers scene-level event filters for an 8-direction resize hot-zone
 * (8 px from each edge/corner). Detection uses scene-local coordinates so
 * it is independent of any stage-vs-scene offset that some platforms
 * (notably macOS with {@code StageStyle.TRANSPARENT}) can introduce.
 * Dragging mutates the stage's position and size directly, respecting
 * minWidth / minHeight constraints.
 * <p>
 * Note: {@code stage.isMaximized()} is intentionally NOT consulted here
 * — JavaFX on macOS with {@code StageStyle.TRANSPARENT} returns spurious
 * {@code true} values from app start (see JDK-8253378 and related). Edge
 * drags on a truly-maximized window naturally un-maximize and resize,
 * which is the correct UX anyway.
 */
public final class WindowResizeHelper {

    private static final Logger log = LoggerFactory.getLogger(WindowResizeHelper.class);

    private static final int BORDER_WIDTH = 8;

    private WindowResizeHelper() {}

    /**
     * Attaches resize detection to the given stage via its Scene.
     *
     * @param stage the Stage to resize (must have a Scene set)
     */
    public static void attach(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) {
            log.warn("WindowResizeHelper.attach called but stage has no Scene; nothing wired");
            return;
        }
        ResizeHandler handler = new ResizeHandler(stage, scene);
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, handler::onMove);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, handler::onPress);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, handler::onDrag);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, handler::onRelease);
        log.debug("WindowResizeHelper attached: scene={}x{}",
            scene.getWidth(), scene.getHeight());
    }

    private enum Direction {
        NONE, N, S, E, W, NW, NE, SW, SE
    }

    private static final class ResizeHandler {
        private final Stage stage;
        private final Scene scene;

        private Direction direction = Direction.NONE;
        private Direction lastHover = Direction.NONE;
        private double dragStartX, dragStartY;
        private double startStageX, startStageY;
        private double startStageW, startStageH;

        ResizeHandler(Stage stage, Scene scene) {
            this.stage = stage;
            this.scene = scene;
        }

        void onMove(MouseEvent e) {
            Direction d = detect(e);
            if (d != lastHover) {
                log.debug("hover {} -> {}", lastHover, d);
                lastHover = d;
            }
            scene.setCursor(cursorFor(d));
        }

        void onPress(MouseEvent e) {
            Direction d = detect(e);
            if (d == Direction.NONE) return;
            direction = d;
            dragStartX = e.getScreenX();
            dragStartY = e.getScreenY();
            startStageX = stage.getX();
            startStageY = stage.getY();
            startStageW = stage.getWidth();
            startStageH = stage.getHeight();
            log.debug("press dir={} screen=({},{}) stage=({},{}) {}x{}",
                d, dragStartX, dragStartY,
                startStageX, startStageY, startStageW, startStageH);
            e.consume();
        }

        void onDrag(MouseEvent e) {
            if (direction == Direction.NONE) return;

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
                default -> { /* NONE */ }
            }

            newX = Math.max(0, newX);
            newY = Math.max(0, newY);

            stage.setX(newX);
            stage.setY(newY);
            stage.setWidth(newW);
            stage.setHeight(newH);
            e.consume();
        }

        void onRelease(MouseEvent e) {
            direction = Direction.NONE;
        }

        /**
         * Detect resize direction using scene-local coordinates.  This is
         * independent of any stage / scene offset (macOS TRANSPARENT stages
         * can have such an offset for the shadow margin), so it stays correct
         * regardless of platform.
         */
        private Direction detect(MouseEvent e) {
            double x = e.getSceneX();
            double y = e.getSceneY();
            double w = scene.getWidth();
            double h = scene.getHeight();
            int b = BORDER_WIDTH;

            boolean top    = y >= 0 && y < b;
            boolean bottom = y > h - b && y <= h;
            boolean left   = x >= 0 && x < b;
            boolean right  = x > w - b && x <= w;

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
                case N  -> Cursor.N_RESIZE;
                case S  -> Cursor.S_RESIZE;
                case E  -> Cursor.E_RESIZE;
                case W  -> Cursor.W_RESIZE;
                case NW -> Cursor.NW_RESIZE;
                case SE -> Cursor.SE_RESIZE;
                case NE -> Cursor.NE_RESIZE;
                case SW -> Cursor.SW_RESIZE;
                default -> Cursor.DEFAULT;
            };
        }
    }
}
