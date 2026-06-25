package fan.summer.api;

import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwissKitJPluginAiToolsDefaultTest {

    /** A plugin that overrides nothing — should still work and return empty aiTools. */
    private static final SwissKitJPlugin MINIMAL = new SwissKitJPlugin() {
        public String getId() { return "test.minimal"; }
        public String getName() { return "Minimal"; }
        public String getDescription() { return ""; }
        public ToolCategory getCategory() { return ToolCategory.OTHER; }
        public String getVersion() { return "0.0.1"; }
        public String getMdiIcon() { return "circle"; }
        public Node createView() { return null; }
    };

    @Test
    void defaultAiToolsIsEmpty() {
        assertNotNull(MINIMAL.aiTools());
        assertTrue(MINIMAL.aiTools().isEmpty());
    }

    @Test
    void defaultAiToolsIsImmutable() {
        // Default returns List.of() which is immutable
        List<?> tools = MINIMAL.aiTools();
        assertThrows(UnsupportedOperationException.class, () -> addOne(tools));
    }

    /** Helper that asserts unchecked-add still throws on the immutable default list. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addOne(List<?> tools) {
        ((List) tools).add(new Object());
    }
}
