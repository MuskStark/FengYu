package fan.summer.fengyu.ai.tools;

import java.awt.AWTException;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ComputerDriver} on top of {@code java.awt.Robot} — the same mechanism used by
 * reference computer-use implementations. Injects real system input and captures the real
 * screen, so on macOS the host needs Accessibility (input) and Screen Recording (capture)
 * permissions; on Linux it needs a display and an accessible X server.
 *
 * <p>Robot is created lazily once and the availability verdict is cached: probing must not
 * throw at bean construction (the tool registers in degraded mode instead, like the browser
 * bridge client).
 */
final class AwtComputerDriver implements ComputerDriver {

    private static final int SETTLE_MS = 80;
    private static final int CLICK_GAP_MS = 60;
    private static final int DRAG_STEP_MS = 15;
    private static final int KEY_GAP_MS = 12;
    /** Above this length, typing switches from keystrokes to clipboard paste for speed. */
    private static final int MAX_KEYSTROKE_CHARS = 1_000;

    private final String osName = System.getProperty("os.name");
    private volatile Robot robot;
    private volatile String unavailableReason = "not probed yet";

    static ComputerDriver create() {
        return new AwtComputerDriver();
    }

    private AwtComputerDriver() {}

    private Robot robot() {
        Robot current = robot;
        if (current != null) return current;
        synchronized (this) {
            if (robot != null) return robot;
            try {
                if (GraphicsEnvironment.isHeadless()) {
                    throw new HeadlessException("no attached display");
                }
                Robot created = new Robot();
                unavailableReason = null;
                robot = created;
                return created;
            } catch (AWTException | HeadlessException | SecurityException e) {
                unavailableReason = "screen control unavailable: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                throw new IllegalStateException(unavailableReason);
            }
        }
    }

    private void probe() {
        if (!available()) throw new IllegalStateException("computer use unavailable: " + unavailableReason());
    }

    @Override
    public boolean available() {
        if (robot != null) return true;
        try {
            robot();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String unavailableReason() {
        if (robot != null) return null;
        return "not probed yet".equals(unavailableReason) ? "screen control unavailable" : unavailableReason;
    }

    @Override
    public List<DisplayInfo> displays() {
        probe();
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        GraphicsDevice primary = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        List<DisplayInfo> out = new ArrayList<>();
        for (int i = 0; i < devices.length; i++) {
            GraphicsConfiguration config = devices[i].getDefaultConfiguration();
            Rectangle bounds = config.getBounds();
            double scale = config.getDefaultTransform().getScaleX();
            out.add(new DisplayInfo(i, devices[i] == primary, bounds.x, bounds.y,
                    bounds.width, bounds.height, scale));
        }
        return out;
    }

    @Override
    public Capture capture(Rectangle logicalRect) {
        probe();
        BufferedImage image = robot().createScreenCapture(logicalRect);
        double scale = logicalRect.width == 0 ? 1.0 : (double) image.getWidth() / logicalRect.width;
        byte[] png;
        try {
            png = ComputerDriver.toPng(image);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode screenshot: " + safeMsg(e));
        }
        return new Capture(png, image.getWidth(), image.getHeight(), logicalRect, scale);
    }

    @Override
    public CursorPosition mousePosition() {
        probe();
        Point location = MouseInfo.getPointerInfo().getLocation();
        return new CursorPosition(location.x, location.y);
    }

    @Override
    public void mouseMove(int x, int y) {
        probe();
        robot().mouseMove(x, y);
        robot().delay(SETTLE_MS);
    }

    @Override
    public void mouseClick(Integer x, Integer y, String button, boolean doubleClick) {
        probe();
        int mask = switch (button == null ? "left" : button.toLowerCase()) {
            case "left" -> InputEvent.BUTTON1_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
            default -> throw new IllegalArgumentException(
                    "unknown button '" + button + "' (use left, right, or middle)");
        };
        Robot bot = robot();
        if (x != null && y != null) {
            bot.mouseMove(x, y);
            bot.delay(SETTLE_MS);
        }
        int clicks = doubleClick ? 2 : 1;
        for (int i = 0; i < clicks; i++) {
            bot.mousePress(mask);
            bot.delay(CLICK_GAP_MS);
            bot.mouseRelease(mask);
            if (i + 1 < clicks) bot.delay(CLICK_GAP_MS);
        }
        bot.delay(SETTLE_MS);
    }

    @Override
    public void mouseDrag(int fromX, int fromY, int toX, int toY) {
        probe();
        Robot bot = robot();
        bot.mouseMove(fromX, fromY);
        bot.delay(SETTLE_MS);
        bot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        bot.delay(SETTLE_MS);
        // Interpolate: many apps only register a drag as a sequence of small moves.
        int steps = Math.max(2, Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY)) / 20);
        for (int i = 1; i <= steps; i++) {
            bot.mouseMove(fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps);
            bot.delay(DRAG_STEP_MS);
        }
        bot.mouseMove(toX, toY);
        bot.delay(DRAG_STEP_MS);
        bot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        bot.delay(SETTLE_MS);
    }

