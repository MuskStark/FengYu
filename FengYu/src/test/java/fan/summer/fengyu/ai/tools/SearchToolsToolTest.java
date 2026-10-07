package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchToolsToolTest {

    private final SearchToolsTool loader = new SearchToolsTool();

    @AfterEach
    void cleanup() {
        ToolActivationContext.clear();
    }

    private static ToolCallback tool(String name, String description) {
        return ToolLoadingPolicyTest.tool(name, description, "{\"type\":\"object\",\"properties\":{"
                + "\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}");
    }

    @Test
    void searchActivatesNameAndDescriptionMatchesAndEmitsMarker() {
        ToolActivationState state = new ToolActivationState(
                Set.of("chrome__navigate", "chrome__screenshot", "excel_read_table"));
        List<ToolCallback> deferred = List.of(
                tool("chrome__navigate", "navigate the active tab"),
                tool("chrome__screenshot", "capture the tab"),
                tool("excel_read_table", "read a table region"));
        ToolActivationContext.set(state, deferred);

        String result = loader.search("chrome");

        assertTrue(state.isActive("chrome__navigate"), result);
        assertTrue(state.isActive("chrome__screenshot"), result);
        assertFalse(state.isActive("excel_read_table"), result);
        assertTrue(result.contains("chrome__navigate: navigate the active tab"), result);
        assertTrue(result.contains("input schema:"), result);
        assertTrue(result.contains(ToolActivationState.markerFor(
                List.of("chrome__navigate", "chrome__screenshot"))), result);
        assertTrue(result.contains("callable from your next message"), result);
        // Re-searching reports already-active instead of duplicating.
        assertTrue(loader.search("navigate").contains("already active"));
        assertEquals(2, state.activatedCount());
    }

    @Test
    void descriptionOnlyMatchesActivateToo() {
        ToolActivationState state = new ToolActivationState(Set.of("spreadsheet_parse"));
        ToolActivationContext.set(state, List.of(tool("spreadsheet_parse", "parses invoice tables")));

        String result = loader.search("invoice");

        assertTrue(state.isActive("spreadsheet_parse"), result);
    }

    @Test
    void noMatchReturnsActionableGuidanceWithoutActivating() {
        ToolActivationState state = new ToolActivationState(Set.of("chrome__navigate"));
        ToolActivationContext.set(state, List.of(tool("chrome__navigate", "navigate")));

        String result = loader.search("quantum");

        assertTrue(result.contains("No inactive tool matched 'quantum'"), result);
        assertTrue(result.contains("do not invent tool names"), result);
        assertEquals(0, state.activatedCount());
    }

    @Test
    void activationCapAnswersWithAHonestLimitMessage() {
        Set<String> eligible = IntStream.rangeClosed(1, 50).mapToObj(i -> "tool_" + i)
                .collect(java.util.stream.Collectors.toSet());
        eligible = new java.util.HashSet<>(eligible);
        eligible.add("unrelated_name");
        ToolActivationState state = new ToolActivationState(eligible);
        for (int i = 1; i <= ToolActivationState.MAX_ACTIVATED; i++) state.activate("tool_" + i);
        ToolActivationContext.set(state, List.of(tool("unrelated_name", "unrelated description")));

        String result = loader.search("unrelated");

        assertTrue(result.contains("activation limit"), result);
        assertFalse(state.isActive("unrelated_name"));
    }

    @Test
    void outsideDynamicLoadingModeAnswersHonestly() {
        ToolActivationContext.clear();
        String result = loader.search("anything");
        assertTrue(result.contains("Tool loading is not active"), result);
    }

    /**
     * Concurrency pin (READ batches run search_tools calls side by side): two searches
     * of the SAME conversation must never clear each other's index. The old shared
     * singleton index interleaved as A-clear → A-index → B-clear → A-search — A then
     * answered "no inactive tool matched" for keywords that plainly match; the per-call
     * index cannot see another call's clear at all.
     */
    @Test
    void concurrentSearchesOfTheSameConversationNeverClearEachOther() throws Exception {
        ConversationContext.set(99L);   // same conversation → the same index session id
        try {
            List<ToolCallback> catalog = List.of(
                    tool("chrome__navigate", "navigate the active tab"),
                    tool("chrome__screenshot", "capture the tab"),
                    tool("excel_read_table", "read a table region"));
            int rounds = 30;
            for (int i = 0; i < rounds; i++) {
                java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(2);
                java.util.List<String> chrome = new java.util.concurrent.CopyOnWriteArrayList<>();
                java.util.List<String> excel = new java.util.concurrent.CopyOnWriteArrayList<>();
                Thread chromeThread = Thread.ofVirtual().start(() -> {
                    ToolActivationContext.set(
                            new ToolActivationState(java.util.Set.of("chrome__navigate")), catalog);
                    await(gate);
                    chrome.add(loader.search("chrome"));
                });
                Thread excelThread = Thread.ofVirtual().start(() -> {
                    ToolActivationContext.set(
                            new ToolActivationState(java.util.Set.of("excel_read_table")), catalog);
                    await(gate);
                    excel.add(loader.search("excel"));
                });
                chromeThread.join(10_000);
                excelThread.join(10_000);
                assertEquals(1, chrome.size(), "round " + i);
                assertEquals(1, excel.size(), "round " + i);
                assertTrue(chrome.get(0).contains("chrome__navigate"),
                        "round " + i + " lost the chrome match: " + chrome.get(0));
                assertTrue(excel.get(0).contains("excel_read_table"),
                        "round " + i + " lost the excel match: " + excel.get(0));
            }
        } finally {
            ConversationContext.clear();
        }
    }

    private static void await(java.util.concurrent.CyclicBarrier gate) {
        try {
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
