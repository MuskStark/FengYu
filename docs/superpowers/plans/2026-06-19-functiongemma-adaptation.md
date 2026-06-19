# FunctionGemma-270m-it 适配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable a single natural-language instruction in local FunctionGemma mode to drive the full Excel `analyze → configure → execute` chain (and other built-in tools), fully offline.

**Architecture:** The 270M model only does single-turn function calls and cannot chain. So the host owns the multi-step loop: each round the model emits one-or-more calls, the host executes all of them and feeds results back, then re-prompts — mirroring the existing `generateNativeWithToolLoop`. Reliability for the tiny model is improved with `enum` constraints, enriched English descriptions, a hardened parser, a larger context window, and an offline keyword-normalizer so Chinese input maps to the English-trained model.

**Tech Stack:** Java 21, JavaFX 21, JUnit Jupiter 5.10.2, Surefire 3.2.5, IDEA bundled Maven (no system `mvn`).

**Spec:** `docs/superpowers/specs/2026-06-19-functiongemma-adaptation-design.md`

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `SwissKitJ-Api/.../ai/AiToolParam.java` | Modify | Add `enumValues` field + factory overload |
| `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java` | Modify | Emit `enum` in declarations; harden parser |
| `SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java` | Modify | Emit `enum` in JSON schema |
| `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java` | Modify | Multi-round FG loop; `MAX_TOOL_ROUNDS=8`; ctx 8192; remove dead `generateFinalAnswer`; wire normalizer |
| `SwissKit/src/main/java/fan/summer/buildintool/ai/Excel*.java` (6 files) | Modify | Enum on `mode`/`action`; enriched English descriptions + examples |
| `SwissKit/src/main/java/fan/summer/ai/tools/OfflineNlNormalizer.java` | Create | Offline CN→EN keyword normalizer (Phase 1b) |
| `SwissKit/src/main/resources/ai/nl-normalizer.properties` | Create | Normalizer dictionary (Phase 1b) |
| `SwissKit/src/test/java/fan/summer/ai/tools/FunctionGemmaAdapterTest.java` | Create | Pure unit tests: declarations/enum, parse |
| `SwissKit/src/test/java/fan/summer/ai/tools/OfflineNlNormalizerTest.java` | Create | Pure unit tests for the normalizer (Phase 1b) |
| `SwissKit/_test.sh` | Create (throwaway) | Bundled-Maven test runner; **do not commit** |

---

## Test Runner (throwaway)

MCP `execute_terminal_command` is a naive exec (no shell). Per the project's test recipe, run Maven through a script. Create `SwissKit/_test.sh` once (Task 1) and reuse it; it is throwaway — **delete it before committing, or leave it untracked** (it must NOT be committed).

`SwissKit/_test.sh`:
```sh
#!/bin/sh
export JAVA_HOME="$(/usr/libexec/java_home)"
MVN="/Users/phoebej/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1
"$MVN" -o -f SwissKit/pom.xml test-compile || exit 1
if [ -n "$1" ]; then
  "$MVN" -o -f SwissKit/pom.xml surefire:test -Dtest="$1"
else
  "$MVN" -o -f SwissKit/pom.xml surefire:test
fi
```

Run a single test class: `sh /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/_test.sh FunctionGemmaAdapterTest`

> First-ever surefire run needs a one-time ONLINE download of junit-platform jars; the recipe permits that. After that, `-o` offline works.

---

# Phase 1 — Excel English end-to-end chain

