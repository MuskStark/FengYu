package fan.summer.fengyu.ai.mcp.apps;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sandbox registration, capability ids, eviction, and explicit disposal. */
class McpSandboxRegistryTest {

    @TempDir
    Path tempDir;

    private McpSandboxRegistry newRegistry() {
        return new McpSandboxRegistry(new McpRuntimeManager(tempDir));
    }

    private McpSandboxRegistry.SandboxEntry register(McpSandboxRegistry registry, String uri) {
        return registry.register("srv-1", "Server", uri, "tool:call-1", "<html>" + uri + "</html>",
                new McpToolUiMeta.McpUiCsp(null, null, null, null), null);
    }

    @Test
    void registerGetDisposeRoundTrip() {
        McpSandboxRegistry registry = newRegistry();
        McpSandboxRegistry.SandboxEntry entry = register(registry, "ui://w/a.html");
        assertNotNull(entry.sandboxId());
        assertTrue(entry.sandboxId().length() >= 20); // 128-bit capability, url-safe
        assertFalse(entry.sandboxId().contains("="));
        assertSameEntry(entry, registry.get(entry.sandboxId()));
        assertEquals(1, registry.size());
        assertTrue(registry.dispose(entry.sandboxId()));
        assertNull(registry.get(entry.sandboxId()));
        assertFalse(registry.dispose(entry.sandboxId()));
    }

    @Test
    void idsAreUniquePerRegistration() {
        McpSandboxRegistry registry = newRegistry();
        McpSandboxRegistry.SandboxEntry first = register(registry, "ui://w/a.html");
        McpSandboxRegistry.SandboxEntry second = register(registry, "ui://w/b.html");
        assertNotEquals(first.sandboxId(), second.sandboxId());
    }

    @Test
    void capEvictsOldest() {
        McpSandboxRegistry registry = newRegistry();
        String firstId = null;
        for (int i = 0; i < McpSandboxRegistry.MAX_SANDBOXES + 4; i++) {
            McpSandboxRegistry.SandboxEntry entry = register(registry, "ui://w/" + i + ".html");
            if (firstId == null) firstId = entry.sandboxId();
        }
        assertEquals(McpSandboxRegistry.MAX_SANDBOXES, registry.size());
        assertNull(registry.get(firstId), "oldest entry must have been evicted");
    }

    @Test
    void disposeServerRemovesOnlyThatServer() {
        McpSandboxRegistry registry = newRegistry();
        McpSandboxRegistry.SandboxEntry keep = registry.register("srv-2", "Other", "ui://x/b.html",
                "", "<html></html>", null, null);
        McpSandboxRegistry.SandboxEntry drop = register(registry, "ui://x/a.html");
        registry.disposeServer("srv-1");
        assertNull(registry.get(drop.sandboxId()));
        assertEquals(keep.sandboxId(), registry.get(keep.sandboxId()).sandboxId());
    }

    private static void assertSameEntry(McpSandboxRegistry.SandboxEntry expected,
                                        McpSandboxRegistry.SandboxEntry actual) {
        assertEquals(expected.sandboxId(), actual.sandboxId());
        assertEquals(expected.html(), actual.html());
    }
}
