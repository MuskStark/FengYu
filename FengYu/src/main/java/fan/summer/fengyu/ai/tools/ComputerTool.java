package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.runtime.RuntimePaths;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side AI tool that drives the user's REAL screen — the ChatGPT-desktop-style
 * "computer use" loop: capture the screen, look at it, inject mouse/keyboard input, verify
 * with another capture. Backed by {@link AwtComputerDriver} (java.awt.Robot) inside this
 * JVM, so no Electron round trip is involved; only registered when
 * {@code fengyu.desktop=true} (set by the Electron sidecar), exactly like
 * {@link BrowserTool}.
 *
 * <p>When no display is reachable (headless server, missing OS permissions) the bean still
 * registers and every call returns a {@code "computer use unavailable"} envelope — the same
 * degraded-mode contract as the browser bridge. macOS needs Screen Recording (capture) and
 * Accessibility (input) granted to the app; otherwise captures show wallpaper only and input
 * is dropped silently by the OS.
 *
 * <p>Approval-gated via {@link ApprovalRequiredTool}: read-only operations
 * (screenshot/displays/apps/cursor/wait) classify as {@link ToolEffect#READ}; every input
 * injection or app control is {@link ToolEffect#EXTERNAL}.
 */
@Component
@ConditionalOnProperty("fengyu.desktop")
public class ComputerTool implements ApprovalRequiredTool, ToolEffectProvider {

    /** Mirrors ToolMediaBridge.MAX_IMAGE_BYTES: larger captures stay file-only. */
    static final int MAX_INLINE_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_WAIT_SECONDS = 60;
    private static final int MAX_KEY_SEQUENCE = 50;
    private static final int MAX_KEY_INTERVAL_MS = 2_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ComputerDriver driver;
    private final ComputerApps apps;

    /** Spring constructor: probes the AWT driver; degraded mode keeps the bean registered. */
    public ComputerTool() {
        this(AwtComputerDriver.create(), new ComputerApps());
    }

    /** Test/injection constructor. */
    ComputerTool(ComputerDriver driver, ComputerApps apps) {
        this.driver = driver;
        this.apps = apps;
    }

    /** Capability status for the settings UI ({@code SettingsController}). */
    public Map<String, Object> availability() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", driver.available());
        out.put("reason", driver.available() ? null : driver.unavailableReason());
        return out;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return switch (toolName) {
            case "computer_screenshot", "computer_displays", "computer_apps",
                    "computer_cursor_position", "computer_wait" -> ToolEffect.READ;
            default -> ToolEffect.EXTERNAL;
        };
    }

    // ── AI tools ──────────────────────────────────────────────────────────────

    @Tool(name = "computer_screenshot",
          description = "Capture the REAL screen as a PNG and see it. Core of the computer-use loop: "
                  + "screenshot → decide → act → screenshot again to verify. Returns logical bounds, image "
                  + "size, and scale (on Hi-DPI screens image pixels = logical points × scale; convert before "
                  + "clicking). Defaults to the primary display; pass displayIndex from computer_displays or a "
                  + "region x/y/width/height in logical coordinates.")
    public String screenshot(
            @ToolParam(required = false, description = "Display index from computer_displays; default primary.")
            Integer displayIndex,
            @ToolParam(required = false, description = "Region left edge in logical screen coordinates.")
            Integer x,
            @ToolParam(required = false, description = "Region top edge in logical screen coordinates.")
            Integer y,
            @ToolParam(required = false, description = "Region width in logical points.") Integer width,
            @ToolParam(required = false, description = "Region height in logical points.") Integer height) {
        return run("screenshot", envelope -> {
            displaysEnvelope(envelope);
            @SuppressWarnings("unchecked")
            List<ComputerDriver.DisplayInfo> all = (List<ComputerDriver.DisplayInfo>) envelope.remove("_displays");
            ComputerDriver.DisplayInfo target = resolveDisplay(all, displayIndex);
            java.awt.Rectangle bounds =
                    new java.awt.Rectangle(target.x(), target.y(), target.width(), target.height());
            java.awt.Rectangle region = bounds;
            if (x != null || y != null || width != null || height != null) {
                region = new java.awt.Rectangle(
                        x == null ? bounds.x : x,
                        y == null ? bounds.y : y,
                        width == null ? bounds.width : width,
                        height == null ? bounds.height : height);
                region = region.intersection(bounds);
            }
            if (region.width <= 0 || region.height <= 0) {
                throw new IllegalArgumentException("region is outside display " + target.index());
            }
            ComputerDriver.Capture capture = driver.capture(region);
            if (capture.png().length > MAX_INLINE_IMAGE_BYTES) {
                throw new IllegalStateException("screenshot is " + capture.png().length
                        + " bytes; capture a smaller region");
            }
            envelope.put("display", target.index());
            envelope.put("bounds", Map.of("x", region.x, "y", region.y,
                    "width", region.width, "height", region.height));
            envelope.put("imageWidth", capture.imageWidth());
            envelope.put("imageHeight", capture.imageHeight());
            envelope.put("scale", capture.scale());
            envelope.put("mimeType", "image/png");
            envelope.put("imageBase64", Base64.getEncoder().encodeToString(capture.png()));
            envelope.put("imageBytes", capture.png().length);
            envelope.put("savedPath", saveScreenshot(capture.png()));
            ComputerDriver.CursorPosition cursor = driver.mousePosition();
            envelope.put("cursor", Map.of("x", cursor.x(), "y", cursor.y()));
            envelope.put("summary", "captured " + region.width + "x" + region.height
                    + " at (" + region.x + "," + region.y + ") as " + capture.imageWidth() + "x"
                    + capture.imageHeight() + " px (scale " + capture.scale()
                    + "); mouse tools use logical points");
        });
    }

    @Tool(name = "computer_displays",
          description = "List attached displays: logical bounds (x, y, width, height), scale, and which one is "
                  + "primary. All computer_* coordinates live in this shared logical space.")
    public String displays() {
        return run("displays", this::displaysEnvelope);
    }

    @Tool(name = "computer_apps",
          description = "List the names of foreground applications currently running, for use with "
                  + "computer_app_activate. Call computer_screenshot afterwards to see the screen state.")
    public String apps() {
        return run("apps", envelope -> {
            List<String> names = apps.list();
            envelope.put("apps", names);
            envelope.put("count", names.size());
            envelope.put("summary", names.isEmpty() ? "no foreground apps reported"
                    : names.size() + " foreground app(s): " + String.join(", ", names));
        });
    }

    @Tool(name = "computer_app_launch",
          description = "Launch an installed application by plain name on the real system, e.g. 'Safari' or "
                  + "'Calendar' (macOS), 'notepad' or 'calc' (Windows), a desktop id like 'code' (Linux). "
                  + "Follow with computer_wait and computer_screenshot to see it.")
    public String appLaunch(
            @ToolParam(description = "Application name, e.g. 'Safari'.") String app) {
        return run("app_launch", envelope -> {
            apps.launch(app);
            envelope.put("app", app.trim());
            envelope.put("summary", "launch requested for '" + app.trim()
                    + "'; wait a moment, then screenshot to verify");
        });
    }

    @Tool(name = "computer_app_activate",
          description = "Bring a running application to the foreground by name (e.g. 'Safari'), then "
                  + "screenshot to confirm focus. Use computer_apps to discover names.")
    public String appActivate(
            @ToolParam(description = "Application name from computer_apps.") String app) {
        return run("app_activate", envelope -> {
            apps.activate(app);
            envelope.put("app", app.trim());
            envelope.put("summary", "activate requested for '" + app.trim() + "'");
        });
    }

    @Tool(name = "computer_cursor_position",
          description = "Current mouse pointer position in logical screen coordinates.")
    public String cursorPosition() {
        return run("cursor_position", envelope -> {
            ComputerDriver.CursorPosition cursor = driver.mousePosition();
            envelope.put("x", cursor.x());
            envelope.put("y", cursor.y());
            envelope.put("summary", "cursor at (" + cursor.x() + ", " + cursor.y() + ")");
        });
    }

    @Tool(name = "computer_mouse_move",
          description = "Move the mouse pointer to logical screen coordinates without clicking. Useful before "
                  + "scroll or hover-sensitive UI.")
    public String mouseMove(
            @ToolParam(description = "Logical x coordinate.") Integer x,
            @ToolParam(description = "Logical y coordinate.") Integer y) {
        return run("mouse_move", envelope -> {
            driver.mouseMove(x, y);
            envelope.put("x", x);
            envelope.put("y", y);
            envelope.put("summary", "moved mouse to (" + x + ", " + y + ")");
        });
    }

    @Tool(name = "computer_click",
          description = "Click the REAL screen at logical coordinates (or the current position when omitted). "
                  + "Derive the point from a fresh computer_screenshot (divide image pixels by scale on Hi-DPI), "
                  + "never from memory. button: left (default), right, middle.")
    public String click(
            @ToolParam(required = false, description = "Logical x coordinate; default current position.")
            Integer x,
            @ToolParam(required = false, description = "Logical y coordinate; default current position.")
            Integer y,
            @ToolParam(required = false, description = "left (default), right, or middle.") String button) {
        return run("click", envelope -> {
            driver.mouseClick(x, y, button, false);
            envelope.put("button", button == null ? "left" : button);
            envelope.put("summary", "clicked " + (button == null ? "left" : button)
                    + (x != null && y != null ? " at (" + x + ", " + y + ")" : " at the current position"));
        });
    }

    @Tool(name = "computer_double_click",
          description = "Double-click the REAL screen at logical coordinates (or the current position when "
                  + "omitted) — open files, select words, expand trees.")
    public String doubleClick(
            @ToolParam(required = false, description = "Logical x coordinate; default current position.")
            Integer x,
            @ToolParam(required = false, description = "Logical y coordinate; default current position.")
            Integer y) {
        return run("double_click", envelope -> {
            driver.mouseClick(x, y, "left", true);
            envelope.put("summary", "double-clicked"
                    + (x != null && y != null ? " at (" + x + ", " + y + ")" : " at the current position"));
        });
    }

    @Tool(name = "computer_drag",
          description = "Press and drag on the REAL screen from one logical point to another — move windows, "
                  + "sliders, selections. Verify start and end points with a screenshot first.")
    public String drag(
            @ToolParam(description = "Logical x of the drag start.") Integer fromX,
            @ToolParam(description = "Logical y of the drag start.") Integer fromY,
            @ToolParam(description = "Logical x of the drag end.") Integer toX,
            @ToolParam(description = "Logical y of the drag end.") Integer toY) {
        return run("drag", envelope -> {
            driver.mouseDrag(fromX, fromY, toX, toY);
            envelope.put("summary", "dragged from (" + fromX + ", " + fromY + ") to (" + toX + ", " + toY + ")");
        });
    }

    @Tool(name = "computer_scroll",
          description = "Scroll the mouse wheel up or down by notches on the REAL screen, optionally at a "
                  + "logical position. Positive amount scrolls in the given direction; ~3 notches ≈ one screen "
                  + "page. Screenshot after scrolling to re-locate content.")
    public String scroll(
            @ToolParam(required = false, description = "down (default) or up.") String direction,
            @ToolParam(required = false, description = "Notches to scroll (default 3).") Integer amount,
            @ToolParam(required = false, description = "Logical x to scroll at; default current position.")
            Integer x,
            @ToolParam(required = false, description = "Logical y to scroll at; default current position.")
            Integer y) {
        return run("scroll", envelope -> {
            String dir = direction == null ? "down" : direction;
            int notches = amount == null ? 3 : amount;
            driver.scroll(dir, notches, x, y);
            envelope.put("direction", dir);
            envelope.put("notches", notches);
            envelope.put("summary", "scrolled " + dir + " " + notches + " notch(es)");
        });
    }

    @Tool(name = "computer_type",
          description = "Type text with the REAL keyboard into whatever currently has focus. Click the target "
                  + "field first. Plain ASCII is typed as keystrokes; other text (CJK, accents, emoji) is pasted "
                  + "from the clipboard, which replaces clipboard content.")
    public String type(
            @ToolParam(description = "Text to type.") String text) {
        return run("type", envelope -> {
            driver.typeText(text);
            envelope.put("length", text.length());
            envelope.put("summary", "typed " + text.length() + " character(s)");
        });
    }

    @Tool(name = "computer_key",
          description = "Press one key or a combo on the REAL keyboard, e.g. 'enter', 'tab', 'esc', 'space', "
                  + "'a', arrows (up/down/left/right), f1-f12, and shortcuts like 'cmd+s' (macOS) or 'ctrl+s', "
                  + "'controlormeta+a', 'shift+arrowright', 'alt+f4'. Acts on the focused app.")
    public String key(
            @ToolParam(description = "Key or combo, e.g. 'enter' or 'cmd+s'.") String key) {
        return run("key", envelope -> {
            driver.pressKeys(key);
            envelope.put("key", key);
            envelope.put("summary", "pressed " + key);
        });
    }

    @Tool(name = "computer_key_sequence",
          description = "Press an ordered sequence of REAL keyboard keys/shortcuts in the focused app, such as [\"tab\", \"tab\", \"enter\"]. Runs up to 50 entries with a bounded pause between them, reducing repeated tool round trips for keyboard navigation.")
    public String keySequence(
            @ToolParam(description = "Ordered key/combo list; each entry accepts the computer_key syntax.")
            List<String> keys,
            @ToolParam(required = false, description = "Pause between keys in milliseconds (default 80, range 0-2000).")
            Integer intervalMs) {
        return run("key_sequence", envelope -> {
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("keys must contain at least one entry");
            }
            if (keys.size() > MAX_KEY_SEQUENCE) {
                throw new IllegalArgumentException("keys exceeds maximum of " + MAX_KEY_SEQUENCE);
            }
            int interval = Math.clamp(intervalMs == null ? 80 : intervalMs, 0, MAX_KEY_INTERVAL_MS);
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("keys[" + i + "] must not be blank");
                }
                driver.pressKeys(key);
                if (interval > 0 && i + 1 < keys.size()) Thread.sleep(interval);
            }
            envelope.put("count", keys.size());
            envelope.put("intervalMs", interval);
            envelope.put("summary", "pressed " + keys.size() + " key sequence entr"
                    + (keys.size() == 1 ? "y" : "ies"));
        });
    }

    @Tool(name = "computer_wait",
          description = "Pause 0.1-60 s so real apps can react (launching, opening menus, loading) before the "
                  + "next screenshot or action.")
    public String waitSeconds(
            @ToolParam(required = false, description = "Seconds to wait (default 1, max 60).") Double seconds) {
        return run("wait", envelope -> {
            double requested = seconds == null ? 1.0 : seconds;
            long millis = Math.round(Math.clamp(requested, 0.1, MAX_WAIT_SECONDS) * 1000);
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(Math.min(100, deadline - System.currentTimeMillis()));
            }
            envelope.put("waitedMs", millis);
            envelope.put("summary", "waited " + millis + " ms");
        });
    }

    // ── envelope helpers ─────────────────────────────────────────────────────

    private interface Op {
        void apply(Map<String, Object> envelope) throws Exception;
    }

    private String run(String action, Op op) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", false);
        envelope.put("action", action);
        try {
            if (!driver.available()) {
                envelope.put("summary", "computer use unavailable: " + driver.unavailableReason());
                return toJson(envelope);
            }
            op.apply(envelope);
            envelope.put("success", true);
            envelope.remove("_displays");
            return toJson(envelope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            envelope.put("summary", action + " cancelled");
            return toJson(envelope);
        } catch (Exception e) {
            envelope.put("summary", action + " failed: " + safeMsg(e));
            envelope.remove("_displays");
            return toJson(envelope);
        }
    }

    /** Shared display listing; also stashes the raw list for region math (removed before serialize). */
    private void displaysEnvelope(Map<String, Object> envelope) {
        List<ComputerDriver.DisplayInfo> displays = driver.displays();
        envelope.put("_displays", displays);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ComputerDriver.DisplayInfo display : displays) {
            items.add(new LinkedHashMap<>(Map.of(
                    "index", display.index(),
                    "primary", display.primary(),
                    "x", display.x(), "y", display.y(),
                    "width", display.width(), "height", display.height(),
                    "scale", display.scale())));
        }
        envelope.put("displays", items);
        if (!envelope.containsKey("summary")) {
            envelope.put("summary", items.size() + " display(s)");
        }
    }

    private static ComputerDriver.DisplayInfo resolveDisplay(
            List<ComputerDriver.DisplayInfo> displays, Integer displayIndex) {
        if (displays.isEmpty()) throw new IllegalStateException("no attached displays");
        if (displayIndex == null) {
            return displays.stream().filter(ComputerDriver.DisplayInfo::primary).findFirst()
                    .orElse(displays.get(0));
        }
        return displays.stream().filter(d -> d.index() == displayIndex).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown display " + displayIndex + "; call computer_displays for indexes"));
    }

    /** Best-effort file mirror; the inline image still works when the write fails. */
    private static String saveScreenshot(byte[] png) {
        try {
            Path dir = RuntimePaths.computerScreenshotsDirectory(RuntimePaths.root());
            Files.createDirectories(dir);
            Path file = dir.resolve("shot-" + System.currentTimeMillis() + ".png");
            Files.write(file, png);
            return file.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeMsg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"summary\":\"failed to serialize computer tool result\"}";
        }
    }
}
