package fan.summer.fengyu.ai.mcp.apps;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Capture-key determinism, FIFO drain, and bounded growth for the UI result store. */
class McpUiCaptureStoreTest {

    @Test
    void keyIsDeterministicInWireNameAndArgs() {
        assertEquals(McpUiCaptureStore.key("srv__tool", "{\"a\":1}"),
                McpUiCaptureStore.key("srv__tool", "{\"a\":1}"));
        assertTrue(!McpUiCaptureStore.key("srv__tool", "{\"a\":1}")
                .equals(McpUiCaptureStore.key("srv__tool", "{\"a\":2}")));
        assertTrue(!McpUiCaptureStore.key("srv__tool", "{}")
                .equals(McpUiCaptureStore.key("srv__other", "{}")));
    }

    @Test
    void fulfillThenTakeDrainsFifo() {
        McpUiCaptureStore store = new McpUiCaptureStore();
        store.fulfill("srv__tool", "{}", new McpToolUiMeta.CapturedResult(false, "first", null, null));
        store.fulfill("srv__tool", "{}", new McpToolUiMeta.CapturedResult(false, "second", null, null));
        assertEquals("first", store.take("srv__tool", "{}").structuredContent());
        assertEquals("second", store.take("srv__tool", "{}").structuredContent());
        assertNull(store.take("srv__tool", "{}"));
    }

    @Test
    void chatFileInjectionDoesNotBreakTheCaptureKeyContract() {
        // The chat-file hand-off rewrites a tool's DISPATCHED arguments; the capture key must
        // stay the MODEL's raw input (the SSE event side hashes tc.arguments()). A fulfill
        // keyed by the original input must be findable by the original input — and invisible
        // to an injected-shaped query — pinning the UiCapturingToolCallback layering.
        McpUiCaptureStore store = new McpUiCaptureStore();
        String modelArgs = "{\"filePath\":\"  \"}";
        String injectedArgs = "{\"filePath\":\"/chat/data.csv\"}";
        store.fulfill("srv__tool", modelArgs, new McpToolUiMeta.CapturedResult(
                false, java.util.Map.of("k", "v"), null, java.util.List.of()));
        assertNotNull(store.take("srv__tool", modelArgs),
                "the event side finds the capture by the model's raw arguments");
        store.fulfill("srv__tool", modelArgs, new McpToolUiMeta.CapturedResult(
                false, java.util.Map.of("k", "v2"), null, java.util.List.of()));
        assertNull(store.take("srv__tool", injectedArgs),
                "an injected-shaped query is a different key, never a hit");
    }

    @Test
    void unknownKeyTakesNull() {
        McpUiCaptureStore store = new McpUiCaptureStore();
        assertNull(store.take("missing", "{}"));
        store.fulfill("srv__tool", "{}", new McpToolUiMeta.CapturedResult(false, null, Map.of(), null));
        assertNotNull(store.take("srv__tool", "{}"));
    }

    @Test
    void perKeyQueueIsCapped() {
        McpUiCaptureStore store = new McpUiCaptureStore();
        for (int i = 0; i < 10; i++) {
            store.fulfill("srv__tool", "{}", new McpToolUiMeta.CapturedResult(false, i, null, null));
        }
        assertEquals(6, store.take("srv__tool", "{}").structuredContent()); // oldest four evicted
    }

    @Test
    void globalMapIsBoundedByDistinctKeys() {
        McpUiCaptureStore store = new McpUiCaptureStore();
        for (int i = 0; i < 200; i++) {
            store.fulfill("srv__tool", "{\"i\":" + i + "}",
                    new McpToolUiMeta.CapturedResult(false, i, null, null));
        }
        assertTrue(store.snapshot().size() <= 64, "map must stay bounded");
    }

    @Test
    void clearRemovesEverything() {
        McpUiCaptureStore store = new McpUiCaptureStore();
        store.fulfill("srv__tool", "{}", new McpToolUiMeta.CapturedResult(false, "x", null, null));
        store.clear();
        assertNull(store.take("srv__tool", "{}"));
    }
}