## Task 1: `enum` support end-to-end

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiToolParam.java`
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java` (`buildToolDeclarations`)
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java` (`buildJsonSchema`)
- Create: `SwissKit/src/test/java/fan/summer/ai/tools/FunctionGemmaAdapterTest.java`
- Create: `SwissKit/_test.sh`

- [ ] **Step 1: Create the test runner script**

Create `SwissKit/_test.sh` with the content shown in the "Test Runner" section above.

- [ ] **Step 2: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/tools/FunctionGemmaAdapterTest.java`:

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionGemmaAdapterTest {

    private static AiTool tool(String name, String desc, AiToolParam... params) {
        return new AiTool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return desc; }
            @Override public List<AiToolParam> getParameters() { return List.of(params); }
            @Override public AiToolResult execute(Map<String, Object> arguments) { return AiToolResult.success("{}"); }
        };
    }

    @Test
    void buildToolDeclarations_emitsEnumForConstrainedParam() {
        AiTool t = tool("excel_configure", "Configure split",
            AiToolParam.of("mode", "string", "Split mode", true, List.of("BY_SHEET", "BY_COLUMN", "COMPLEX")));
        String decl = new FunctionGemmaAdapter().buildToolDeclarations(List.of(t));
        assertTrue(decl.contains("enum:[BY_SHEET,BY_COLUMN,COMPLEX]"),
            "enum must appear in declaration; was:\n" + decl);
    }

    @Test
    void buildToolDeclarations_omitsEnumWhenAbsent() {
        AiTool t = tool("excel_analyze", "Analyze",
            AiToolParam.of("filePath", "string", "Path", true));
        String decl = new FunctionGemmaAdapter().buildToolDeclarations(List.of(t));
        assertFalse(decl.contains("enum:"), "no enum when param has none; was:\n" + decl);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `sh /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/_test.sh FunctionGemmaAdapterTest`
Expected: COMPILE FAIL — `AiToolParam.of(String,String,String,boolean,List<String>)` does not exist.

- [ ] **Step 4: Add `enumValues` to `AiToolParam`**

Replace the whole record body of `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiToolParam.java` with:

```java
package fan.summer.api.ai;

import java.util.List;

/**
 * Describes a single parameter accepted by an {@link AiTool}.
 *
 * @param name        parameter name (key in the arguments map)
 * @param type        JSON schema type, e.g. {@code "string"}, {@code "integer"}, {@code "boolean"}
 * @param description human-readable description shown to the model
 * @param required    whether this parameter must be provided
 * @param enumValues  optional allowed values emitted as {@code enum:[...]} to the model;
 *                    empty list means unconstrained (never null after compact-constructor normalization)
 * @see AiTool#getParameters()
 */
public record AiToolParam(
    String name,
    String type,
    String description,
    boolean required,
    List<String> enumValues
) {
    public AiToolParam {
        if (enumValues == null) enumValues = List.of();
    }

    public static AiToolParam of(String name, String type, String description, boolean required) {
        return new AiToolParam(name, type, description, required, List.of());
    }

    public static AiToolParam of(String name, String type, String description) {
        return new AiToolParam(name, type, description, true, List.of());
    }

    public static AiToolParam of(String name, String type, String description, boolean required, List<String> enumValues) {
        return new AiToolParam(name, type, description, required, enumValues);
    }
}
```

- [ ] **Step 5: Emit `enum` in `FunctionGemmaAdapter.buildToolDeclarations`**

In `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java`, find inside `buildToolDeclarations`:

```java
                    String fgType = toFgType(p.type());
                    propJoiner.add(p.name() + ":{description:" + p.description() + ",type:" + fgType + "}");
```

Replace with:

```java
                    String fgType = toFgType(p.type());
                    StringBuilder prop = new StringBuilder()
                        .append(p.name()).append(":{description:").append(p.description())
                        .append(",type:").append(fgType);
                    if (!p.enumValues().isEmpty()) {
                        prop.append(",enum:[").append(String.join(",", p.enumValues())).append("]");
                    }
                    propJoiner.add(prop.append("}").toString());
```

- [ ] **Step 6: Emit `enum` in `ToolSchemaBuilder.buildJsonSchema`**

In `SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java`, inside `buildJsonSchema`, find:

```java
            prop.put("description", p.description());
            properties.put(p.name(), prop);
```

Replace with:

```java
            prop.put("description", p.description());
            if (!p.type().endsWith("[]") && !p.enumValues().isEmpty()) {
                prop.put("enum", p.enumValues());
            }
            properties.put(p.name(), prop);
```

- [ ] **Step 7: Install the API jar so SwissKit sees the new field**

Run: `sh /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/_test.sh FunctionGemmaAdapterTest`
(If SwissKit can't resolve the new `enumValues()` accessor, the API jar is stale — run the API install from IDEA Maven: `install -f SwissKitJ-Api/pom.xml -DskipTests`, then re-run the test.)
Expected: PASS — both `buildToolDeclarations_emitsEnum*` tests pass.

- [ ] **Step 8: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiToolParam.java \
        SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java \
        SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java \
        SwissKit/src/test/java/fan/summer/ai/tools/FunctionGemmaAdapterTest.java
git commit -m "✨ feat(ai): add enum support to AiToolParam and tool declarations (FunctionGemma/OpenAI/Anthropic)"
```
(Do **not** commit `SwissKit/_test.sh` — it is untracked throwaway.)

---

## Task 2: Harden `FunctionGemmaAdapter` parser

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java`
- Test: `SwissKit/src/test/java/fan/summer/ai/tools/FunctionGemmaAdapterTest.java`

- [ ] **Step 1: Add failing parse tests**

Append to `FunctionGemmaAdapterTest` (add the `assertEquals` import if not already present):

```java
    @Test
    void parseToolCalls_parsesSingleCallWithDelimitedString() {
        // String values wrapped in the 🪙 delimiter (may contain commas/braces).
        String out = "<start_function_call>call:excel_analyze{filePath:🪙/a,b/c.xlsx🪙}<end_function_call>";
        var calls = new FunctionGemmaAdapter().parseToolCalls(out);
        assertEquals(1, calls.size());
        assertEquals("excel_analyze", calls.get(0).name());
        assertEquals("/a,b/c.xlsx", calls.get(0).arguments().get("filePath"));
    }

    @Test
    void parseToolCalls_parsesMultipleCalls() {
        String out = "<start_function_call>call:t1{k:🪙v1🪙}<end_function_call>"
                   + "<start_function_call>call:t2{k:🪙v2🪙}<end_function_call>";
        var calls = new FunctionGemmaAdapter().parseToolCalls(out);
        assertEquals(2, calls.size());
        assertEquals("t1", calls.get(0).name());
        assertEquals("t2", calls.get(1).name());
    }

    @Test
    void parseToolCalls_toleratesBraceInsideDelimitedValue() {
        // A 🪙-wrapped value containing '}' must not terminate the arg block early.
        String out = "<start_function_call>call:t{k:🪙a}b🪙}<end_function_call>";
        var calls = new FunctionGemmaAdapter().parseToolCalls(out);
        assertEquals(1, calls.size(), "must still match with a brace inside the value");
        assertEquals("a}b", calls.get(0).arguments().get("k"));
    }

    @Test
    void parseToolCalls_toleratesMissingDelimiters() {
        // Bare string value (model forgot 🪙) — still extracted as a string.
        String out = "<start_function_call>call:t{k:plain}<end_function_call>";
        var calls = new FunctionGemmaAdapter().parseToolCalls(out);
        assertEquals(1, calls.size());
        assertEquals("plain", calls.get(0).arguments().get("k"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sh /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/_test.sh FunctionGemmaAdapterTest`
Expected: `parseToolCalls_parsesMultipleCalls` and `parseToolCalls_toleratesBraceInsideDelimitedValue` FAIL (current regex `[^}]*` stops at the first `}`).

- [ ] **Step 3: Loosen the parse regex**

In `FunctionGemmaAdapter.java`, replace:

```java
    private static final Pattern FG_CALL = Pattern.compile(
        "<start_function_call>call:(\\w+)\\{([^}]*)}<end_function_call>"
    );
```

with:

```java
    private static final Pattern FG_CALL = Pattern.compile(
        "<start_function_call>call:(\\w+)\\{([\\s\\S]*?)\\}<end_function_call>"
    );
```

The `[\s\S]*?` non-greedily matches up to `}<end_function_call>`, so a `}` inside a 🪙-delimited value no longer truncates the block. `splitArgPairs` already respects 🪙 delimiters, so commas/braces inside values survive.

- [ ] **Step 4: Run tests to verify they pass**

Run: `sh /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/_test.sh FunctionGemmaAdapterTest`
Expected: PASS — all FunctionGemmaAdapterTest cases green.

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java \
        SwissKit/src/test/java/fan/summer/ai/tools/FunctionGemmaAdapterTest.java
git commit -m "🐛 fix(ai): harden FunctionGemma call parser (braces in values, multi-call)"
```

---

## Task 3: FunctionGemma multi-round loop in `AiServiceImpl`

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java`

> **Verification note:** This is integration code (native `NativeWorkerClient` + JavaFX `Platform.runLater`), so it is not unit-testable without bootstrapping the native worker and the FX toolkit — consistent with the existing `generateNativeWithToolLoop`, which has no unit test either. Correctness rests on the parser/declaration logic covered by Tasks 1–2, plus the manual smoke step. Mirror the structure of the existing native loop exactly.

- [ ] **Step 1: Bump `MAX_TOOL_ROUNDS` to 8**

In `AiServiceImpl.java`, change `private static final int MAX_TOOL_ROUNDS = 5;` to `private static final int MAX_TOOL_ROUNDS = 8;`.

- [ ] **Step 2: Bump context window to 8192**

In `loadModel`, change `.ctxLength(4096)` to `.ctxLength(8192)`.

- [ ] **Step 3: Replace `chatFunctionGemmaNative` with the loop driver**

In `AiServiceImpl.java`, replace the whole `chatFunctionGemmaNative` method with:

```java
    private void chatFunctionGemmaNative(List<AiChatMessage> history, float temperature,
                                          float topP, int maxTokens, AiStreamCallback callback) {
        Thread.ofVirtual().start(() -> {
            try {
                OfflineNlNormalizer.normalizeLatestUser(history);   // offline CN→EN keywords (Phase 1b)
                String toolDecls = functionGemmaAdapter.buildToolDeclarations(AiServiceProvider.getTools());
                String prompt = functionGemmaAdapter.buildPrompt(history, toolDecls);
                generateFunctionGemmaLoop(prompt, toolDecls, temperature, topP, maxTokens,
                                          history, callback, 0);
            } catch (Exception e) {
                log.error("FunctionGemma generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }
```

- [ ] **Step 4: Add the recursive loop + remove dead `generateFinalAnswer`**

In `AiServiceImpl.java`, delete the existing `generateFinalAnswer` method entirely, and replace it with:

```java
    /**
     * FunctionGemma multi-round loop: each round the model emits one-or-more
     * single-turn calls (its strength); the host executes ALL of them, feeds the
     * results back, and re-prompts — until a round produces no call (final answer)
     * or {@link #MAX_TOOL_ROUNDS} is reached. Intermediate tool-call rounds do not
     * stream raw {@code call:…{…}} tokens; only the final answer is delivered.
     */
    private void generateFunctionGemmaLoop(String prompt, String toolDecls, float temperature,
                                            float topP, int maxTokens, List<AiChatMessage> history,
                                            AiStreamCallback callback, int round) {
        if (round >= MAX_TOOL_ROUNDS || workerClient == null || !workerClient.isAlive()) {
            log.warn("FunctionGemma loop stopped at round {} (max {})", round, MAX_TOOL_ROUNDS);
            Platform.runLater(() -> callback.onComplete("", 0, 0));
            return;
        }

        GenerateParams genParams = new GenerateParams()
            .temperature(temperature).topP(topP).maxTokens(maxTokens);

        workerClient.generate(prompt, genParams, new GenerateCallback() {
            @Override
            public boolean onToken(String tokenText) {
                // Tool-call rounds are discarded (no raw call:… tokens to the UI);
                // the final-answer round forwards the cleaned text in onDone.
                return true;
            }

            @Override
            public void onDone(String fullText, int tokenCount, double tokPerSec) {
                List<AiToolCall> toolCalls = functionGemmaAdapter.parseToolCalls(fullText);
                if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                    // Record the assistant turn (text stripped of call noise), then execute every call.
                    history.add(AiChatMessage.assistantWithTools(
                        functionGemmaAdapter.stripToolCalls(fullText), toolCalls));
                    for (AiToolCall tc : toolCalls) {
                        Platform.runLater(() -> callback.onToolCall(tc));
                        AiToolResult result = ToolExecutor.execute(tc.name(), tc.arguments());
                        Platform.runLater(() -> callback.onToolResult(tc.id(), result));
                        history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));
                    }
                    String newPrompt = functionGemmaAdapter.buildPrompt(history, toolDecls);
                    generateFunctionGemmaLoop(newPrompt, toolDecls, temperature, topP, maxTokens,
                                              history, callback, round + 1);
                } else {
                    String clean = functionGemmaAdapter.stripToolCalls(fullText);
                    final String answer = clean;
                    Platform.runLater(() -> {
                        if (!answer.isEmpty()) callback.onToken(answer);
                        callback.onComplete(answer, tokenCount, tokPerSec);
                    });
                }
            }

            @Override
            public void onError(String message) {
                Platform.runLater(() -> callback.onError(new RuntimeException(message)));
            }
        });
    }
```

- [ ] **Step 5: Confirm it compiles**

Run: `sh /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/_test.sh FunctionGemmaAdapterTest`
(The script runs `test-compile` first; a clean compile of `AiServiceImpl` is the gate. Existing tests still pass.)
Expected: COMPILE OK; FunctionGemmaAdapterTest PASS.

- [ ] **Step 6: Manual smoke (requires the real model)**

In the running app: Settings → AI mode = local, load `functiongemma-270m-it` GGUF. In the AI box type an English instruction, e.g.:
`Analyze /abs/path/data.xlsx, then split sheet Sheet1 by the column named Region into /abs/path/out`
Expected: the UI shows `onToolCall`/`onToolResult` for `excel_analyze` → `excel_configure` → `excel_execute` across successive rounds, then a final summary. (If the model mis-selects a tool, that is the ~60% base-accuracy limit, not a code bug — retry or rephrase.)

- [ ] **Step 7: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java
git commit -m "✨ feat(ai): FunctionGemma host-driven multi-round tool loop (analyze→configure→execute)"
```

> Note: `OfflineNlNormalizer` is referenced in Step 3 but created in Task 5 (Phase 1b). To keep Phase 1 compiling standalone, either (a) do Phase 1b (Task 5) before committing Task 3, or (b) temporarily comment out the `normalizeLatestUser` line in Step 3 and uncomment it in Task 5. Recommended: do Task 5 right after Task 3 (they pair naturally), then commit both.

---

## Task 4: Excel tools — enum + enriched descriptions

**Files:**
- Modify: the six `SwissKit/src/main/java/fan/summer/buildintool/ai/Excel*.java`

> Verification: compile + a regression run of `FunctionGemmaAdapterTest`. The enum now lives on the real tools; descriptions are richer. No new unit test (descriptions are strings).

- [ ] **Step 1: `ExcelAnalyzeTool` — enriched description**

Replace its `getDescription()` with:

```java
    @Override public String getDescription() {
        return "Analyze (read) an Excel .xlsx/.xls file and return every sheet name, row counts, and column headers. "
               + "This is the FIRST step before configuring or splitting a file. "
               + "Example: excel_analyze{filePath:\"/path/file.xlsx\"}.";
    }
```

(`getParameters()` unchanged.)

- [ ] **Step 2: `ExcelConfigureTool` — enum on `mode` + description**

Replace `getDescription()` with:

```java
    @Override public String getDescription() {
        return "Configure how to split the Excel file. Call AFTER excel_analyze. "
               + "Modes: BY_SHEET (export each sheet as its own file), "
               + "BY_COLUMN (split one sheet into multiple files grouped by the unique values of a column), "
               + "COMPLEX (database-backed multi-config). "
               + "Examples: excel_configure{mode:\"BY_COLUMN\", splitSheet:\"Sheet1\", splitColumn:\"部门\"}; "
               + "excel_configure{mode:\"BY_SHEET\"}.";
    }
```

Replace `getParameters()` with:

```java
    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("mode", "string", "Split mode", true,
                List.of("BY_SHEET", "BY_COLUMN", "COMPLEX")),
            AiToolParam.of("sheets", "string[]", "Sheet names to export (BY_SHEET mode)", false),
            AiToolParam.of("splitSheet", "string", "Sheet name to split on (BY_COLUMN mode)", false),
            AiToolParam.of("splitColumn", "string", "Column header name to split by (BY_COLUMN mode)", false),
            AiToolParam.of("taskId", "string", "Complex split task ID from DB (COMPLEX mode)", false)
        );
    }
