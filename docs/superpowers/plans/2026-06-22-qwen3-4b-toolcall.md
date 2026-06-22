# Qwen3-4B Local Tool-Calling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the FunctionGemma-based local tool-calling backend with Qwen3-4B (Hermes tool-call format + displayed thinking), end-to-end verified by a manual smoke test with a real GGUF.

**Architecture:** Qwen3 runs through the existing self-contained GGUF inference engine (`LocalChatBackend` native path). A new `ThinkingStreamSegmenter` splits the streaming token stream into THINK / CONTENT / suppressed(tool-call) regions, routing THINK to a new `AiStreamCallback.onThinking` channel and tool-call extraction to the shared `ToolCallParser` (extended with a Hermes regex). A thin `Qwen3Adapter` owns the thinking-mode toggle and the Hermes system-prompt preamble. `AiChatPlugin` renders each completed thinking block as a collapsed `<details>` card above the response bubble. FunctionGemma support is removed entirely.

**Tech Stack:** Java 21 (virtual threads), JavaFX + WebView, JUnit 5, CommonMark, llama.cpp JNI (native backend), pure-Java transformer (fallback). Build via IntelliJ IDEA Maven tool window only — **no system `mvn`** (see CLAUDE.md).

**Spec:** `docs/superpowers/specs/2026-06-22-qwen3-4b-toolcall-design.md`

**Key decisions baked in:**
- Thinking rendering = buffer-then-render (segmenter emits a complete THINK block on `</think>`; plugin renders one collapsed card). Real-time append deferred.
- `onThinking` is a new independent callback channel (default no-op).
- Thinking is **not** added to conversation history (only the clean assistant text + tool calls are).
- Java fallback path keeps tool-calling (shared `ToolCallParser`) but does **not** display thinking (accepted degradation).
- `OfflineNlNormalizer` is removed with FunctionGemma — its only consumer was the FunctionGemma path, and Qwen3 is Chinese-native (no CN→EN normalization needed).

**Validation-first ordering:** Tasks 1–8 are each verifiable by unit tests / compilation **without a real model**. Task 9 is the real-model end-to-end smoke. Task 10 is docs.

---

## File Structure

**Create:**
- `SwissKit/src/main/java/fan/summer/ai/tools/ThinkingStreamSegmenter.java` — stateful stream segmenter (THINK/CONTENT/suppress). Owns `<think>`/`<tool_call>` marker splitting.
- `SwissKit/src/main/java/fan/summer/ai/tools/Qwen3Adapter.java` — thinking-mode toggle + Hermes system-prompt preamble.
- `SwissKit/src/test/java/fan/summer/ai/tools/ThinkingStreamSegmenterTest.java`
- `SwissKit/src/test/java/fan/summer/ai/tools/Qwen3AdapterTest.java`
- `SwissKit/src/test/java/fan/summer/ai/tools/ToolCallParserHermesTest.java`

**Modify:**
- `SwissKit/src/main/java/fan/summer/ai/tools/ToolCallParser.java` — add Hermes `<tool_call>` regex to `parse` + `stripToolCalls`.
- `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiStreamCallback.java` — add `default void onThinking(String)`.
- `SwissKit/src/main/java/fan/summer/ai/service/LocalChatBackend.java` — remove FunctionGemma wiring; add Qwen3 detection + `chatQwen3Native` loop + Hermes system prompt.
- `SwissKit/src/main/java/fan/summer/ai/util/MarkdownRenderer.java` — add `renderCollapsible` + CSS.
- `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java` — render thinking card; wire `onThinking`.
- `SwissKit/src/main/resources/i18n/messages.properties` + `messages_en.properties` — thinking i18n keys.
- `SwissKit/src/main/java/fan/summer/ai/inference/StopDetector.java` — drop FunctionGemma stop sequences.
- `CLAUDE.md` — note Qwen3-4B as the local tool-calling model.

**Delete:**
- `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java`
- `SwissKit/src/test/java/fan/summer/ai/tools/FunctionGemmaAdapterTest.java`
- `SwissKit/src/main/java/fan/summer/ai/tools/OfflineNlNormalizer.java`
- `SwissKit/src/test/java/fan/summer/ai/tools/OfflineNlNormalizerTest.java`
- `SwissKit/src/main/resources/ai/nl-normalizer.properties`
- `docs/superpowers/specs/2026-06-19-functiongemma-adaptation-design.md`
- `docs/superpowers/plans/2026-06-19-functiongemma-adaptation.md`

---

## Task 1: Remove FunctionGemma support

**Goal:** Strip FunctionGemma (and now-dead `OfflineNlNormalizer`) so the codebase compiles and all remaining tests pass with only the generic native path. Qwen3 detection is added in Task 6.

**Files:**
- Delete: `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java`
- Delete: `SwissKit/src/test/java/fan/summer/ai/tools/FunctionGemmaAdapterTest.java`
- Delete: `SwissKit/src/main/java/fan/summer/ai/tools/OfflineNlNormalizer.java`
- Delete: `SwissKit/src/test/java/fan/summer/ai/tools/OfflineNlNormalizerTest.java`
- Delete: `SwissKit/src/main/resources/ai/nl-normalizer.properties`
- Delete: `docs/superpowers/specs/2026-06-19-functiongemma-adaptation-design.md`
- Delete: `docs/superpowers/plans/2026-06-19-functiongemma-adaptation.md`
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/LocalChatBackend.java`
- Modify: `SwissKit/src/main/java/fan/summer/ai/inference/StopDetector.java`

- [ ] **Step 1: Delete the six FunctionGemma / normalizer files + two old docs**

In the IDEA Project view (or via `mcp__idea__execute_terminal_command`), remove:
```
git rm SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java
git rm SwissKit/src/test/java/fan/summer/ai/tools/FunctionGemmaAdapterTest.java
git rm SwissKit/src/main/java/fan/summer/ai/tools/OfflineNlNormalizer.java
git rm SwissKit/src/test/java/fan/summer/ai/tools/OfflineNlNormalizerTest.java
git rm SwissKit/src/main/resources/ai/nl-normalizer.properties
git rm docs/superpowers/specs/2026-06-19-functiongemma-adaptation-design.md
git rm docs/superpowers/plans/2026-06-19-functiongemma-adaptation.md
```

- [ ] **Step 2: Strip FunctionGemma wiring from `LocalChatBackend.java`**

Remove the import and the two fields:

`mcp__idea__replace_text_in_file` — oldText:
```java
import fan.summer.ai.tools.FunctionGemmaAdapter;
import fan.summer.ai.tools.OfflineNlNormalizer;
import fan.summer.ai.tools.ToolCallParser;
```
newText:
```java
import fan.summer.ai.tools.ToolCallParser;
```

oldText:
```java
    private FunctionGemmaAdapter functionGemmaAdapter;
    private boolean isFunctionGemma;