    @Override
    public void scroll(String direction, int amount, Integer x, Integer y) {
        probe();
        String dir = direction == null ? "down" : direction.toLowerCase();
        int notches = switch (dir) {
            case "down" -> Math.abs(amount);
            case "up" -> -Math.abs(amount);
            default -> throw new IllegalArgumentException(
                    "unknown direction '" + direction + "' (use up or down)");
        };
        Robot bot = robot();
        if (x != null && y != null) {
            bot.mouseMove(x, y);
            bot.delay(SETTLE_MS);
        }
        bot.mouseWheel(notches);
        bot.delay(SETTLE_MS);
    }

    @Override
    public void typeText(String text) {
        probe();
        if (text == null || text.isEmpty()) return;
        if (ComputerKeyMap.typeable(text) && text.length() <= MAX_KEYSTROKE_CHARS) {
            Robot bot = robot();
            for (int i = 0; i < text.length(); i++) {
                ComputerKeyMap.Stroke stroke = ComputerKeyMap.strokeFor(text.charAt(i));
                if (stroke.shift()) bot.keyPress(KeyEvent.VK_SHIFT);
                bot.keyPress(stroke.keyCode());
                bot.delay(KEY_GAP_MS);
                bot.keyRelease(stroke.keyCode());
                if (stroke.shift()) bot.keyRelease(KeyEvent.VK_SHIFT);
                bot.delay(KEY_GAP_MS);
            }
            bot.delay(SETTLE_MS);
        } else {
            // Non-ASCII (or very long) text: paste from the clipboard. Layout-independent, and
            // the only reliable way to type CJK/dead-key characters through Robot.
            setClipboard(text);
            pressKeys(ComputerKeyMap.isMac(osName) ? "cmd+v" : "ctrl+v");
        }
    }

    @Override
    public void pressKeys(String combo) {
        probe();
        ComputerKeyMap.KeyCombo parsed = ComputerKeyMap.parse(combo, osName);
        Robot bot = robot();
        for (int modifier : parsed.modifiers()) bot.keyPress(modifier);
        bot.delay(KEY_GAP_MS);
        bot.keyPress(parsed.keyCode());
        bot.delay(KEY_GAP_MS);
        bot.keyRelease(parsed.keyCode());
        for (int i = parsed.modifiers().size() - 1; i >= 0; i--) {
            bot.keyRelease(parsed.modifiers().get(i));
        }
        bot.delay(SETTLE_MS);
    }

    /** Clipboard writes officially belong on the EDT; Robot input does not. */
    private static void setClipboard(String text) {
        try {
            java.awt.EventQueue.invokeAndWait(() ->
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(text), null));
        } catch (Exception e) {
            throw new IllegalStateException("clipboard unavailable for paste: " + safeMsg(e));
        }
    }

    private static String safeMsg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
