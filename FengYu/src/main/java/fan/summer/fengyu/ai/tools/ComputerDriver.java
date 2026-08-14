package fan.summer.fengyu.ai.tools;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Screen/input surface behind the {@code computer_*} tools. Implemented by
 * {@link AwtComputerDriver} on top of {@code java.awt.Robot}; fakes implement it in unit
 * tests so CI never needs a display.
 *
 * <p>All coordinates are <em>logical</em> screen points in the shared multi-display space
 * (the same space {@code computer_displays} reports bounds in and {@code java.awt.Robot}
 * consumes) — as opposed to captured-image pixels, which may differ on Hi-DPI screens.
 */
interface ComputerDriver {

    /** One attached display and its bounds in the shared logical coordinate space. */
    record DisplayInfo(int index, boolean primary, int x, int y, int width, int height,
                       double scale) {}

    /** A captured screen region: PNG bytes plus image-pixel and logical dimensions. */
    record Capture(byte[] png, int imageWidth, int imageHeight, Rectangle logical,
                   double scale) {}

    /** True when the platform can drive the screen (display present, Robot created). */
    boolean available();

    /** Why the driver is unavailable; {@code null} when {@link #available()} is true. */
    String unavailableReason();

    List<DisplayInfo> displays();

    /** Captures a logical-coordinate region of the screen as PNG bytes. */
    Capture capture(Rectangle logicalRect);

    record CursorPosition(int x, int y) {}

    CursorPosition mousePosition();

    void mouseMove(int x, int y);

    /** Clicks at the current position (or {@code x}/{@code y} when given): left/right/middle. */
    void mouseClick(Integer x, Integer y, String button, boolean doubleClick);

    void mouseDrag(int fromX, int fromY, int toX, int toY);

    /** Scrolls {@code amount} wheel notches up or down, optionally at a position. */
    void scroll(String direction, int amount, Integer x, Integer y);

    /** Types text via per-character keystrokes, or clipboard paste for non-ASCII text. */
    void typeText(String text);

    /** Presses one key or a modifier combo such as {@code cmd+s}. */
    void pressKeys(String combo);

    /** Renders a captured region to PNG (kept here so tests never touch ImageIO). */
    static byte[] toPng(BufferedImage image) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
