package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.runtime.RuntimePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;

import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComputerToolTest {

    /** Minimal valid 1×1 transparent PNG. */
    private static final byte[] TINY_PNG =
            Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                    + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** Scriptable driver: records every input call, returns canned capture/display data. */
    private static final class FakeDriver implements ComputerDriver {
        boolean available = true;
        String unavailableReason;
        List<DisplayInfo> displays = List.of(
                new DisplayInfo(0, true, 0, 0, 1440, 900, 2.0),
                new DisplayInfo(1, false, 1440, 0, 1920, 1080, 1.0));
        Rectangle captured;
        Integer movedX, movedY, clickX, clickY, dragFromX, dragFromY, dragToX, dragToY;
        String clickButton;
        boolean clickDouble;
        String scrollDirection;
        int scrollAmount;
        String typedText, pressedKey;
        final List<String> pressedKeys = new ArrayList<>();

        @Override public boolean available() { return available; }
        @Override public String unavailableReason() { return unavailableReason; }
        @Override public List<DisplayInfo> displays() { return displays; }
        @Override public Capture capture(Rectangle logicalRect) {
            captured = new Rectangle(logicalRect);
            return new Capture(TINY_PNG, logicalRect.width * 2, logicalRect.height * 2,
                    logicalRect, 2.0);
        }
        @Override public CursorPosition mousePosition() { return new CursorPosition(11, 22); }
        @Override public void mouseMove(int x, int y) { movedX = x; movedY = y; }
        @Override public void mouseClick(Integer x, Integer y, String button, boolean doubleClick) {
            clickX = x; clickY = y; clickButton = button; clickDouble = doubleClick;
        }
        @Override public void mouseDrag(int fromX, int fromY, int toX, int toY) {
            dragFromX = fromX; dragFromY = fromY; dragToX = toX; dragToY = toY;
        }
        @Override public void scroll(String direction, int amount, Integer x, Integer y) {
            scrollDirection = direction; scrollAmount = amount;
        }
        @Override public void typeText(String text) { typedText = text; }
        @Override public void pressKeys(String combo) { pressedKey = combo; pressedKeys.add(combo); }
    }

    /** Records launch/activate without touching a real OS. */
    private static final class FakeApps extends ComputerApps {
        List<String> running = List.of("FengYu", "Safari");
        final List<String> launched = new ArrayList<>();
        final List<String> activated = new ArrayList<>();

        FakeApps() { super("Mac OS X", (command, timeout) -> ""); }

        @Override List<String> list() { return running; }
        @Override void launch(String app) { launched.add(app); }
        @Override void activate(String app) { activated.add(app); }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> parse(String json) throws Exception {
        return JSON.readValue(json, Map.class);
    }

    @Test
    void classifiesReadOnlyAndExternalEffects() {
        ComputerTool tool = new ComputerTool(new FakeDriver(), new FakeApps());
        assertEquals(ToolEffect.READ, tool.effectFor("computer_screenshot"));
        assertEquals(ToolEffect.READ, tool.effectFor("computer_displays"));
        assertEquals(ToolEffect.READ, tool.effectFor("computer_apps"));
        assertEquals(ToolEffect.READ, tool.effectFor("computer_cursor_position"));
        assertEquals(ToolEffect.READ, tool.effectFor("computer_wait"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("computer_click"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("computer_type"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("computer_key"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("computer_key_sequence"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("computer_mouse_move"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("computer_drag"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("computer_scroll"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("computer_app_launch"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("computer_app_activate"));
        assertEquals(ToolEffect.EXTERNAL, tool.effectFor("future_computer_tool"));
    }

    @Test
    void springAiDiscoversKeySequenceWithArraySchema() throws Exception {
        var callback = java.util.Arrays.stream(
                        ToolCallbacks.from(new ComputerTool(new FakeDriver(), new FakeApps())))
                .filter(item -> item.getToolDefinition().name().equals("computer_key_sequence"))
                .findFirst()
                .orElseThrow();
        String schema = callback.getToolDefinition().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parse(schema).get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> keys = (Map<String, Object>) properties.get("keys");
        assertEquals("array", keys.get("type"));
    }

    @Test
    void unavailableDriverReturnsDegradedEnvelopeForEveryAction() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.available = false;
        driver.unavailableReason = "headless environment (no attached display)";
        ComputerTool tool = new ComputerTool(driver, new FakeApps());

        Map<String, Object> availability = tool.availability();
        assertEquals(false, availability.get("available"));
        assertEquals(driver.unavailableReason, availability.get("reason"));

        for (String result : List.of(tool.click(10, 10, "left"), tool.type("hi"),
                tool.screenshot(null, null, null, null, null))) {
            Map<String, Object> envelope = parse(result);
            assertEquals(Boolean.FALSE, envelope.get("success"));
            assertTrue(((String) envelope.get("summary")).contains("computer use unavailable"),
                    String.valueOf(envelope));
        }
    }

    @Test
    void screenshotCapturesPrimaryDisplayAndReportsScale(@TempDir Path runtimeDir) throws Exception {
        System.setProperty(RuntimePaths.ROOT_PROPERTY, runtimeDir.toString());
        try {
            FakeDriver driver = new FakeDriver();
            ComputerTool tool = new ComputerTool(driver, new FakeApps());

            Map<String, Object> envelope = parse(tool.screenshot(null, null, null, null, null));

            assertEquals(Boolean.TRUE, envelope.get("success"));
            assertEquals("screenshot", envelope.get("action"));
            assertEquals(0, envelope.get("display"));
            @SuppressWarnings("unchecked")
            Map<String, Object> bounds = (Map<String, Object>) envelope.get("bounds");
            assertEquals(0, bounds.get("x"));
            assertEquals(1440, bounds.get("width"));
            assertEquals(2880, envelope.get("imageWidth"));
            assertEquals(2.0, envelope.get("scale"));
            assertEquals("image/png", envelope.get("mimeType"));
            assertEquals(TINY_PNG.length, ((Number) envelope.get("imageBytes")).intValue());
            assertFalse(((String) envelope.get("imageBase64")).isBlank());
            assertNotNull(envelope.get("savedPath"), "file mirror should be written");
            assertEquals(new Rectangle(0, 0, 1440, 900), driver.captured);
        } finally {
            System.clearProperty(RuntimePaths.ROOT_PROPERTY);
        }
    }

    @Test
    void screenshotHonorsDisplayIndexAndRegionIntersection() throws Exception {
        FakeDriver driver = new FakeDriver();
        ComputerTool tool = new ComputerTool(driver, new FakeApps());

        Map<String, Object> envelope =
                parse(tool.screenshot(1, 2400, 100, 500, 2000));
        assertEquals(Boolean.TRUE, envelope.get("success"));
        assertEquals(1, envelope.get("display"));
        // Region intersects display 1 (x 1440..3360): 2400..2900 stays; height clips to 1080-100.
        assertEquals(new Rectangle(2400, 100, 500, 980), driver.captured);
    }

    @Test
    void screenshotRejectsUnknownDisplayAndEmptyRegion() throws Exception {
        ComputerTool tool = new ComputerTool(new FakeDriver(), new FakeApps());

        Map<String, Object> unknown = parse(tool.screenshot(7, null, null, null, null));
        assertEquals(Boolean.FALSE, unknown.get("success"));
        assertTrue(((String) unknown.get("summary")).contains("computer_displays"));

        Map<String, Object> outside = parse(tool.screenshot(0, 5000, 5000, 100, 100));
        assertEquals(Boolean.FALSE, outside.get("success"));
        assertTrue(((String) outside.get("summary")).contains("outside display"));
    }

    @Test
    void displaysListsAllScreensWithPrimaryFlag() throws Exception {
        ComputerTool tool = new ComputerTool(new FakeDriver(), new FakeApps());
        Map<String, Object> envelope = parse(tool.displays());
        assertEquals(Boolean.TRUE, envelope.get("success"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> displays = (List<Map<String, Object>>) envelope.get("displays");
        assertEquals(2, displays.size());
        assertEquals(true, displays.get(0).get("primary"));
        assertEquals(false, displays.get(1).get("primary"));
        assertEquals(1920, displays.get(1).get("width"));
    }

    @Test
    void inputActionsForwardParamsToDriver() throws Exception {
        FakeDriver driver = new FakeDriver();
        FakeApps apps = new FakeApps();
        ComputerTool tool = new ComputerTool(driver, apps);

        assertEquals(Boolean.TRUE, parse(tool.click(100, 200, "right")).get("success"));
        assertEquals(100, driver.clickX);
        assertEquals(200, driver.clickY);
        assertEquals("right", driver.clickButton);
        assertFalse(driver.clickDouble);

        assertEquals(Boolean.TRUE, parse(tool.doubleClick(null, null)).get("success"));
        assertTrue(driver.clickDouble);

        assertEquals(Boolean.TRUE, parse(tool.mouseMove(5, 6)).get("success"));
        assertEquals(5, driver.movedX);
        assertEquals(6, driver.movedY);

        assertEquals(Boolean.TRUE, parse(tool.drag(1, 2, 3, 4)).get("success"));
        assertEquals(1, driver.dragFromX);
        assertEquals(4, driver.dragToY);

        assertEquals(Boolean.TRUE, parse(tool.scroll("up", 5, 9, 9)).get("success"));
        assertEquals("up", driver.scrollDirection);
        assertEquals(5, driver.scrollAmount);

        assertEquals(Boolean.TRUE, parse(tool.type("你好 world")).get("success"));
        assertEquals("你好 world", driver.typedText);

        assertEquals(Boolean.TRUE, parse(tool.key("cmd+s")).get("success"));
        assertEquals("cmd+s", driver.pressedKey);

        assertEquals(Boolean.TRUE, parse(tool.cursorPosition()).get("success"));
        Map<String, Object> cursor = parse(tool.cursorPosition());
        assertEquals(11, cursor.get("x"));

        assertEquals(Boolean.TRUE, parse(tool.appLaunch("Safari")).get("success"));
        assertEquals(List.of("Safari"), apps.launched);
        assertEquals(Boolean.TRUE, parse(tool.appActivate("FengYu")).get("success"));
        assertEquals(List.of("FengYu"), apps.activated);

        Map<String, Object> appList = parse(tool.apps());
        assertEquals(Boolean.TRUE, appList.get("success"));
        assertEquals(2, appList.get("count"));
    }

    @Test
    void appFailuresBecomeEnvelopesNotExceptions() throws Exception {
        ComputerApps failing = new ComputerApps("Mac OS X", (command, timeout) -> {
            throw new IllegalStateException("app not found");
        });
        ComputerTool tool = new ComputerTool(new FakeDriver(), failing);
        Map<String, Object> envelope = parse(tool.appLaunch("NoSuchApp"));
        assertEquals(Boolean.FALSE, envelope.get("success"));
        assertTrue(((String) envelope.get("summary")).contains("app_launch failed"));
    }

    @Test
    void waitClampsToConfiguredRange() throws Exception {
        ComputerTool tool = new ComputerTool(new FakeDriver(), new FakeApps());
        Map<String, Object> envelope = parse(tool.waitSeconds(0.05));
        assertEquals(Boolean.TRUE, envelope.get("success"));
        assertEquals(100, ((Number) envelope.get("waitedMs")).intValue());
    }

    @Test
    void keySequenceRunsInOrderAndRejectsInvalidInput() throws Exception {
        FakeDriver driver = new FakeDriver();
        ComputerTool tool = new ComputerTool(driver, new FakeApps());

        Map<String, Object> result = parse(tool.keySequence(List.of("tab", "shift+tab", "enter"), 0));
        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals(3, result.get("count"));
        assertEquals(0, result.get("intervalMs"));
        assertEquals(List.of("tab", "shift+tab", "enter"), driver.pressedKeys);

        Map<String, Object> empty = parse(tool.keySequence(List.of(), 10));
        assertEquals(Boolean.FALSE, empty.get("success"));
        assertTrue(((String) empty.get("summary")).contains("at least one"));
    }

    @Test
    void defaultConstructorProducesWellFormedEnvelopesInAnyEnvironment() throws Exception {
        // On CI this is a headless JVM (degraded); on a desktop it succeeds — both must be JSON.
        ComputerTool tool = new ComputerTool();
        Map<String, Object> envelope = parse(tool.displays());
        assertEquals("displays", envelope.get("action"));
        assertNotNull(envelope.get("success"));
        assertNotNull(envelope.get("summary"));
    }
}