```
newText: (empty — remove both lines)

- [ ] **Step 3: Reduce `detectModelType` to a no-op hook (Qwen3 branch added in Task 6)**

`mcp__idea__replace_text_in_file` — oldText:
```java
    private void detectModelType(String modelPath) {
        isFunctionGemma = false;
        functionGemmaAdapter = null;
        if (backend != Backend.NATIVE && backend != Backend.JAVA) return;
        String name = Path.of(modelPath).getFileName().toString().toLowerCase();
        isFunctionGemma = name.contains("functiongemma");
        if (isFunctionGemma) {
            functionGemmaAdapter = new FunctionGemmaAdapter();
            log.info("FunctionGemma detected — using native tool calling protocol");
        }
    }
```
newText:
```java
    private void detectModelType(String modelPath) {
        // Model-specific detection hook. The Qwen3 branch is added in the Qwen3 task.
        // The generic native/java path handles every other model.
    }
```

- [ ] **Step 4: Remove the FunctionGemma early-out in `chatNative`**

`mcp__idea__replace_text_in_file` — oldText:
```java
    private void chatNative(List<AiChatMessage> history, float temperature, float topP,
                            int maxTokens, AiStreamCallback callback) {
        if (isFunctionGemma) {
            chatFunctionGemmaNative(history, temperature, topP, maxTokens, callback);
            return;
        }
        Thread.ofVirtual().start(() -> {
```
newText:
```java
    private void chatNative(List<AiChatMessage> history, float temperature, float topP,
                            int maxTokens, AiStreamCallback callback) {
        Thread.ofVirtual().start(() -> {
```

- [ ] **Step 5: Delete the two FunctionGemma methods `chatFunctionGemmaNative` + `generateFunctionGemmaLoop`**

Remove the entire block from the `// ── FunctionGemma single-turn tool calling ────────` comment through the end of `generateFunctionGemmaLoop(...)` (the method that ends right before `// ── Java backend chat ───────────────────────────────`). Use `mcp__idea__replace_text_in_file` with oldText = that whole region and newText = empty.

- [ ] **Step 6: Drop the `isFunctionGemma` reset in `unloadModel`**

oldText:
```java
        isFunctionGemma = false;
        functionGemmaAdapter = null;
        loadedModelPath = null;
```
newText:
```java
        loadedModelPath = null;
```

- [ ] **Step 7: Drop the `isFunctionGemma` short-circuit in `buildSystemPrompt`**

oldText:
```java
    private String buildSystemPrompt() {
        if (isFunctionGemma) return "";
        String base = SwissKitJSettingUi.getAiSystemPrompt();
```
newText:
```java
    private String buildSystemPrompt() {
        String base = SwissKitJSettingUi.getAiSystemPrompt();
```

- [ ] **Step 8: Remove FunctionGemma stop sequences from `StopDetector.java`**

`mcp__idea__replace_text_in_file` on `SwissKit/src/main/java/fan/summer/ai/inference/StopDetector.java` — oldText:
```java
        // Gemma
        "<end_of_turn>",
        "<start_of_turn>",
        // FunctionGemma
        "<end_function_call>",
        "<start_function_call>",
        "<end_function_response>",
        "<start_function_response>",
        // Generic role tags some fine-tunes emit
```
newText:
```java
        // Gemma
        "<end_of_turn>",
        "<start_of_turn>",
        // Generic role tags some fine-tunes emit
```

- [ ] **Step 9: Verify the build compiles**

Run via IDEA Maven tool window: **SwissKit → Lifecycle → compile** (or `mcp__idea__build_project`). Expected: BUILD SUCCESS, no references to `FunctionGemma` or `OfflineNlNormalizer`.

- [ ] **Step 10: Verify remaining tests pass**

Run via IDEA Maven tool window: **SwissKit → Lifecycle → test**. Expected: all green. (`FunctionGemmaAdapterTest` and `OfflineNlNormalizerTest` are gone; nothing else referenced them.)

- [ ] **Step 11: Commit**

```
git add -A
git commit -m "🔥 chore(ai): remove FunctionGemma adapter and OfflineNlNormalizer

FunctionGemma is superseded by Qwen3-4B as the local tool-calling model
(see docs/superpowers/specs/2026-06-22-qwen3-4b-toolcall-design.md).
OfflineNlNormalizer's only consumer was the FunctionGemma path and Qwen3
is Chinese-native, so it is removed as dead code."
```

---

## Task 2: Add Hermes `<tool_call>` parsing to `ToolCallParser`

**Goal:** The shared parser recognizes Qwen3's Hermes format `<tool_call>{"name":"..","arguments":{..}}</tool_call>` in both `parse` and `stripToolCalls`, alongside the existing Qwen2.5 / generic patterns.

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/ToolCallParser.java`
- Test: `SwissKit/src/test/java/fan/summer/ai/tools/ToolCallParserHermesTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/tools/ToolCallParserHermesTest.java`:
```java
package fan.summer.ai.tools;

import fan.summer.api.ai.AiToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallParserHermesTest {

    @Test
    void parsesSingleHermesToolCall() {
        String text = "<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Beijing\"}}\n</tool_call>";
        List<AiToolCall> calls = ToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("get_weather", calls.get(0).name());
        assertEquals("Beijing", calls.get(0).arguments().get("city"));
    }

    @Test
    void parsesMultipleHermesToolCallsInOneTurn() {
        String text = "<tool_call>\n{\"name\": \"a\", \"arguments\": {\"x\": 1}}\n</tool_call>\n"
                    + "<tool_call>\n{\"name\": \"b\", \"arguments\": {\"y\": 2}}\n</tool_call>";
        List<AiToolCall> calls = ToolCallParser.parse(text);
        assertEquals(2, calls.size());
        assertEquals("a", calls.get(0).name());
        assertEquals("b", calls.get(1).name());
    }

    @Test
    void parsesHermesCallEmbeddedInProse() {
        String text = "I'll check that for you.\n<tool_call>\n{\"name\": \"hash\", \"arguments\": {\"algo\": \"md5\"}}\n</tool_call>\n";
        List<AiToolCall> calls = ToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("hash", calls.get(0).name());
    }

    @Test
    void stripToolCallsRemovesHermesBlock() {
        String text = "Prefix.\n<tool_call>\n{\"name\": \"a\", \"arguments\": {}}\n</tool_call>\nSuffix.";
        String stripped = ToolCallParser.stripToolCalls(text);
        assertTrue(stripped.contains("Prefix"));
        assertTrue(stripped.contains("Suffix"));
        assertFalse(stripped.contains("tool_call"));
    }

    @Test
    void hermesAndQwen25PatternsCoexist() {
        // Existing Qwen2.5 special-token form still parses (regression guard).
        String qwen25 = "<|tool_call_begin|>{\"name\":\"q\",\"arguments\":{\"k\":\"v\"}}<|tool_call_end|>";
        assertEquals("q", ToolCallParser.parse(qwen25).get(0).name());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run via IDEA Maven tool window (test scope): `ToolCallParserHermesTest`. Expected: FAIL — `parse` returns empty for the Hermes form (no matching pattern yet).

- [ ] **Step 3: Add the Hermes regex + wire it into `parse` and `stripToolCalls`**

In `SwissKit/src/main/java/fan/summer/ai/tools/ToolCallParser.java`, add the constant after `GENERIC_TOOL_CALL`:
```java
    private static final Pattern HERMES_TOOL_CALL = Pattern.compile(
        "<tool_call>\\s*\\{\\s*\"name\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?})\\s*}\\s*</tool_call>",
        Pattern.DOTALL
    );
```

In `parse(...)`, insert a Hermes branch **after** the Qwen block and **before** the generic block:
```java
        m = HERMES_TOOL_CALL.matcher(text);
        while (m.find()) {
            calls.add(buildCall(m.group(1), m.group(2)));
        }
        if (!calls.isEmpty()) {
            log.debug("Parsed {} tool call(s) via Hermes pattern", calls.size());
            return calls;
        }
```

In `stripToolCalls(...)`, replace the body with:
```java
    public static String stripToolCalls(String text) {
        if (text == null) return "";
        String result = QWEN_TOOL_CALL.matcher(text).replaceAll("");
        result = HERMES_TOOL_CALL.matcher(result).replaceAll("");
        log.debug("stripToolCalls: originalLength={}, resultLength={}", text.length(), result.length());
        return result.trim();
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run `ToolCallParserHermesTest`. Expected: all 5 PASS.

- [ ] **Step 5: Commit**

```
git add SwissKit/src/main/java/fan/summer/ai/tools/ToolCallParser.java SwissKit/src/test/java/fan/summer/ai/tools/ToolCallParserHermesTest.java
git commit -m "✨ feat(ai): parse Qwen3 Hermes <tool_call> format in ToolCallParser"
```

---

## Task 3: Add `onThinking` callback channel

**Goal:** A new independent channel for reasoning/thinking text. Default no-op keeps every existing implementor source-compatible.

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiStreamCallback.java`

- [ ] **Step 1: Add the default method**

In `AiStreamCallback.java`, add after `onToken`:
```java
    /**
     * Called when the model emits a completed reasoning/thinking block (e.g. Qwen3's
     * {@code <think>…</think>}). The fragment is the full text of one closed thinking
     * block — callers render it as a unit. The default implementation discards it.
     *
     * <p>Only the local Qwen3 native backend invokes this today; cloud and Java-fallback
     * paths never call it (thinking is simply not surfaced there).</p>
     *
     * @param fragment the complete text of one thinking block (never {@code null})
     */
    default void onThinking(String fragment) {}
```

- [ ] **Step 2: Verify the API module compiles**

Run via IDEA Maven: **SwissKitJ-Api → Lifecycle → compile**, then **SwissKit → Lifecycle → compile**. Expected: BUILD SUCCESS (the default method is backward-compatible).

- [ ] **Step 3: Commit**

```
git add SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiStreamCallback.java
git commit -m "✨ feat(ai): add onThinking stream-callback channel for reasoning display"
```

---

## Task 4: Implement `ThinkingStreamSegmenter`

**Goal:** A stateful segmenter that consumes the raw Qwen3 token stream and emits THINK / CONTENT segments, suppressing `<tool_call>` regions from display. Markers split across token boundaries are held back until disambiguated (same idea as `StopDetector.endsWithPartialStop`).

This is the hardest unit — full TDD.

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/ThinkingStreamSegmenter.java`
- Test: `SwissKit/src/test/java/fan/summer/ai/tools/ThinkingStreamSegmenterTest.java`

- [ ] **Step 1: Write the failing test suite**

Create `SwissKit/src/test/java/fan/summer/ai/tools/ThinkingStreamSegmenterTest.java`:
```java
package fan.summer.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThinkingStreamSegmenterTest {

    private static List<String> contents(List<ThinkingStreamSegmenter.Segment> segs) {
        return texts(segs, ThinkingStreamSegmenter.Type.CONTENT);
    }
    private static List<String> thinks(List<ThinkingStreamSegmenter.Segment> segs) {
        return texts(segs, ThinkingStreamSegmenter.Type.THINK);
    }
    private static List<String> texts(List<ThinkingStreamSegmenter.Segment> segs, ThinkingStreamSegmenter.Type t) {
        List<String> out = new ArrayList<>();
        for (var s : segs) if (s.type() == t) out.add(s.text());
        return out;
    }

    @Test
    void pureContentPassesThrough() {
        var seg = new ThinkingStreamSegmenter();
        var out = seg.feed("Hello world");
        assertEquals(List.of("Hello world"), contents(out));
        assertTrue(thinks(out).isEmpty());
    }

    @Test
    void thinkBlockRoutedToThinkAndRemovedFromContent() {
        var seg = new ThinkingStreamSegmenter();
        seg.feed("Before ");
        seg.feed("<think>");
        seg.feed("reasoning here");
        var out = seg.feed("</think>");
        assertEquals(List.of("Before "), contents(out.subList(0, 1)));
        // The flush-on-close: collect everything across feeds
        var all = new ArrayList<ThinkingStreamSegmenter.Segment>();
        all.addAll(out);
        // re-run cleanly in one segmenter
        var s2 = new ThinkingStreamSegmenter();
        s2.feed("Before ");
        s2.feed("<think>");
        s2.feed("reasoning here");
        var closed = s2.feed("</think>After text");
        assertEquals(List.of("reasoning here"), thinks(closed));
        assertEquals(List.of("Before ", "After text"), contents(closed));
    }

    @Test
    void toolCallRegionIsSuppressed() {
        var seg = new ThinkingStreamSegmenter();
        var out = seg.feed("OK <tool_call>{\"name\":\"x\",\"arguments\":{}}</tool_call> done");
        assertEquals(List.of("OK ", " done"), contents(out));
        assertTrue(thinks(out).isEmpty());
    }

    @Test
    void splitThinkMarkerAcrossTokensHoldsBack() {
        var seg = new ThinkingStreamSegmenter();
        var a = seg.feed("Hej <thi");
        var b = seg.feed("nk>secret");
        var c = seg.feed("</think>end");
        // "Hej " emitted as content immediately; "<thi" held; then think opens
        var allContent = new ArrayList<String>();
        var allThink = new ArrayList<String>();
        for (var batch : List.of(a, b, c)) for (var s : batch) {
            (s.type() == ThinkingStreamSegmenter.Type.CONTENT ? allContent : allThink).add(s.text());
        }
        assertEquals(List.of("Hej "), allContent);
        assertEquals(List.of("secret"), allThink);
        // final content "end" still pending until flush
        var flushed = seg.flush();
        assertEquals(List.of("end"), contents(flushed));
    }

    @Test
    void splitToolCallOpenMarkerHoldsBack() {
        var seg = new ThinkingStreamSegmenter();
        var a = seg.feed("pre <tool_");
        var b = seg.feed("call>{\"name\":\"y\",\"arguments\":{}}");
        var c = seg.feed("</tool_call>post");
        var allContent = new ArrayList<String>();
        for (var batch : List.of(a, b, c)) for (var s : batch)
            if (s.type() == ThinkingStreamSegmenter.Type.CONTENT) allContent.add(s.text());
        assertEquals(List.of("pre ", "post"), allContent);
    }

    @Test
    void flushEmitsTrailingContent() {
        var seg = new ThinkingStreamSegmenter();
        seg.feed("just text");
        var flushed = seg.flush();
        assertEquals(List.of("just text"), contents(flushed));
    }

    @Test
    void flushEmitsUnclosedThinkAsThink() {
        var seg = new ThinkingStreamSegmenter();
        seg.feed("<think>partial with no close");
        var flushed = seg.flush();
        assertEquals(List.of("partial with no close"), thinks(flushed));
    }

    @Test
    void flushDiscardsUnclosedToolCall() {
        var seg = new ThinkingStreamSegmenter();
        seg.feed("visible <tool_call>{\"name\":\"z\",\"arguments\":");
        var flushed = seg.flush();
        assertEquals(List.of("visible "), contents(flushed));
    }

    @Test
    void multipleThinkBlocks() {
        var seg = new ThinkingStreamSegmenter();
        var out = seg.feed("<think>t1</think>A<think>t2</think>B");
        assertEquals(List.of("t1", "t2"), thinks(out));
        assertEquals(List.of("A", "B"), contents(out));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run `ThinkingStreamSegmenterTest`. Expected: compile FAIL — class does not exist.

- [ ] **Step 3: Implement the segmenter**

Create `SwissKit/src/main/java/fan/summer/ai/tools/ThinkingStreamSegmenter.java`:
```java
package fan.summer.ai.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateful stream segmenter for Qwen3 hybrid-reasoning output.
 *
 * <p>Splits the raw token stream into three regions:
 * <ul>
 *   <li>{@code <think>…</think>} — routed to the thinking display ({@link Type#THINK})</li>
 *   <li>{@code <tool_call>…</tool_call>} — suppressed from display entirely</li>
 *   <li>plain content — the visible answer ({@link Type#CONTENT})</li>
 * </ul>
 *
 * <p>Markers may be split across token boundaries (the model emits {@code "<thi"} then
 * {@code "nk>"}). When no full marker is present, the segmenter holds back the tail of
 * the buffer if it could be the prefix of a marker, so a half marker is never emitted
 * as content. This mirrors {@link fan.summer.ai.inference.StopDetector#endsWithPartialStop}.
 *
 * <p>The segmenter is single-use per generation round. Call {@link #flush()} at EOS to
 * drain any trailing content / unclosed think block.
 */
public final class ThinkingStreamSegmenter {

    /** Displayable segment type. Tool-call regions produce no segment at all. */
    public enum Type { THINK, CONTENT }

    public record Segment(Type type, String text) {}

    private static final String THINK_OPEN  = "<think>";
    private static final String THINK_CLOSE = "</think>";
    private static final String CALL_OPEN   = "<tool_call>";
    private static final String CALL_CLOSE  = "</tool_call>";
    private static final String[] MARKERS = { THINK_OPEN, THINK_CLOSE, CALL_OPEN, CALL_CLOSE };
    private static final int MAX_MARKER = MARKERS[0].length();
    static {
        int m = MAX_MARKER;
        for (String mk : MARKERS) m = Math.max(m, mk.length());
        // MAX_MARKER reused as the holdback ceiling; assign via static init for clarity.
    }

    private final StringBuilder pending = new StringBuilder();
    private boolean inThink = false;
    private boolean inToolCall = false;

    /**
     * Feed one token fragment; returns the displayable segments produced by this token
     * (possibly empty — the token may have been held back or fallen inside a region).
     */
    public List<Segment> feed(String fragment) {
        if (fragment == null || fragment.isEmpty()) return List.of();
        pending.append(fragment);
        return scan();
    }

    /**
     * Drain pending state at end-of-stream. An unclosed {@code <think>} is emitted as a
     * THINK segment; an unclosed {@code <tool_call>} is discarded; trailing content is
     * emitted as CONTENT.
     */
    public List<Segment> flush() {
        List<Segment> out = new ArrayList<>();
        if (inThink) {
            out.add(new Segment(Type.THINK, pending.toString()));
        } else if (!inToolCall && !pending.isEmpty()) {
            out.add(new Segment(Type.CONTENT, pending.toString()));
        }
        pending.setLength(0);
        inThink = false;
        inToolCall = false;
        return out;
    }

    private List<Segment> scan() {
        List<Segment> out = new ArrayList<>();
        while (true) {
            if (inToolCall) {
                int close = pending.indexOf(CALL_CLOSE);
                if (close < 0) break;                       // keep buffering the suppressed region
                pending.delete(0, close + CALL_CLOSE.length());
                inToolCall = false;
                continue;
            }
            if (inThink) {
                int close = pending.indexOf(THINK_CLOSE);
                if (close < 0) break;                       // keep buffering think text
                out.add(new Segment(Type.THINK, pending.substring(0, close)));
                pending.delete(0, close + THINK_CLOSE.length());
                inThink = false;
                continue;
            }
            int tOpen = pending.indexOf(THINK_OPEN);
            int cOpen = pending.indexOf(CALL_OPEN);
            int next = minPositive(tOpen, cOpen);
            if (next < 0) {
                int hold = longestMarkerPrefix(pending, MAX_MARKER);
                int emitLen = pending.length() - hold;
                if (emitLen > 0) {
                    out.add(new Segment(Type.CONTENT, pending.substring(0, emitLen)));
                    pending.delete(0, emitLen);
                }
                break;
            }
            if (next > 0) {
                out.add(new Segment(Type.CONTENT, pending.substring(0, next)));
                pending.delete(0, next);
            }
            if (next == tOpen) {
                pending.delete(0, THINK_OPEN.length());
                inThink = true;
            } else {
                pending.delete(0, CALL_OPEN.length());
                inToolCall = true;
            }
        }
        return out;
    }

    /** Largest k such that the buffer's tail of length k is a proper prefix of some marker. */
    private static int longestMarkerPrefix(CharSequence buf, int maxMarkerLen) {
        int len = buf.length();
        int limit = Math.min(maxMarkerLen - 1, len);
        int best = 0;
        for (int k = limit; k >= 1; k--) {
            for (String m : MARKERS) {
                if (k < m.length() && buf.regionMatches(len - k, m, 0, k)) {
                    best = Math.max(best, k);
                    break;
                }
            }
        }
        return best;
    }

    private static int minPositive(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }
}
```

Fix the `MAX_MARKER` initializer so it equals the true longest marker length (12 = `</tool_call>`). Replace the static block with:
```java
    private static final int MAX_MARKER;
    static {
        int m = 0;
        for (String mk : MARKERS) m = Math.max(m, mk.length());
        MAX_MARKER = m;
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run `ThinkingStreamSegmenterTest`. Expected: all 9 PASS.

- [ ] **Step 5: Commit**

```
git add SwissKit/src/main/java/fan/summer/ai/tools/ThinkingStreamSegmenter.java SwissKit/src/test/java/fan/summer/ai/tools/ThinkingStreamSegmenterTest.java
git commit -m "✨ feat(ai): ThinkingStreamSegmenter for Qwen3 thinking/tool-call streaming"
```

---

## Task 5: Implement `Qwen3Adapter`

**Goal:** A thin adapter owning the thinking-mode toggle and the Hermes system-prompt preamble.

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/Qwen3Adapter.java`
- Test: `SwissKit/src/test/java/fan/summer/ai/tools/Qwen3AdapterTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/tools/Qwen3AdapterTest.java`:
```java
package fan.summer.ai.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Qwen3AdapterTest {

    @Test
    void augmentAddsHermesDirective() {
        String out = new Qwen3Adapter().augmentSystemPrompt("You are helpful.");
        assertTrue(out.contains("You are helpful."));
        assertTrue(out.contains("<tool_call>"));
    }

    @Test
    void thinkingEnabledByDefaultDoesNotEmitNoThink() {
        String out = new Qwen3Adapter().augmentSystemPrompt("base");
        assertFalse(out.contains("/no_think"));
    }

    @Test
    void disablingThinkingInjectsNoThink() {
        Qwen3Adapter a = new Qwen3Adapter();
        a.setThinkingEnabled(false);
        assertTrue(a.augmentSystemPrompt("base").contains("/no_think"));
    }

    @Test
    void nullBaseIsTolerated() {
        assertDoesNotThrow(() -> new Qwen3Adapter().augmentSystemPrompt(null));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run `Qwen3AdapterTest`. Expected: compile FAIL — class missing.

- [ ] **Step 3: Implement the adapter**

Create `SwissKit/src/main/java/fan/summer/ai/tools/Qwen3Adapter.java`:
```java
package fan.summer.ai.tools;

/**
 * Qwen3-specific tool-calling adapter.
 *
 * <p>Owns the two things that are genuinely Qwen3-specific on top of the shared
 * {@link ToolCallParser} (Hermes regex) and the {@link ThinkingStreamSegmenter}:
 * <ul>
 *   <li>the Hermes tool-call directive injected into the system prompt, which reliably
 *       triggers {@code <tool_call>} emission even though the host's chat template is
 *       a simplified ChatML re-implementation;</li>
 *   <li>the thinking-mode toggle ({@code /no_think} suppresses Qwen3 reasoning).</li>
 * </ul>
 *
 * <p>Thinking defaults to ON — it materially improves tool-calling judgment on a 4B
 * model. The toggle is a future hook (e.g. a latency-sensitive setting).
 */
public final class Qwen3Adapter {

    private static final String HERMES_DIRECTIVE =
        "\n\nWhen you need to call a tool, emit exactly one block per call:\n" +
        "<tool_call>\n{\"name\": \"<tool_name>\", \"arguments\": {<param>: <value>}}\n</tool_call>\n" +
        "Do not wrap tool calls in markdown code fences.";

    private boolean thinkingEnabled = true;

    public boolean isThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(boolean enabled) { this.thinkingEnabled = enabled; }

    /**
     * Append the Hermes directive (and, if thinking is disabled, {@code /no_think})
     * to the base system prompt.
     *
     * @param base the base system prompt; {@code null} is treated as empty
     * @return the augmented system prompt, never {@code null}
     */
    public String augmentSystemPrompt(String base) {
        String out = (base == null ? "" : base) + HERMES_DIRECTIVE;
        if (!thinkingEnabled) out += "\n\n/no_think";
        return out;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run `Qwen3AdapterTest`. Expected: all 4 PASS.

- [ ] **Step 5: Commit**

```
git add SwissKit/src/main/java/fan/summer/ai/tools/Qwen3Adapter.java SwissKit/src/test/java/fan/summer/ai/tools/Qwen3AdapterTest.java
git commit -m "✨ feat(ai): Qwen3Adapter for Hermes system prompt + thinking toggle"
```

---

## Task 6: Wire Qwen3 into `LocalChatBackend`

**Goal:** Detect a Qwen3 GGUF by filename, route it through a new `chatQwen3Native` loop that streams THINK → `onThinking`, CONTENT → `onToken`, and extracts tool calls terminally via the shared `ToolCallParser`.

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/LocalChatBackend.java`

- [ ] **Step 1: Add the Qwen3 fields + imports**

Add imports:
```java
import fan.summer.ai.tools.Qwen3Adapter;
import fan.summer.ai.tools.ThinkingStreamSegmenter;
```

Add fields next to `loadedModelPath`:
```java
    private Qwen3Adapter qwen3Adapter;
    private boolean isQwen3;
```

- [ ] **Step 2: Implement Qwen3 detection in `detectModelType`**

Replace the no-op body from Task 1:
```java
    private void detectModelType(String modelPath) {
        isQwen3 = false;
        qwen3Adapter = null;
        if (backend != Backend.NATIVE && backend != Backend.JAVA) return;
        String name = Path.of(modelPath).getFileName().toString().toLowerCase();
        isQwen3 = name.contains("qwen3");
        if (isQwen3) {
            qwen3Adapter = new Qwen3Adapter();
            log.info("Qwen3 detected — Hermes tool calling + thinking stream");
        }
    }
```

- [ ] **Step 3: Route Qwen3 in `chatNative`**

In `chatNative`, add the Qwen3 early-out right after the method opening brace (before `Thread.ofVirtual`):
```java
        if (isQwen3) {
            chatQwen3Native(history, temperature, topP, maxTokens, callback);
            return;
        }
```

- [ ] **Step 4: Add the Qwen3 chat loop**

Add these two methods immediately after `generateNativeWithToolLoop` (before the `// ── Java backend chat` section):

```java
    // ── Qwen3 native chat (Hermes tool calling + thinking stream) ─────────

    private void chatQwen3Native(List<AiChatMessage> history, float temperature,
                                 float topP, int maxTokens, AiStreamCallback callback) {
        Thread.ofVirtual().start(() -> {
            try {
                String systemPrompt = buildSystemPrompt();
                String prompt = buildNativePrompt(history, systemPrompt);
                generateQwen3Loop(prompt, temperature, topP, maxTokens, history, callback, 0);
            } catch (Exception e) {
                log.error("Qwen3 generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }

    private void generateQwen3Loop(String prompt, float temperature, float topP,
                                   int maxTokens, List<AiChatMessage> history,
                                   AiStreamCallback callback, int round) {
        if (round >= MAX_TOOL_ROUNDS || workerClient == null || !workerClient.isAlive()) return;

        GenerateParams genParams = new GenerateParams()
            .temperature(temperature).topP(topP).maxTokens(maxTokens);

        ThinkingStreamSegmenter segmenter = new ThinkingStreamSegmenter();
        TokenBatcher contentBatcher = TokenBatcher.forCallback(callback::onToken);

        workerClient.generate(prompt, genParams, new GenerateCallback() {
            @Override
            public boolean onToken(String tokenText) {
                for (var seg : segmenter.feed(tokenText)) {
                    if (seg.type() == ThinkingStreamSegmenter.Type.THINK) {
                        final String t = seg.text();
                        Platform.runLater(() -> callback.onThinking(t));
                    } else {
                        contentBatcher.add(seg.text());
                    }
                }
                return true;
            }

            @Override
            public void onDone(String fullText, int tokenCount, double tokPerSec) {
                for (var seg : segmenter.flush()) {
                    if (seg.type() == ThinkingStreamSegmenter.Type.THINK) {
                        final String t = seg.text();
                        Platform.runLater(() -> callback.onThinking(t));
                    } else {
                        contentBatcher.add(seg.text());
                    }
                }
                contentBatcher.close();

                List<AiToolCall> toolCalls = ToolCallParser.parse(fullText);
                if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                    history.add(AiChatMessage.assistantWithTools(
                        ToolCallParser.stripToolCalls(ThinkingStreamSegmenter.stripThink(fullText)),
                        toolCalls));
                    for (AiToolCall tc : toolCalls) {
                        Platform.runLater(() -> callback.onToolCall(tc));
                        AiToolResult result = ToolExecutor.execute(tc.name(), tc.arguments());
                        Platform.runLater(() -> callback.onToolResult(tc.id(), result));
                        history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));
                    }
                    String newPrompt = buildNativePrompt(history, buildSystemPrompt());
                    generateQwen3Loop(newPrompt, temperature, topP, maxTokens,
                                      history, callback, round + 1);
                } else {
                    // Thinking is intentionally NOT added to history — only clean text.
                    String clean = ToolCallParser.stripToolCalls(
                        ThinkingStreamSegmenter.stripThink(fullText));
                    final String answer = clean;
                    Platform.runLater(() -> callback.onComplete(answer, tokenCount, tokPerSec));
                }
            }

            @Override
            public void onError(String message) {
                contentBatcher.close();
                Platform.runLater(() -> callback.onError(new RuntimeException(message)));
            }
        });
    }
```

- [ ] **Step 5: Add the static `stripThink` helper to `ThinkingStreamSegmenter`**

Add to `ThinkingStreamSegmenter.java`:
```java
    private static final java.util.regex.Pattern THINK_BLOCK =
        java.util.regex.Pattern.compile("<think>.*?</think>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern THINK_OPEN_ONLY =
        java.util.regex.Pattern.compile("<think>.*", java.util.regex.Pattern.DOTALL);

    /**
     * Removes all {@code <think>…</think>} blocks (and a trailing unclosed
     * {@code <think>…}) from text. Used to clean assistant turns before they are
     * added to history or returned as the final answer.
     *
     * @param text source text; may be {@code null}
     * @return text with think blocks removed, trimmed; empty if input was {@code null}
     */
    public static String stripThink(String text) {
        if (text == null) return "";
        String out = THINK_BLOCK.matcher(text).replaceAll("");
        out = THINK_OPEN_ONLY.matcher(out).replaceAll("");
        return out.trim();
    }
```

Add a regression test to `ThinkingStreamSegmenterTest.java`:
```java
    @Test
    void stripThinkRemovesClosedAndUnclosedBlocks() {
        assertEquals("a b", ThinkingStreamSegmenter.stripThink("a <think>x</think> b"));
        assertEquals("a", ThinkingStreamSegmenter.stripThink("a <think>unclosed"));
        assertEquals("", ThinkingStreamSegmenter.stripThink(null));
    }
```

- [ ] **Step 6: Augment the system prompt for Qwen3**

In `buildSystemPrompt`, after assembling `base + toolDefs`, wrap for Qwen3:
```java
    private String buildSystemPrompt() {
        String base = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools());
        String composed = toolDefs.isEmpty() ? base : base + "\n\n" + toolDefs;
        if (isQwen3 && qwen3Adapter != null) {
            return qwen3Adapter.augmentSystemPrompt(composed);
        }
        return composed;
    }
```

- [ ] **Step 7: Reset Qwen3 state in `unloadModel`**

In `unloadModel`, after `loadedModelPath = null;` add:
```java
        isQwen3 = false;
        qwen3Adapter = null;
```

- [ ] **Step 8: Verify compilation + full test suite**

Run IDEA Maven: **SwissKit → Lifecycle → test**. Expected: BUILD SUCCESS, all tests green (segmenter + stripThink + Hermes + existing).

- [ ] **Step 9: Commit**

```
git add SwissKit/src/main/java/fan/summer/ai/service/LocalChatBackend.java SwissKit/src/main/java/fan/summer/ai/tools/ThinkingStreamSegmenter.java SwissKit/src/test/java/fan/summer/ai/tools/ThinkingStreamSegmenterTest.java
git commit -m "✨ feat(ai): wire Qwen3 chat loop with thinking stream + Hermes tool calling"
```

---

## Task 7: Add `MarkdownRenderer.renderCollapsible`

**Goal:** Render a finalized markdown block inside a collapsed `<details>` element (for the thinking card).

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/util/MarkdownRenderer.java`

- [ ] **Step 1: Add the `details`/`summary` CSS**

In the `CSS` text block, append before the closing `"""`:
```css
        details.sk-collapse {
            background: rgba(255,255,255,0.03);
            border: 1px solid rgba(255,255,255,0.06);
            border-radius: 10px;
            padding: 6px 12px;
            margin: 4px 0;
        }
        details.sk-collapse > summary {
            cursor: pointer;
            color: rgba(255,255,255,0.50);
            font-size: 12px;
            font-weight: 600;
            list-style: none;
        }
        details.sk-collapse[open] > summary { margin-bottom: 6px; }
```

- [ ] **Step 2: Add `renderCollapsible` + a private HTML-escape helper**

Add after `renderPlain`:
```java
    /**
     * Renders markdown inside a collapsed {@code <details>} block with the given
     * summary label. Used for reasoning/thinking display.
     *
     * @param summary  the visible summary text (HTML-escaped); shown when collapsed
     * @param markdown the markdown body; {@code null}/blank yields an empty document
     * @return a full HTML document with a collapsed {@code <details>} block
     */
    public static String renderCollapsible(String summary, String markdown) {
        if (markdown == null || markdown.isBlank()) return wrapHtml("");
        Node document = PARSER.parse(markdown);
        String inner = RENDERER.render(document);
        return wrapHtml(
            "<details class=\"sk-collapse\"><summary>" + escapeHtml(summary)
            + "</summary>" + inner + "</details>");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
```

- [ ] **Step 3: Verify compilation**

Run IDEA Maven: **SwissKit → Lifecycle → compile**. Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
git add SwissKit/src/main/java/fan/summer/ai/util/MarkdownRenderer.java
git commit -m "✨ feat(ai): MarkdownRenderer.renderCollapsible for thinking display"
```

---

## Task 8: Display thinking in `AiChatPlugin`

**Goal:** Render each completed thinking block as a collapsed card above the assistant response bubble; wire `onThinking` in both callback sites.

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java`
- Modify: `SwissKit/src/main/resources/i18n/messages.properties`
- Modify: `SwissKit/src/main/resources/i18n/messages_en.properties`

- [ ] **Step 1: Add i18n keys**

Append to `messages.properties`:
```
builtin.ai.thinking=思考过程
builtin.ai.thinkingSummary=💭 模型思考过程（点击展开）
```
Append to `messages_en.properties`:
```
builtin.ai.thinking=Thinking
builtin.ai.thinkingSummary=💭 Model reasoning (click to expand)
```

- [ ] **Step 2: Store the assistant wrapper reference**

In `AiChatView`, add a field near `currentResponseView`:
```java
        private VBox currentAssistantWrapper;
```

In `addAssistantBubble`, capture the wrapper. Change the method so that just before `return webView;` it stores the wrapper, and returns the webView. Concretely, replace:
```java
            messageList.getChildren().add(wrapper);
            scrollToBottom();
            return webView;
        }
```
with:
```java
            messageList.getChildren().add(wrapper);
            currentAssistantWrapper = wrapper;
            scrollToBottom();
            return webView;
        }
```

- [ ] **Step 3: Add the `addThinkingCard` method**

Add this method inside `AiChatView` (e.g. right after `addAssistantBubble`):
```java
        private void addThinkingCard(String thinkingMarkdown) {
            Platform.runLater(() -> {
                Label label = new Label("💭 " + I18n.get("builtin.ai.thinking"));
                label.setStyle("-fx-text-fill: rgba(255,255,255,0.45); -fx-font-size: 11px; -fx-font-weight: bold;");

                WebView wv = new WebView();
                wv.setMaxWidth(560);
                wv.setPrefWidth(560);
                wv.setMinHeight(24);
                wv.setPrefHeight(24);
                wv.setStyle(
                    "-fx-background-color: #1e1e2e;" +
                    "-fx-border-color: rgba(255,255,255,0.06);" +
                    "-fx-border-width: 1px; -fx-border-radius: 12px; -fx-background-radius: 12px;"
                );
                wv.getEngine().loadContent(
                    MarkdownRenderer.renderCollapsible(I18n.get("builtin.ai.thinkingSummary"), thinkingMarkdown));
                autoResizeWebView(wv);

                VBox wrapper = new VBox(3, label, wv);
                wrapper.setAlignment(Pos.CENTER_LEFT);
                wrapper.setPadding(new Insets(2, 0, 2, 0));

                int idx = (currentAssistantWrapper == null)
                    ? messageList.getChildren().size()
                    : messageList.getChildren().indexOf(currentAssistantWrapper);
                messageList.getChildren().add(Math.max(0, idx), wrapper);
                scrollToBottom();
            });
        }
```

- [ ] **Step 4: Override `onThinking` in the `onSend` callback**

In the `new AiStreamCallback() { … }` inside `onSend`, add (alongside `onToken`):
```java
                    @Override
                    public void onThinking(String fragment) {
                        addThinkingCard(fragment);
                    }
```

- [ ] **Step 5: Override `onThinking` in the `executeSlashGuided` callback**

Add the identical override inside the `executeSlashGuided` callback:
```java
                    @Override
                    public void onThinking(String fragment) {
                        addThinkingCard(fragment);
                    }
```

- [ ] **Step 6: Verify compilation**

Run IDEA Maven: **SwissKit → Lifecycle → compile**. Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```
git add SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java SwissKit/src/main/resources/i18n/messages.properties SwissKit/src/main/resources/i18n/messages_en.properties
git commit -m "✨ feat(ai): display Qwen3 thinking as a collapsible card in AiChatPlugin"
```

---

## Task 9: End-to-end smoke test (real Qwen3-4B GGUF)

**Goal:** Prove the full chain — model → ChatTemplate → segmenter → tool execution → thinking rendering — works with a real model. This is the "small-scale validation" the user asked for. No automated test; record observations in this file.

**Prerequisite — obtain the GGUF (skip if already present):**
Download `Qwen3-4B-Instruct-Q4_K_M.gguf` (~2.5 GB) from HuggingFace:
- `Qwen/Qwen3-4B-Instruct-GGUF` (official) or `bartowski/Qwen_Qwen3-4B-Instruct-GGUF` (mirror).
Place it anywhere on disk, e.g. `~/models/qwen3-4b-instruct-q4_k_m.gguf`.

- [ ] **Step 1: Build the app**

Run via IDEA Maven: install API, then package `SwissKit` (skipTests):
```
# via IDEA Maven tool window:
# 1) SwissKitJ-Api → Lifecycle → install (-DskipTests)
# 2) SwissKit → Lifecycle → package (-DskipTests)
```
Expected: `SwissKit/target/SwissKitJ-3.1.0.jar` produced.

- [ ] **Step 2: Launch and load the model**

Run the jar. Settings → AI mode = **local** → load the Qwen3-4B GGUF. Expected: log line `Qwen3 detected — Hermes tool calling + thinking stream`; model label shows the filename; no native-unavailable banner (native JNI loads).

- [ ] **Step 3: Smoke case A — plain chat (no tools)**

Type: `你好,介绍一下你自己`
Expected:
- A 💭 thinking card appears (collapsed) above the response.
- The response bubble shows a clean Chinese answer (no `<think>` or `<tool_call>` tokens leak).
- Status bar shows token count + tok/s.

Record: ✓ / ✗ and notes.

- [ ] **Step 4: Smoke case B — single tool call**

Register a built-in AI tool is already done by `BuiltinAiToolRegistrar`. Type: `把文本 "hello" 转成 base64`
Expected:
- 💭 thinking card (collapsed).
- A ⚙ tool-call card `base64_encode` with the argument.
- A ✓ tool-result card with `aGVsbG8=`.
- A final assistant answer referencing the result.
- No raw `<tool_call>{…}` JSON visible at any point during streaming.

Record: ✓ / ✗.

- [ ] **Step 5: Smoke case C — multi-round tool loop**

Type a request that needs two tool calls or a tool-then-summarize flow (e.g. hash a string then format it, per the registered tools). Expected: two tool-call/result pairs, then a final answer, within `MAX_TOOL_ROUNDS` (8).

Record: ✓ / ✗.

- [ ] **Step 6: Smoke case D — thinking-mode sanity (optional)**

Temporarily set `qwen3Adapter.setThinkingEnabled(false)` via a scratch change or a settings hook, rebuild, re-run case B. Expected: no 💭 card; tool call still fires (`/no_think` injected). Revert.

Record: ✓ / ✗ / skipped.

- [ ] **Step 7: Record results in this plan**

Append a `## Smoke Results` section below with the date, model file + quant, and per-case ✓/✗ + notes. If any case fails, file the specific symptom (e.g. "model emitted bare JSON, not `<tool_call>`") — that triggers the Hermes-prompt-tuning lever in `Qwen3Adapter`.

- [ ] **Step 8: Commit the recorded results**

```
git add docs/superpowers/plans/2026-06-22-qwen3-4b-toolcall.md
git commit -m "✅ test(ai): record Qwen3-4B end-to-end smoke results"
```

---

## Task 10: Update docs

**Goal:** Reflect the new local tool-calling model in `CLAUDE.md`.

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the AI tools note in CLAUDE.md**

In the `## Architecture` → startup sequence (step 6) area, and in the "AI tools" bullet, note:
```markdown
**Local tool-calling model**: Qwen3-4B (Hermes `<tool_call>` format, displayed `<think>` reasoning).
Detected by filename containing `qwen3`; routed via `LocalChatBackend.chatQwen3Native` +
`ThinkingStreamSegmenter` + `Qwen3Adapter`. Tool-call parsing for Qwen2.5 / Qwen3 / generic
all live in `ToolCallParser`. FunctionGemma support was removed in v3.1.0.
```

- [ ] **Step 2: Commit**

```
git add CLAUDE.md
git commit -m "📝 docs: note Qwen3-4B as the local tool-calling model"
```

---

## Self-Review (completed by plan author)

**Spec coverage:**
- §1 removal list → Task 1 (FunctionGemma files, `OfflineNlNormalizer`, old docs, stop sequences). ✓
- §1 Hermes in shared parser → Task 2. ✓
- §1 Qwen3 detection + routing → Task 6. ✓
- §2 `ThinkingStreamSegmenter` → Task 4. ✓
- §2 `onThinking` channel → Task 3. ✓
- §2 thinking not in history → Task 6 (`stripThink` + clean assistant turn). ✓
- §3 error/boundary cases (malformed JSON, bare-JSON fallback, EOS unclosed, multiple tool calls, Java-fallback degradation, `/no_think`) → Tasks 2/4/6. ✓
- Display thinking (decision B, buffer-then-render) → Tasks 7/8. ✓
- Validation-first ordering → Tasks 1–8 model-free; Task 9 real GGUF. ✓

**Placeholder scan:** none — every code step contains complete code; every command is exact.

**Type/name consistency:**
- `ThinkingStreamSegmenter.{Segment, Type.THINK, Type.CONTENT, feed, flush, stripThink}` used identically in Tasks 4 & 6.
- `Qwen3Adapter.{augmentSystemPrompt, setThinkingEnabled}` used identically in Tasks 5 & 6.
- `ToolCallParser.{parse, stripToolCalls}` + `HERMES_TOOL_CALL` consistent across Tasks 2 & 6.
- `AiStreamCallback.onThinking(String)` consistent across Tasks 3 & 6 & 8.
- `MarkdownRenderer.renderCollapsible(String, String)` consistent across Tasks 7 & 8.
- `currentAssistantWrapper` field set in Task 8 Step 2, read in Task 8 Step 3. ✓

**Known follow-ups (out of scope, noted for future):**
- Real-time append-incremental thinking rendering (decision §8 option b).
- A UI setting exposing `Qwen3Adapter.setThinkingEnabled`.
- If smoke test shows Qwen3 ignoring the generic prompt, tune `Qwen3Adapter.HERMES_DIRECTIVE`.
