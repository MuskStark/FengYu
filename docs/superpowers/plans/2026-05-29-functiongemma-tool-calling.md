# FunctionGemma Tool Calling Optimization — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable reliable tool calling with FunctionGemma's native protocol in local model mode.

**Architecture:** A `FunctionGemmaAdapter` class encapsulates all FunctionGemma-specific formatting/parsing. `AiServiceImpl` detects the model by filename and branches to the adapter for prompt building, tool call parsing, and result formatting — single-turn only.

**Tech Stack:** Java 21, JavaFX, GGUF model loading, regex parsing

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java` | All FunctionGemma protocol logic: declarations, parsing, result formatting, prompt building |
| Modify | `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java` | Detection flag, adapter branching, single-round tool loop |
| Modify | `SwissKit/src/main/java/fan/summer/ai/inference/StopDetector.java` | Add 4 FunctionGemma stop sequences |
| Modify | `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` | Accept `*.ggufz` in file chooser |

No test infrastructure exists in this project; verification is manual through the running app.

---

### Task 1: Create FunctionGemmaAdapter — tool call parsing

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java`

- [ ] **Step 1: Create the adapter class with tool call parsing**

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolCall;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates FunctionGemma's native tool calling protocol.
 * <p>
 * FunctionGemma uses a structured format with control tokens
 * ({@code <start_function_call>}, {@code <end_function_call>}, etc.)
 * instead of JSON. This adapter handles:
 * <ul>
 *   <li>Formatting tool declarations for the system prompt</li>
 *   <li>Parsing tool calls from model output</li>
 *   <li>Formatting tool results for the response turn</li>
 *   <li>Building the complete prompt with {@code developer} role</li>
 * </ul>
 *
 * @see <a href="https://ai.google.dev/gemma/docs/functiongemma/formatting-and-best-practices">FunctionGemma formatting guide</a>
 */
public class FunctionGemmaAdapter {

    private static final PluginLogger log = LoggerFactory.getLogger(FunctionGemmaAdapter.class);

    // FunctionGemma string delimiter token — wraps all string values
    private static final String STRING_DELIM = "🪙"; // 🪙

    // ── Tool call parsing ──────────────────────────────────────

    private static final Pattern FG_CALL = Pattern.compile(
        "<start_function_call>call:(\\w+)\\{([^}]*)}<end_function_call>"
    );

    /**
     * Parse all FunctionGemma tool calls from model output.
     *
     * @param text raw model output
     * @return list of parsed tool calls; empty if none found
     */
    public List<AiToolCall> parseToolCalls(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<AiToolCall> calls = new ArrayList<>();
        Matcher m = FG_CALL.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String argsRaw = m.group(2);
            Map<String, Object> args = parseArgs(argsRaw, name);
            calls.add(AiToolCall.of(name, args));
            log.info("Parsed FunctionGemma tool call: name={}, args={}", name, args);
        }

