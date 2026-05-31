# FunctionGemma Tool Calling Optimization — Design Spec

**Date:** 2026-05-29
**Scope:** Local model mode (`AiServiceImpl`) only
**Model:** `functiongemma-270m-it` (GGUF, all quantizations)
**Goal:** Achieve reliable tool calling with FunctionGemma's native protocol

## Problem

FunctionGemma uses a completely different tool calling protocol than the current system:

| Aspect | Current System | FunctionGemma |
|--------|---------------|---------------|
| Tool definitions | Markdown + JSON `{"name":"...", "arguments":{...}}` | `<start_function_declaration>declaration:name{...}<end_function_declaration>` |
| Tool calls | Qwen `<|tool_call_begin|>` or bare JSON | `<start_function_call>call:name{param:value}<end_function_call>` |
| Tool results | ChatML `<|im_start|>tool\n...` | `<start_function_response>response:name{...}<end_function_response>` |
| System trigger | None | Must include `"You are a model that can do function calling with the following functions"` |
| String delimiter | Not used | `🪙` wraps all string values |
| Role for system/tool | `system` / `tool` | `developer` |
| Multi-round | Up to 5 rounds | Single-turn only (model not trained for multi-turn) |

With the current code, FunctionGemma tool calling fails 100% because:
1. The system prompt tells it to use JSON, but it was trained on its own format
2. The parser cannot recognize `<start_function_call>` output
3. Tool results are fed back in the wrong format

## Approach: Adapter Layer

Create a `FunctionGemmaAdapter` class that encapsulates all FunctionGemma-specific logic. `AiServiceImpl` detects FunctionGemma at load time and delegates to the adapter instead of the generic tool calling pipeline.

**Why adapter over other options:**
- Clean separation — no risk of breaking existing models
- Single focused class — easy to understand and test
- No changes to public API or other services
- Can be refactored into a strategy pattern later if more special-case models appear

## Detection

Filename-based detection (case-insensitive):

```java
private void detectModelType(String modelPath) {
    if (backend != Backend.NATIVE && backend != Backend.JAVA) return;
    String name = Path.of(modelPath).getFileName().toString().toLowerCase();
    isFunctionGemma = name.contains("functiongemma");
    if (isFunctionGemma) {
        functionGemmaAdapter = new FunctionGemmaAdapter();
    }
}
```

Guarded to local mode only (`backend == NATIVE || backend == JAVA`). OpenAI and Anthropic services are unaffected.

## FunctionGemmaAdapter

Single class: `fan.summer.ai.tools.FunctionGemmaAdapter`

### Tool Declaration Formatting

Converts `AiTool` list to FunctionGemma's native format. Output example:

```
You are a model that can do function calling with the following functions
<start_function_declaration>declaration:get_weather{description:Gets the current weather in a given location.,parameters:{properties:{location:{description:The city and state,type:STRING},unit:{description:The temperature unit,type:STRING}},required:[location],type:OBJECT}}<end_function_declaration>
```

The trigger phrase `"You are a model that can do function calling with the following functions"` is mandatory — it activates FunctionGemma's tool calling mode.

### Tool Call Parsing

Extracts `<start_function_call>call:name{key:value}<end_function_call>` from model output:

```java
private static final Pattern FG_CALL = Pattern.compile(
    "<start_function_call>call:(\\w+)\\{([^}]*)}<end_function_call>"
);
```

Arguments are `key:value` pairs separated by commas. String values are delimited by `🪙`. Types are inferred from the tool's parameter schema.

### Tool Result Formatting

Wraps tool execution results in FunctionGemma's response format:

```
<start_function_response>response:get_weather{result:🪙Sunny, 22°C🪙}<end_function_response>
```

### Prompt Building

Uses `developer` role for system/tool definitions and tool responses:

```
<start_of_turn>developer
You are a model that can do function calling with the following functions
<start_function_declaration>...<end_function_declaration>
<end_of_turn>
<start_of_turn>user
Hey, what's the weather in Tokyo?
<end_of_turn>
<start_of_turn>model
<start_function_call>call:get_weather{location:🪙Tokyo🪙,unit:🪙celsius🪙}<end_function_call>
<end_of_turn>       ← stop here
```

## AiServiceImpl Integration

Four branching points in `AiServiceImpl`, all guarded by `isFunctionGemma`:

### loadModel()
Call `detectModelType(modelPath.toString())` after model is loaded.

### buildSystemPrompt()
When `isFunctionGemma`, return empty string — the adapter embeds tool declarations in the prompt builder instead of using the system prompt.

### Chat dispatch (chatNative / chatJava)
When `isFunctionGemma`, use adapter's single-round flow instead of the existing multi-round loop:
1. Generate with model → check if output contains `<start_function_call>`
2. If yes → parse call → execute tool → format response → generate again → final answer
3. If no → return output as-is

### unloadModel()
Clear `isFunctionGemma` flag and `functionGemmaAdapter` reference.

## StopDetector Changes

Add 4 FunctionGemma-specific stop sequences to `STOP_SEQUENCES`:

```java
"<end_function_call>",
"<start_function_call>",
"<end_function_response>",
"<start_function_response>",
```

Purely additive — no other model emits these tokens.

## File Chooser Update

In `SwissKitJSettingUi.java`, accept `*.ggufz` alongside `*.gguf`:

```java
chooser.getExtensionFilters().add(
    new FileChooser.ExtensionFilter("GGUF Model", "*.gguf", "*.ggufz"));
```

## File Change Summary

| File | Change | Lines |
|------|--------|-------|
| **New:** `ai/tools/FunctionGemmaAdapter.java` | Adapter: declarations, parsing, formatting, prompt building | ~150 |
| **Modified:** `ai/service/AiServiceImpl.java` | Detection, branching, single-round loop | ~40 |
| **Modified:** `ai/inference/StopDetector.java` | 4 stop sequences | 4 |
| **Modified:** `ui/setting/SwissKitJSettingUi.java` | File chooser extension | 1 |
| **Unchanged:** ToolCallParser, ToolSchemaBuilder, ToolExecutor, ChatTemplate, OpenAiService, AnthropicService, native JNI, SwissKitJ-Api | — | 0 |

## Constraints

- Only active when `backend == NATIVE || backend == JAVA` (local mode)
- Only active when model filename contains `"functiongemma"` (case-insensitive)
- Single-turn tool calling only (matches model's training scope)
- No changes to public API (`SwissKitJ-Api` module untouched)
- No changes to OpenAI / Anthropic service implementations