```

- [ ] **Step 3: `ExcelExecuteTool` — enriched description**

Replace `getDescription()` with:

```java
    @Override public String getDescription() {
        return "Execute (run) the configured Excel split and write the output files. "
               + "Call AFTER excel_analyze and excel_configure. "
               + "Example: excel_execute{outputDir:\"/out\", filePrefix:\"result_\"}.";
    }
```

- [ ] **Step 4: `ExcelQueryTool` — enriched description**

Replace `getDescription()` with:

```java
    @Override public String getDescription() {
        return "Query (read) the current Excel split configuration state: source file, mode, selected sheets/columns, output directory. "
               + "Call to inspect progress. No arguments. Example: excel_query{}.";
    }
```

- [ ] **Step 5: `ExcelComplexConfigTool` — enum on `action` + description**

Replace `getDescription()` with:

```java
    @Override public String getDescription() {
        return "Manage database-backed complex split configs used by COMPLEX mode. "
               + "Actions: 'add' (insert one config row: sheet + header row + split column), "
               + "'list' (show all rows for a taskId), 'clear' (delete all rows for a taskId). "
               + "'add' returns a taskId to pass to excel_configure mode=COMPLEX. "
               + "headerIndex=-1 and columnIndex=-1 together means copy the whole sheet to every output file. "
               + "Example: excel_complex_config{action:\"add\", sheetName:\"Sheet1\", headerIndex:1, columnIndex:2}.";
    }