        log.debug("FunctionGemma parseToolCalls: found {} calls", calls.size());
        return calls;
    }

    /**
     * Quick check whether the text contains a FunctionGemma tool call pattern.
     */
    public boolean containsToolCall(String text) {
        return text != null && text.contains("<start_function_call>");
    }

    /**
     * Parse FunctionGemma argument string: {@code key:value,key:value}.
     * String values are wrapped in {@code 🪙} delimiters.
     */
    private Map<String, Object> parseArgs(String argsRaw, String toolName) {
        if (argsRaw == null || argsRaw.isBlank()) return Map.of();

        Map<String, Object> args = new LinkedHashMap<>();
        Map<String, AiToolParam> paramTypes = getParamTypes(toolName);

        // Split on commas, but respect 🪙-delimited strings
        List<String> pairs = splitArgPairs(argsRaw);
        for (String pair : pairs) {
            int colonIdx = pair.indexOf(':');
            if (colonIdx <= 0) continue;

            String key = pair.substring(0, colonIdx).trim();
            String valStr = pair.substring(colonIdx + 1).trim();

            // Remove 🪙 delimiters from string values
            valStr = valStr.replace(STRING_DELIM, "");

            // Type conversion based on tool parameter schema
            AiToolParam param = paramTypes.get(key);
            Object value = convertValue(valStr, param);
            args.put(key, value);
        }
        return args;
    }

    /**
     * Split argument string on commas, respecting 🪙-delimited strings
     * that may contain commas.
     */
    private List<String> splitArgPairs(String argsRaw) {
        List<String> pairs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < argsRaw.length(); i++) {
            char c = argsRaw.charAt(i);

            // Check for string delimiter (🪙 is a surrogate pair)
            if (i + 1 < argsRaw.length()) {
                String twoChars = argsRaw.substring(i, Math.min(i + 2, argsRaw.length()));
                if (twoChars.equals(STRING_DELIM)) {
                    inString = !inString;
                    current.append(twoChars);
                    i++; // skip second char of surrogate pair
                    continue;
                }
            }

            if (c == ',' && !inString) {
                pairs.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            pairs.add(current.toString().trim());
        }
        return pairs;
    }

    /**
     * Get parameter type map for a specific tool from the registered tools.
     */
    private Map<String, AiToolParam> getParamTypes(String toolName) {
        AiTool tool = AiServiceProvider.getTool(toolName);
        if (tool == null) return Map.of();
        Map<String, AiToolParam> types = new LinkedHashMap<>();
        for (AiToolParam p : tool.getParameters()) {
            types.put(p.name(), p);
        }
        return types;
    }

    /**
     * Convert a string value to the appropriate Java type based on parameter schema.
     */
    private Object convertValue(String valStr, AiToolParam param) {
        if (param == null) return valStr;
        String type = param.type().toLowerCase();
        return switch (type) {
            case "integer", "number" -> {
                try { yield Integer.parseInt(valStr); }
                catch (NumberFormatException e) { yield valStr; }
            }
            case "float", "double" -> {
                try { yield Double.parseDouble(valStr); }
                catch (NumberFormatException e) { yield valStr; }
            }
            case "boolean" -> Boolean.parseBoolean(valStr);
            default -> valStr;
        };
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java
git commit -m "✨ feat(ai): add FunctionGemmaAdapter with tool call parsing"
```

---

### Task 2: Extend FunctionGemmaAdapter — tool declarations

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java`

- [ ] **Step 1: Add tool declaration formatting and result formatting methods**

Append these methods inside `FunctionGemmaAdapter`, after the `convertValue` method:

```java
    // ── Tool declaration formatting ────────────────────────────

    private static final String SYSTEM_TRIGGER =
        "You are a model that can do function calling with the following functions\n";

    /**
     * Build FunctionGemma-format tool declarations for the developer prompt.
     *
     * <p>Format: {@code <start_function_declaration>declaration:name{description:...,parameters:{...}}<end_function_declaration>}</p>
     */
    public String buildToolDeclarations(List<AiTool> tools) {
        if (tools.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append(SYSTEM_TRIGGER);

        for (AiTool tool : tools) {
            sb.append("<start_function_declaration>declaration:")
              .append(tool.getName())
              .append("{description:").append(tool.getDescription())
              .append(",parameters:");

            List<AiToolParam> params = tool.getParameters();
            if (!params.isEmpty()) {
                sb.append("{properties:{");
                StringJoiner propJoiner = new StringJoiner(",");
                List<String> required = new ArrayList<>();
                for (AiToolParam p : params) {
                    String fgType = toFgType(p.type());
                    propJoiner.add(p.name() + ":{description:" + p.description() + ",type:" + fgType + "}");
                    if (p.required()) required.add(p.name());
                }
                sb.append(propJoiner);
                sb.append("}");
                if (!required.isEmpty()) {
                    sb.append(",required:[").append(String.join(",", required)).append("]");
                }
                sb.append(",type:OBJECT");
            } else {
                sb.append("{type:OBJECT,properties:{}}");
            }
            sb.append("}}<end_function_declaration>");
        }

        log.debug("FunctionGemma tool declarations built: {} tools, {} chars", tools.size(), sb.length());
        return sb.toString();
    }

    /**
     * Convert Java type string to FunctionGemma type enum.
     */
    private String toFgType(String javaType) {
        if (javaType == null) return "STRING";
        return switch (javaType.toLowerCase()) {
            case "integer" -> "INTEGER";
            case "number", "float", "double" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            case "string" -> "STRING";
            default -> {
                if (javaType.endsWith("[]")) yield "ARRAY";
                yield "STRING";
            }
        };
    }

    // ── Tool result formatting ─────────────────────────────────

    /**
     * Format a tool execution result as a FunctionGemma function response.
     *
     * <p>Format: {@code <start_function_response>response:name{result:value}<end_function_response>}</p>
     */
    public String formatToolResponse(String toolName, String result) {
        return "<start_function_response>response:" + toolName +
               "{result:" + escapeForFG(result) + "}<end_function_response>";
    }

    /**
     * Escape text for inclusion in FunctionGemma structured blocks.
     * Wraps string values in 🪙 delimiters.
     */
    private String escapeForFG(String text) {
        if (text == null) return STRING_DELIM + STRING_DELIM;
        return STRING_DELIM + text + STRING_DELIM;
    }

    // ── Prompt building ────────────────────────────────────────

    /**
     * Build a complete FunctionGemma prompt with developer (system/tool defs),
     * user, model, and tool response turns.
     *
     * @param history chat messages
     * @param toolDeclarations pre-built tool declaration string
     * @return formatted prompt ready for model inference
     */
    public String buildPrompt(List<fan.summer.api.ai.AiChatMessage> history,
                              String toolDeclarations) {
        var sb = new StringBuilder();

        // Developer turn: system trigger + tool declarations
        if (toolDeclarations != null && !toolDeclarations.isEmpty()) {
            sb.append("<start_of_turn>developer\n")
              .append(toolDeclarations)
              .append("<end_of_turn>\n");
        }

        // Chat history
        for (var msg : history) {
            String role = switch (msg.role()) {
                case ASSISTANT -> "model";
                case SYSTEM -> "developer";
                case TOOL -> "developer";  // tool results use developer role
                default -> "user";
            };

            sb.append("<start_of_turn>").append(role).append("\n");

            // For tool results, use FunctionGemma response format
            if (msg.role() == fan.summer.api.ai.AiChatMessage.Role.TOOL) {
                String toolName = msg.toolName() != null ? msg.toolName() : "unknown";
                sb.append(formatToolResponse(toolName, msg.content()));
            } else if (msg.role() == fan.summer.api.ai.AiChatMessage.Role.ASSISTANT && msg.hasToolCalls()) {
                // Model turn with tool calls — emit the native format
                for (var tc : msg.toolCalls()) {
                    sb.append("<start_function_call>call:")
                      .append(tc.name()).append("{");
                    StringJoiner j = new StringJoiner(",");
                    for (var entry : tc.arguments().entrySet()) {
                        j.add(entry.getKey() + ":" + formatArgValue(entry.getValue()));
                    }
                    sb.append(j);
                    sb.append("}<end_function_call>");
                }
                // Also append any text content
                if (msg.content() != null && !msg.content().isEmpty()) {
                    sb.append(msg.content());
                }
            } else {
                sb.append(msg.content() == null ? "" : msg.content());
            }

            sb.append("<end_of_turn>\n");
        }

        // Start model turn for generation
        sb.append("<start_of_turn>model\n");
        return sb.toString();
    }

    /**
     * Format a single argument value for FunctionGemma call syntax.
     * Strings get 🪙 delimiters; others are left as-is.
     */
    private String formatArgValue(Object value) {
        if (value instanceof String s) {
            return STRING_DELIM + s + STRING_DELIM;
        }
        return String.valueOf(value);
    }

    /**
     * Strip FunctionGemma control tokens from model output to get clean text.
     */
    public String stripToolCalls(String text) {
        if (text == null) return "";
        // Remove function call blocks
        String result = text.replaceAll("<start_function_call>.*?<end_function_call>", "");
        // Remove any stray control tokens
        result = result.replace("<start_function_declaration>", "")
                       .replace("<end_function_declaration>", "")
                       .replace("<start_function_response>", "")
                       .replace("<end_function_response>", "");
        return result.trim();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java
git commit -m "✨ feat(ai): add FunctionGemma tool declarations, result formatting, and prompt building"
```

---

### Task 3: Add FunctionGemma stop sequences to StopDetector

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/inference/StopDetector.java:26-46`

- [ ] **Step 1: Add 4 FunctionGemma stop sequences**

In `StopDetector.java`, find the `STOP_SEQUENCES` list (around line 26) and add the 4 FunctionGemma entries. Insert after the `"Users/"` line and before the closing `);`:

```java
    private static final List<String> STOP_SEQUENCES = List.of(
        // ChatML / Qwen
        "<|im_end|>",
        "<|im_start|>",
        // Llama 3
        "<|eot_id|>",
        "<|start_header_id|>",
        "<|end_header_id|>",
        "<|end_of_text|>",
        // GPT-2 / GPT-Neo / some Qwen variants
        "",
        // Mistral / fallback
        "</s>",
        // Gemma
        "<end_of_turn>",
        "<start_of_turn>",
        // FunctionGemma
        "<end_function_call>",
        "<start_function_call>",
        "<end_function_response>",
        "<start_function_response>",
        // Generic role tags some fine-tunes emit
        "uninstall",
        "find ",
        "cd ~"
    );
