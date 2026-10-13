package fan.summer.fengyu.ai.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MIME gate between sandbox pages and MCP resource bytes. */
class McpUiReadResourceMimeTest {

    @Test
    void allowsCommonFamiliesAndDocuments() {
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("text/plain"));
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("text/markdown; charset=utf-8"));
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("application/json"));
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("application/pdf"));
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("image/svg+xml"));
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("image/PNG")); // case-insensitive
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("audio/mpeg"));
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("video/mp4"));
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("font/woff2"));
        assertTrue(McpRuntimeManager.isUiReadMimeAllowed("application/vnd.openxmlformats-"
                + "officedocument.wordprocessingml.document"));
    }

    @Test
    void rejectsBlankUnknownAndExecutable() {
        assertFalse(McpRuntimeManager.isUiReadMimeAllowed(null));
        assertFalse(McpRuntimeManager.isUiReadMimeAllowed(""));
        assertFalse(McpRuntimeManager.isUiReadMimeAllowed("   "));
        assertFalse(McpRuntimeManager.isUiReadMimeAllowed("application/x-unknown-vendor-format"));
        assertFalse(McpRuntimeManager.isUiReadMimeAllowed("application/x-msdownload"));
        assertFalse(McpRuntimeManager.isUiReadMimeAllowed("application/x-sh"));
    }
}