```

Replace `getParameters()` with:

```java
    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("action", "string", "Action: add, list, or clear", true,
                List.of("add", "list", "clear")),
            AiToolParam.of("taskId", "string", "Task ID (auto-generated on 'add' if omitted)", false),
            AiToolParam.of("sheetName", "string", "Sheet name (required for 'add')", false),
            AiToolParam.of("headerIndex", "integer", "1-based header row; -1 = no header / copy all (required for 'add')", false),
            AiToolParam.of("columnIndex", "integer", "1-based column to split by; -1 = copy to all outputs (required for 'add')", false)
        );
    }
```

- [ ] **Step 6: Compile + regression**

Run: `sh /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/_test.sh FunctionGemmaAdapterTest`
Expected: COMPILE OK; tests still PASS.

- [ ] **Step 7: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelAnalyzeTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelConfigureTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelExecuteTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelQueryTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelComplexConfigTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelCancelTool.java
git commit -m "📝 feat(ai): enrich Excel tool descriptions + enum on mode/action for small-model reliability"
```

---

# Phase 1b — Offline Chinese keyword normalization

## Task 5: `OfflineNlNormalizer` + dictionary

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/OfflineNlNormalizer.java`
- Create: `SwissKit/src/main/resources/ai/nl-normalizer.properties`
- Create: `SwissKit/src/test/java/fan/summer/ai/tools/OfflineNlNormalizerTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/tools/OfflineNlNormalizerTest.java`:

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.AiChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineNlNormalizerTest {

    @Test
    void normalize_mapsActionKeywordsToEnglish() {
        String out = OfflineNlNormalizer.normalize("请拆分这个文件");
        assertTrue(out.contains("split"), "action keyword must map to English; was: " + out);
    }

    @Test
    void normalize_preservesIdentifiersVerbatim() {
        String out = OfflineNlNormalizer.normalize("分析 /abs/data.xlsx 并按 Region 列拆分");
        assertTrue(out.contains("/abs/data.xlsx"), "file path must pass through; was: " + out);
        assertTrue(out.contains("Region"), "column name must pass through; was: " + out);
    }

    @Test
    void normalize_passesThroughUncoveredText() {
        assertEquals("plain english already", OfflineNlNormalizer.normalize("plain english already"));
    }

    @Test
    void normalizeLatestUser_rewritesOnlyLastUserMessage() {
        List<AiChatMessage> h = new ArrayList<>();
        h.add(AiChatMessage.user("拆分 a"));
        h.add(AiChatMessage.assistant("ok"));
        h.add(AiChatMessage.user("分析 b"));
        OfflineNlNormalizer.normalizeLatestUser(h);
        assertEquals("拆分 a", h.get(0).content());
        assertTrue(h.get(2).content().contains("analyze"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sh /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/_test.sh OfflineNlNormalizerTest`