```

Note: The existing file has `" uninstall"`, `"find "`, `"cd ~"` as the last 3 entries (they may appear as single-char strings in the file). Preserve the existing entries exactly and add only the 4 new FunctionGemma lines before the generic role tags section.

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/inference/StopDetector.java
git commit -m "✨ feat(ai): add FunctionGemma stop sequences to StopDetector"
```

---

### Task 4: Integrate FunctionGemmaAdapter into AiServiceImpl

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java`

This is the largest task. Changes are at specific, well-defined locations.

- [ ] **Step 1: Add import and fields**

At the top of `AiServiceImpl.java`, add the import after the existing tool imports (around line 11):

```java
import fan.summer.ai.tools.FunctionGemmaAdapter;
```

Add two new instance fields after the `loadedModelPath` field (around line 53):

```java
    private volatile String loadedModelPath;
    private FunctionGemmaAdapter functionGemmaAdapter;  // NEW
    private boolean isFunctionGemma;                     // NEW
```

- [ ] **Step 2: Add detection method**

Add this method after the constructor (around line 74):

```java
    /**
     * Detect if the loaded model is FunctionGemma by filename.
     * Only applies in local model mode (native or Java backend).
     */
    private void detectModelType(String modelPath) {
        isFunctionGemma = false;
        functionGemmaAdapter = null;
        if (backend != Backend.NATIVE && backend != Backend.JAVA) return;
        String name = java.nio.file.Path.of(modelPath).getFileName().toString().toLowerCase();
        isFunctionGemma = name.contains("functiongemma");
        if (isFunctionGemma) {
            functionGemmaAdapter = new FunctionGemmaAdapter();
            log.info("FunctionGemma detected — using native tool calling protocol");
        }
    }
