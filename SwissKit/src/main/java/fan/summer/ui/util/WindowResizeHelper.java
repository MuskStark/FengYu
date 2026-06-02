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

    private enum Direction {
        NONE, N, S, E, W, NW, NE, SW, SE
    }

    private static final class ResizeHandler {
        private final Stage stage;
        private final Region root;

        private Direction direction = Direction.NONE;
        private double dragStartX, dragStartY;
        private double startStageX, startStageY;
        private double startStageW, startStageH;

        ResizeHandler(Stage stage, Region root) {
            this.stage = stage;
            this.root = root;
        }

        void onMove(MouseEvent e) {
            if (stage.isMaximized()) {
                root.setCursor(Cursor.DEFAULT);
                return;
            }
            Direction d = detect(e);
            root.setCursor(cursorFor(d));
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