Expected: COMPILE FAIL — `OfflineNlNormalizer` does not exist.

- [ ] **Step 3: Create the dictionary resource**

Create `SwissKit/src/main/resources/ai/nl-normalizer.properties`:

```properties
# Offline CN→EN keyword normalizer for the English-trained FunctionGemma model.
# Simple substring replacement: action verbs and domain words → English so the
# tiny model can select the right tool/param. Identifiers (paths, sheet/column
# names) are intentionally NOT listed here and pass through verbatim.
拆分= split 
分析= analyze 
合并= merge 
转换= convert 
查询= query 
取消= cancel 
按列= by column 
每个sheet= each sheet 
每个表= each sheet 
表=sheet
列=column
目录=directory
```

- [ ] **Step 4: Create `OfflineNlNormalizer`**

Create `SwissKit/src/main/java/fan/summer/ai/tools/OfflineNlNormalizer.java`:

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Offline CN→EN keyword normalizer for the English-trained FunctionGemma model.
 *
 * <p>Loads a local {@code /ai/nl-normalizer.properties} dictionary at class-load time
 * and replaces known Chinese action/domain keywords with their English equivalents
 * via plain substring replacement. File paths, sheet names and column names are
 * identifiers and are intentionally not in the dictionary, so they pass through.
 *
 * <p>This is a <b>best-effort heuristic</b>: coverage is limited to the dictionary,
 * and substring replacement can occasionally over-match inside larger words. It is
 * fully offline and adds no second model, matching the lightweight 270M deployment.
 */