```

- [ ] **Step 3: Hook detection into loadModel()**

In the `loadModel()` method, add the detection call after the `loadedModelPath` assignment (around line 87). Insert `detectModelType(modelPath.toString());` right after `loadedModelPath = modelPath.toString();`:

```java
    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        try {
            loadedModelPath = modelPath.toString();
            detectModelType(modelPath.toString());  // NEW
            log.info("Loading AI model [{}]: {}", backend, modelPath);
            // ... rest unchanged ...
```

- [ ] **Step 4: Hook cleanup into unloadModel()**

In `unloadModel()`, add cleanup before `loadedModelPath = null;` (around line 131):

```java
    @Override
    public void unloadModel() {
        if (backend == Backend.NATIVE && nativeContext != null) {
            nativeContext.close();
            nativeContext = null;
            nativeChatTemplate = null;
        } else if (javaRunner != null) {
            javaRunner.unload();
        }
        isFunctionGemma = false;           // NEW
        functionGemmaAdapter = null;        // NEW
        loadedModelPath = null;
    }
```

- [ ] **Step 5: Add FunctionGemma-specific system prompt**

Modify `buildSystemPrompt()` (around line 378) to short-circuit for FunctionGemma:

```java
    private String buildSystemPrompt() {
        // FunctionGemma: adapter handles tool declarations in the prompt builder
        if (isFunctionGemma) return "";
        String base = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools());
        if (toolDefs.isEmpty()) return base;
        return base + "\n\n" + toolDefs;
    }
