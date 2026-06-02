package fan.summer.ui.util;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Attaches edge and corner resize behaviour to an undecorated JavaFX Stage.
 * <p>
 * Call {@link #attach(Stage)} once after the stage is shown. The helper
 * registers scene-level event filters for an 8-direction resize hot-zone
 * (6 px from each edge/corner). Dragging mutates the stage's position and
 * size directly, respecting minWidth / minHeight constraints.
 */
public final class WindowResizeHelper {

    private static final int BORDER_WIDTH = 6;

    private WindowResizeHelper() {}

    /**
     * Attaches resize detection to the given stage via its Scene.
     *
     * @param stage the Stage to resize (must have a Scene set)
     */
    public static void attach(Stage stage) {
        ResizeHandler handler = new ResizeHandler(stage);
        Scene scene = stage.getScene();
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, handler::onMove);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, handler::onPress);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, handler::onDrag);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, handler::onRelease);
    }

    private enum Direction {
        NONE, N, S, E, W, NW, NE, SW, SE
    }

    private static final class ResizeHandler {
        private final Stage stage;

        private Direction direction = Direction.NONE;
        private double dragStartX, dragStartY;
        private double startStageX, startStageY;
        private double startStageW, startStageH;

        ResizeHandler(Stage stage) {
            this.stage = stage;
        }

        void onMove(MouseEvent e) {
            if (stage.isMaximized()) {
                stage.getScene().setCursor(Cursor.DEFAULT);
                return;
            }
            Direction d = detect(e);
            stage.getScene().setCursor(cursorFor(d));
        }

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

        /** Uses screen coordinates relative to stage bounds — no node coordinate system issues. */
        private Direction detect(MouseEvent e) {
            double relX = e.getScreenX() - stage.getX();
            double relY = e.getScreenY() - stage.getY();
            double w = stage.getWidth();
            double h = stage.getHeight();
            int b = BORDER_WIDTH;

            boolean top    = relY < b;
            boolean bottom = relY > h - b;
            boolean left   = relX < b;
            boolean right  = relX > w - b;

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