public final class OfflineNlNormalizer {

    private static final PluginLogger log = LoggerFactory.getLogger(OfflineNlNormalizer.class);
    private static final Map<String, String> DICT = load();

    private OfflineNlNormalizer() {}

    private static Map<String, String> load() {
        Map<String, String> m = new LinkedHashMap<>();
        Properties p = new Properties();
        try (InputStream in = OfflineNlNormalizer.class.getResourceAsStream("/ai/nl-normalizer.properties")) {
            if (in != null) {
                p.load(in);
                for (String k : p.stringPropertyNames()) {
                    m.put(k, p.getProperty(k));
                }
            } else {
                log.warn("nl-normalizer.properties not found on classpath; normalizer is a no-op");
            }
        } catch (Exception e) {
            log.warn("Failed to load nl-normalizer.properties: {}", e.getMessage());
        }
        return m;
    }

    /** Replace known Chinese keywords with their English equivalents; leave the rest verbatim. */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) return text;
        String out = text;
        for (Map.Entry<String, String> e : DICT.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Normalize the most recent USER message in-place (other turns untouched). */
    public static void normalizeLatestUser(List<AiChatMessage> history) {
        if (history == null || history.isEmpty()) return;
        for (int i = history.size() - 1; i >= 0; i--) {
            AiChatMessage m = history.get(i);
            if (m.role() == AiChatMessage.Role.USER) {
                String normalized = normalize(m.content());
                if (!normalized.equals(m.content())) {
                    history.set(i, AiChatMessage.user(normalized));
                }
                return;
            }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `sh /Users/phoebej/Develop/Java/SwissKitJ/SwissKit/_test.sh OfflineNlNormalizerTest`
Expected: PASS — all four cases green.

- [ ] **Step 6: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/OfflineNlNormalizer.java \
        SwissKit/src/main/resources/ai/nl-normalizer.properties \
        SwissKit/src/test/java/fan/summer/ai/tools/OfflineNlNormalizerTest.java
git commit -m "✨ feat(ai): offline CN→EN keyword normalizer for FunctionGemma local mode"
```

(Task 3's `AiServiceImpl` already calls `OfflineNlNormalizer.normalizeLatestUser(history)`, so Phase 1b is now wired. If you deferred it per the Task 3 note, uncomment that line here and recompile.)

---

## Final cleanup

- [ ] **Delete the throwaway runner**: `rm SwissKit/_test.sh` (ensure it is not committed).

- [ ] **Full test pass**: run all SwissKit tests once via the IDEA Maven tool window (Surefire) to confirm no regressions across the module.

---

## Self-Review (spec coverage)

Spec section → task:

- 5.1 (FG multi-round loop, parallel exec, suppress intermediate streaming, delete single-step `generateFinalAnswer`) → **Task 3** (Steps 3–4). Parallel exec: the `for (AiToolCall tc : toolCalls)` loop executes ALL parsed calls. Streaming suppression: tool rounds discard tokens; final round forwards `clean`. ✓
- 5.1 `MAX_TOOL_ROUNDS` → 8 → **Task 3 Step 1**. ✓
- 5.2 enum in declarations + parse hardening + token alignment → **Task 1 Step 5** (enum) + **Task 2** (regex). Token alignment: unchanged (already matches official example); covered by `buildToolDeclarations`/`parseToolCalls` tests. ✓
- 5.3 `AiToolParam.enumValues` + schema enum → **Task 1 Steps 4 & 6**. ✓
- 5.4 Excel descriptions + enum on mode/action → **Task 4**. ✓
- 5.5 `OfflineNlNormalizer` + dictionary + wiring → **Task 5** (component) + **Task 3 Step 3** (wiring). ✓
- 5.6 ctx 8192 → **Task 3 Step 2**. ✓
- §6 error handling (tool errors fed back; max-rounds surface) → **Task 3**: errors flow back as `toolResult(result.output())` (the tool's own error string) and the model self-corrects next round; max-rounds → `onComplete("")`. (Retry-on-parse-fail from §6 is intentionally **not** implemented in this plan — the non-greedy regex + final-round `stripToolCalls` cover the common cases; add later if smoke testing shows it's needed. Flagged here so it's a conscious omission, not a gap.) ⚠ noted
- §7 tests (adapter parse/declare/prompt, normalizer, loop mock, e2e) → Tasks 1, 2, 5 cover adapter + normalizer (pure). Loop mock + e2e are manual smoke (Task 3 Step 6) — the loop is integration code with native+FX deps, untestable without bootstrapping both, matching existing `generateNativeWithToolLoop`. ⚠ noted

**Type/signature consistency check:**
- `AiToolParam.of(...,boolean,List<String>)` — defined Task 1 Step 4, used Task 4 Steps 2 & 5. ✓
- `p.enumValues()` accessor — record-generated, used Task 1 Steps 5–6 and Task 4. ✓
- `functionGemmaAdapter.parseToolCalls` / `stripToolCalls` / `buildToolDeclarations` / `buildPrompt` — all existing methods; used unchanged. ✓
- `OfflineNlNormalizer.normalizeLatestUser(history)` — defined Task 5 Step 4, called Task 3 Step 3. ✓
- `AiToolResult`, `AiChatMessage.assistantWithTools`/`toolResult`/`user`, `AiToolCall.name()/id()/arguments()` — match contracts verified in `SwissKitJ-Api`. ✓

No placeholders. Conscious omissions (parse-fail retry, loop unit test) are flagged above with rationale.