```

- [ ] **Step 6: Add FunctionGemma chat dispatch for native backend**

Add a new method `chatFunctionGemmaNative` after the existing `chatNative` method (around line 231). This is the single-round tool calling loop for FunctionGemma:

```java
    // ── FunctionGemma single-turn tool calling ────────────────

    private void chatFunctionGemmaNative(List<AiChatMessage> history, float temperature,
                                          float topP, int maxTokens, AiStreamCallback callback) {
        Thread.ofVirtual().start(() -> {
            try {
                String toolDecls = functionGemmaAdapter.buildToolDeclarations(AiServiceProvider.getTools());
                String prompt = functionGemmaAdapter.buildPrompt(history, toolDecls);

                StringBuilder response = new StringBuilder();
                AtomicBoolean stopped = new AtomicBoolean(false);
                AtomicInteger tokenCount = new AtomicInteger(0);
                long[] firstTokenNanos = {0L};
                long genStartNanos = System.nanoTime();

                // Round 1: generate (may produce tool call or final answer)
                GenerateParams genParams = new GenerateParams()
                    .temperature(temperature).topP(topP).maxTokens(maxTokens);

                nativeContext.generate(prompt, genParams, new GenerateCallback() {
                    @Override
                    public boolean onToken(String tokenText) {
                        if (stopped.get()) return false;
                        if (firstTokenNanos[0] == 0L) firstTokenNanos[0] = System.nanoTime();
                        tokenCount.incrementAndGet();

                        int prevLen = response.length();
                        response.append(tokenText);
                        int stopIdx = StopDetector.findStop(response);
                        if (stopIdx >= 0) {
                            response.setLength(stopIdx);
                            int safeLen = stopIdx - prevLen;
                            if (safeLen > 0) {
                                String safe = tokenText.substring(0, Math.min(tokenText.length(), safeLen));
                                Platform.runLater(() -> callback.onToken(safe));
                            }
                            stopped.set(true);
                            return false;
                        }
                        Platform.runLater(() -> callback.onToken(tokenText));
                        return true;
                    }

                    @Override
                    public void onDone(String fullText) {
                        String output = response.toString();

                        if (functionGemmaAdapter.containsToolCall(output)) {
                            // Parse tool call
                            List<AiToolCall> toolCalls = functionGemmaAdapter.parseToolCalls(output);
                            if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                                AiToolCall tc = toolCalls.get(0);
                                Platform.runLater(() -> callback.onToolCall(tc));

                                // Execute tool
                                AiToolResult result = ToolExecutor.execute(tc.name(), tc.arguments());
                                Platform.runLater(() -> callback.onToolResult(tc.id(), result));

                                // Add to history: model's tool call + tool result
                                history.add(AiChatMessage.assistantWithTools(
                                    functionGemmaAdapter.stripToolCalls(output), toolCalls));
                                history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));

                                // Round 2: generate final answer with tool result
                                String newPrompt = functionGemmaAdapter.buildPrompt(history, toolDecls);
                                generateFinalAnswer(newPrompt, temperature, topP, maxTokens, callback);
                                return;
                            }
                        }

                        // No tool call — output is the final answer
                        int n = tokenCount.get();
                        long baseNanos = firstTokenNanos[0] != 0L ? firstTokenNanos[0] : genStartNanos;
                        long elapsedMs = (System.nanoTime() - baseNanos) / 1_000_000;
                        double tokPerSec = (n > 0 && elapsedMs > 0) ? n * 1000.0 / elapsedMs : 0;
                        Platform.runLater(() -> callback.onComplete(output, n, tokPerSec));
                    }

                    @Override
                    public void onError(String message) {
                        Platform.runLater(() -> callback.onError(new RuntimeException(message)));
                    }
                });
            } catch (Exception e) {
                log.error("FunctionGemma generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }

    /**
     * Generate the final answer after a FunctionGemma tool call.
     */
    private void generateFinalAnswer(String prompt, float temperature, float topP,
                                      int maxTokens, AiStreamCallback callback) {
        GenerateParams genParams = new GenerateParams()
            .temperature(temperature).topP(topP).maxTokens(maxTokens);

        StringBuilder finalResponse = new StringBuilder();
        AtomicInteger finalTokenCount = new AtomicInteger(0);
        long[] finalFirstToken = {0L};
        long finalStart = System.nanoTime();

        nativeContext.generate(prompt, genParams, new GenerateCallback() {
            @Override
            public boolean onToken(String tokenText) {
                if (finalFirstToken[0] == 0L) finalFirstToken[0] = System.nanoTime();
                finalTokenCount.incrementAndGet();

                int prevLen = finalResponse.length();
                finalResponse.append(tokenText);
                int stopIdx = StopDetector.findStop(finalResponse);
                if (stopIdx >= 0) {
                    finalResponse.setLength(stopIdx);
                    int safeLen = stopIdx - prevLen;
                    if (safeLen > 0) {
                        String safe = tokenText.substring(0, Math.min(tokenText.length(), safeLen));
                        Platform.runLater(() -> callback.onToken(safe));
                    }
                    return false;
                }
                Platform.runLater(() -> callback.onToken(tokenText));
                return true;
            }

            @Override
            public void onDone(String fullText) {
                String output = finalResponse.toString();
                int n = finalTokenCount.get();
                long base = finalFirstToken[0] != 0L ? finalFirstToken[0] : finalStart;
                long elapsedMs = (System.nanoTime() - base) / 1_000_000;
                double tokPerSec = (n > 0 && elapsedMs > 0) ? n * 1000.0 / elapsedMs : 0;
                Platform.runLater(() -> callback.onComplete(output, n, tokPerSec));
            }

            @Override
            public void onError(String message) {
                Platform.runLater(() -> callback.onError(new RuntimeException(message)));
            }
        });
    }
```

- [ ] **Step 7: Branch chat dispatch to FunctionGemma**

In `chatNative()` (around line 217), add the FunctionGemma branch at the beginning of the method:

```java
    private void chatNative(List<AiChatMessage> history, float temperature, float topP,
                            int maxTokens, AiStreamCallback callback) {
        if (isFunctionGemma) {
            chatFunctionGemmaNative(history, temperature, topP, maxTokens, callback);
            return;
        }
        Thread.ofVirtual().start(() -> {
            // ... existing code unchanged ...
```

- [ ] **Step 8: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java
git commit -m "✨ feat(ai): integrate FunctionGemmaAdapter into AiServiceImpl with single-round tool loop"
```

---

### Task 5: Accept *.ggufz in file chooser

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java:524`

- [ ] **Step 1: Update extension filter**

Find line 524:
```java
chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GGUF Model", "*.gguf"));
```

Replace with:
```java
chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GGUF Model", "*.gguf", "*.ggufz"));
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java
git commit -m "✨ feat(ui): accept *.ggufz files in model file chooser"
```

---

### Task 6: Final verification build

- [ ] **Step 1: Build and verify compilation**

```bash
mvn install -f SwissKitJ-Api/pom.xml -DskipTests && mvn clean package -f SwissKit/pom.xml -DskipTests
```

Expected: BUILD SUCCESS with no compilation errors.

- [ ] **Step 2: Run the application and manually test**

```bash
java -jar SwissKit/target/SwissKitJ-3.0.0-beta.1.jar
```

Manual test steps:
1. Go to Settings → AI section
2. Load a FunctionGemma GGUF model file
3. Open AI Chat, send a message that should trigger a tool call (e.g., "encode 'hello' to base64")
4. Verify the model generates a `<start_function_call>` response
5. Verify the tool is executed and result is fed back
6. Verify the model produces a final natural-language answer

---

## Self-Review

**1. Spec coverage:**
- ✅ Detection via filename — Task 4 Step 2-3
- ✅ Adapter with tool call parsing — Task 1
- ✅ Adapter with declarations, result formatting, prompt building — Task 2
- ✅ AiServiceImpl integration — Task 4 Steps 1-7
- ✅ StopDetector stop sequences — Task 3
- ✅ File chooser extension — Task 5
- ✅ Single-round only — Task 4 Step 6 (chatFunctionGemmaNative)
- ✅ Local mode guard — Task 4 Step 2 (backend check)

**2. Placeholder scan:** No TBD/TODO/placeholders found. All code blocks contain complete implementations.

**3. Type consistency:**
- `FunctionGemmaAdapter.parseToolCalls(String)` returns `List<AiToolCall>` — matches usage in Task 4 Step 6
- `FunctionGemmaAdapter.buildToolDeclarations(List<AiTool>)` returns `String` — matches usage in Task 4 Step 6
- `FunctionGemmaAdapter.buildPrompt(List<AiChatMessage>, String)` returns `String` — matches usage in Task 4 Step 6
- `FunctionGemmaAdapter.containsToolCall(String)` returns `boolean` — matches usage in Task 4 Step 6
- `FunctionGemmaAdapter.stripToolCalls(String)` returns `String` — matches usage in Task 4 Step 6
- `FunctionGemmaAdapter.formatToolResponse(String, String)` returns `String` — matches usage in `buildPrompt`
- All method names consistent across tasks ✅
