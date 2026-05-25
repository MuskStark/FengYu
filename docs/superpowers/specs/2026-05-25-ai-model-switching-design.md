# AI Model Source Switching — Local / OpenAI / Anthropic

**Date:** 2026-05-25
**Status:** Approved

## Summary

Add support for switching the AI chat backend between local GGUF models, OpenAI-compatible APIs, and Anthropic Claude APIs. The user selects a mode in Settings; the AI Chat UI automatically uses the active backend. All three backends support streaming chat and tool calling.

## Architecture

Three `AiService` implementations, selected by a mode setting:

| Implementation | Backend | Location |
|---|---|---|
| `AiServiceImpl` (existing) | Local GGUF (llama.cpp JNI / pure Java) | `fan.summer.ai.service` |
| `OpenAiService` (new) | OpenAI-compatible HTTP API | `fan.summer.ai.service` |
| `AnthropicService` (new) | Anthropic HTTP API | `fan.summer.ai.service` |

The `AiService` interface in `SwissKitJ-Api` is unchanged. A factory method on `AiServiceProvider` creates/sets the appropriate implementation based on the `ai.mode` DB setting.

## Mode Switching

**DB setting:** `ai.mode` with values `local` (default), `openai`, `anthropic`.

**`AiServiceProvider` changes:** Add `switchMode(String mode)` that instantiates the correct `AiService` and calls `setService()`. On mode change, the previous service is discarded (local models get `unloadModel()`, remote services just drop their HTTP connections).

**Startup auto-initialize:** `SwissKitJApp` reads `ai.mode` from DB and calls `switchMode()` before showing the UI. `switchMode()` also reads mode-specific settings and prepares the service:
- **`local`:** If `ai.model.path` is set and the file exists, automatically loads the GGUF model in a background thread. The chat UI shows a loading indicator until ready. No manual "Load" button press needed.
- **`openai`:** Reads endpoint/API key/model from DB. Service is immediately ready (`isReady()` returns true if all three are configured).
- **`anthropic`:** Same as OpenAI — reads config from DB, immediately ready if configured.

**User experience:** After initial configuration (first-time setup in Settings), subsequent app launches load the AI backend automatically. Opening the AI Chat tool shows the model ready to use immediately.

## Settings UI

The AI Model tab is restructured with a mode selector at the top, followed by mode-specific config panels that swap visibility.

### Mode selector

`ComboBox<String>`: "Local Model" / "OpenAI Compatible" / "Anthropic Claude". On change: save `ai.mode`, switch visible panel, call `AiServiceProvider.switchMode(mode)`.

### Config panels

**Local (existing fields, unchanged):**
- Model path, browse/load/unload buttons, memory bar

**OpenAI (new):**
- API endpoint URL (default: `https://api.openai.com`)
- API key (password field)
- Model name (text field, e.g. `gpt-4o`)
- Test Connection button

**Anthropic (new):**
- API endpoint URL (default: `https://api.anthropic.com`)
- API key (password field)
- Model name (text field, e.g. `claude-sonnet-4-20250514`)
- Test Connection button

**Shared across all modes (existing keys):** temperature (`ai.temperature`), max tokens (`ai.max_tokens`), system prompt (`ai.system_prompt`).

### New DB keys

| Key | Purpose | Example |
|---|---|---|
| `ai.mode` | Active backend | `local` / `openai` / `anthropic` |
| `ai.openai.endpoint` | OpenAI API base URL | `https://api.openai.com` |
| `ai.openai.api_key` | OpenAI API key | `sk-...` |
| `ai.openai.model` | Model identifier | `gpt-4o` |
| `ai.anthropic.endpoint` | Anthropic API base URL | `https://api.anthropic.com` |
| `ai.anthropic.api_key` | Anthropic API key | `sk-ant-...` |
| `ai.anthropic.model` | Model identifier | `claude-sonnet-4-20250514` |

## HTTP Client & Streaming

Uses `java.net.http.HttpClient` (no new dependencies).

**Streaming flow:**
1. POST request to provider endpoint with `HTTP/1.1`
2. Read response body as `InputStream` in a virtual thread
3. Parse SSE lines (`data: ...` for OpenAI, `event:` + `data:` for Anthropic)
4. Extract text delta → `callback.onToken(delta)` on FX thread
5. On stream end → `callback.onComplete()`

**Error handling:**
- HTTP errors (401, 429, 500) → `callback.onError()` with descriptive message
- Network timeout → single retry, then error
- Malformed SSE chunks → log warning, skip, continue

## Tool Calling

Existing `AiTool` / `AiToolCall` / `AiToolResult` types serve as the internal representation. Each remote service translates to/from provider-specific JSON formats.

### Format differences

| Concept | OpenAI wire | Anthropic wire |
|---|---|---|
| Tool definition | `tools[].function { name, description, parameters }` | `tools[] { name, description, input_schema }` |
| Tool call in response | `message.tool_calls[] { id, function { name, arguments } }` | `content_block { type: tool_use, id, name, input }` |
| Tool result | `role: tool, tool_call_id, content` | `role: user, content: [{ type: tool_result, tool_use_id, content }]` |
| System prompt | Top-level `system` field | Top-level `system` array of content blocks |

Each service has its own translation methods (`toXxxTools`, `parseToolCalls`, `buildToolResultMessage`, `buildHistory`).

## Files

### New files

- `SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java`
- `SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java`

### Modified files

- `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java` — add `switchMode()`
- `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` — restructure AI tab
- `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` — read `ai.mode` at startup
- `SwissKit/src/main/resources/i18n/messages.properties` — new Chinese labels
- `SwissKit/src/main/resources/i18n/messages_en.properties` — new English labels

## Out of Scope

- API key encryption (follows existing plaintext pattern for email passwords)
- Multiple API key profiles per provider
- Token usage / billing tracking
- Proxy configuration
- Anthropic prompt caching or other advanced API features
