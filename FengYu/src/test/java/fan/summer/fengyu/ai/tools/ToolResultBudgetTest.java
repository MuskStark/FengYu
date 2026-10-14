package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import fan.summer.fengyu.runtime.RuntimePaths;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layered result budgets: bounded text, persisted overflow, resource-link passthrough.
 * Artifacts land under the real runtime root's tool-results dir (gitignored); the assertions
 * read them back through the marker's path.
 */
class ToolResultBudgetTest {

    @org.junit.jupiter.api.BeforeAll
    static void isolateRuntimeRoot(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        // Artifacts must never land in the shared real runtime root: a concurrently-running
        // JVM's first-write sweep would delete them mid-assertion, and the repo dir would
        // collect leftovers. RuntimePaths reads the property on every write.
        realRoot = System.getProperty(RuntimePaths.ROOT_PROPERTY);
        System.setProperty(RuntimePaths.ROOT_PROPERTY, root.toString());
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreRuntimeRoot() {
        if (realRoot == null) System.clearProperty(RuntimePaths.ROOT_PROPERTY);
        else System.setProperty(RuntimePaths.ROOT_PROPERTY, realRoot);
    }

    private static String realRoot;

    @Test
    void anInBudgetResultPassesThroughUntouched() {
        String output = "x".repeat(1024);
        assertEquals(output, ToolResultBudget.budget("some_tool", output, true));
        assertEquals(output, ToolResultBudget.budget("some_tool", output, false));
        assertEquals(null, ToolResultBudget.budget("some_tool", null, true));
    }

    @Test
    void anOverBudgetResultIsTruncatedWithAMarkerAndPersisted() throws Exception {
        String output = "y".repeat(200_000); // past the 64 KiB MCP budget, under 256 KiB plugin one
        String budgeted = ToolResultBudget.budget("report_tool", output, true);

        int headBytes = budgeted.substring(0, budgeted.indexOf("\n\n[FengYu]"))
                .getBytes(StandardCharsets.UTF_8).length;
        assertTrue(headBytes > ToolResultBudget.MCP_BUDGET_BYTES * 9 / 10,
                "the head keeps (nearly) the whole budget: " + headBytes);
        assertTrue(headBytes <= ToolResultBudget.MCP_BUDGET_BYTES, "never over the budget");
        assertTrue(budgeted.contains("[FengYu] Tool output truncated"), "the marker is explicit");
        assertTrue(budgeted.contains("kept until the app restarts"), "the retention contract is stated");

        // The artifact carries the COMPLETE output, not the truncation.
        int pathStart = budgeted.indexOf("saved to: ") + "saved to: ".length();
        Path artifact = Path.of(budgeted.substring(pathStart, budgeted.indexOf(" (kept until")));
        assertTrue(Files.exists(artifact), "the overflow artifact exists");
        assertEquals(output, Files.readString(artifact));

        // The plugin budget (larger) keeps the same string untouched.
        assertEquals(output, ToolResultBudget.budget("report_tool", output, false));
    }

    @Test
    void aHugeResultStillKeepsAFullHead() {
        // The old step-decrement loop collapsed to an empty head past ~6.4 MB; the byte cut
        // with its continuation-byte walk-back must keep the budget's worth at any size.
        String huge = "z".repeat(7 * 1024 * 1024);
        String budgeted = ToolResultBudget.budget("huge_tool", huge, true);
        int headBytes = budgeted.substring(0, budgeted.indexOf("\n\n[FengYu]"))
                .getBytes(StandardCharsets.UTF_8).length;
        assertTrue(headBytes > ToolResultBudget.MCP_BUDGET_BYTES * 9 / 10,
                "a 7 MB result still shows its budget's worth: " + headBytes);
        assertTrue(budgeted.startsWith("zzzz"), "the head is the prefix of the real output");
    }

    @Test
    void mcpResourceLinkPayloadsPassThroughVerbatim() {
        String resourceLinks = "{\"content\":[{\"type\":\"text\",\"text\":\""
                + "z".repeat(300_000) + "\"}],\"resourceUri\":\"file:///data.csv\"}";
        assertEquals(resourceLinks, ToolResultBudget.budget("mcp_tool", resourceLinks, true),
                "the protocol's own large-content channel is never truncated");
        String embeddedResource = "{\"content\":[{\"type\":\"resource\",\"resource\":{"
                + "\"uri\":\"file:///big\",\"text\":\"" + "z".repeat(300_000) + "\"}}]}";
        assertEquals(embeddedResource, ToolResultBudget.budget("mcp_tool", embeddedResource, true));
    }

    @Test
    void ordinaryJsonWithCommonKeysIsStillBudgeted() {
        // "contents"/"resource" as bare keys are everyday JSON, not the MCP large-content
        // channel — passing them through would reopen the unbounded-output hole.
        String fileListing = "{\"contents\":[{\"name\":\"f" + "i".repeat(300_000) + "\"}]}";
        String budgeted = ToolResultBudget.budget("list_tool", fileListing, true);
        assertTrue(budgeted.length() < fileListing.length(), "ordinary oversized JSON is cut");
        assertTrue(budgeted.contains("Tool output truncated"));
        String k8s = "{\"resource\":{\"spec\":\"" + "s".repeat(300_000) + "\"}}";
        assertTrue(ToolResultBudget.budget("k8s_tool", k8s, true).contains("Tool output truncated"));
    }

    @Test
    void aResourceShapedPayloadPastThePassThroughCeilingIsStillBudgeted() {
        // The pass-through exists for the protocol's own large-content channel, but a
        // hostile server can LABEL anything a resource — past the absolute ceiling the
        // budget applies again, marker and all.
        String oversized = "{\"resourceUri\":\"file:///big.csv\",\"text\":\""
                + "z".repeat(33 * 1024 * 1024) + "\"}";
        String budgeted = ToolResultBudget.budget("big_resource_tool", oversized, true);
        assertTrue(budgeted.length() < oversized.length(), "past the ceiling the budget applies");
        assertTrue(budgeted.contains("Tool output truncated"));
        assertTrue(budgeted.contains("MiB"), "the marker reports the real scale");
    }

    @Test
    void resourceLinkElementsAndArrayRootsPassThrough() {
        String linkElement = "{\"content\":[{\"type\":\"resource_link\","
                + "\"resourceUri\":\"file:///a\"}],\"extra\":\"" + "z".repeat(300_000) + "\"}";
        assertEquals(linkElement, ToolResultBudget.budget("link_tool", linkElement, true),
                "a content[] element typed resource_link is the official shape");
        String arrayRoot = "[{\"type\":\"resource\",\"resource\":{"
                + "\"uri\":\"file:///b\",\"text\":\"" + "z".repeat(300_000) + "\"}}]";
        assertEquals(arrayRoot, ToolResultBudget.budget("array_tool", arrayRoot, true),
                "a bare content-item array root is the official shape too");
    }

    @Test
    void markerTextNeverStartsMidSurrogatePair() {
        // Odd-length mixed content: the cut must land on a code point boundary even when the
        // raw byte-limit index would split a pair.
        String mixed = ("a🛠").repeat(40_000) + "b"; // 3 chars per group, odd total length
        String budgeted = ToolResultBudget.budget("mixed_tool", mixed, true);
        int headEnd = budgeted.indexOf("\n\n[FengYu]");
        assertTrue(headEnd > 0, "the marker is present");
        assertFalse(Character.isHighSurrogate(budgeted.charAt(headEnd - 1)),
                "the head must end on a whole character");
    }
}
