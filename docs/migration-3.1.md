# Migrating to SwissKitJ 3.1 (ChatBackend)

SwissKitJ 3.1 replaces the `AiService` interface with a unified `ChatBackend`
contract. This is a **breaking change** for external plugins that call
`AiServiceProvider.getService()` or reference the concrete service classes
directly.

## Who is affected

| Plugin pattern | Impact |
|----------------|--------|
| Calls `AiServiceProvider.getService()` to drive AI chat | **Code change required** — type rename |
| Implements `AiService` to register a custom backend | **Rewrite required** (rare) — see [Custom backends](#custom-backends) below |
| Uses `instanceof OpenAiService` / `instanceof AnthropicService` | **Code change required** — see [Type checks](#type-checks) below |
| Only registers `AiTool` instances via `AiServiceProvider.registerTool()` | **No change** |
| Only calls `AiServiceProvider.registerTool()` / `getTools()` / `unregisterTool()` | **No change** |

## Migration steps

### 1. Replace `AiService` type references

```java
// Before (3.0.x and earlier):
import fan.summer.api.ai.AiService;
Optional<AiService> opt = AiServiceProvider.getService();
AiService svc = opt.get();
svc.chat(history, callback);

// After (3.1):
import fan.summer.api.ai.ChatBackend;
Optional<ChatBackend> opt = AiServiceProvider.getService();
ChatBackend svc = opt.get();
svc.chat(history, callback);
```

Method signatures are identical — only the type name changes.

### 2. Type checks

```java
// Before:
if (svc instanceof AiServiceImpl) { ... }       // local mode
if (svc instanceof OpenAiService) { ... }       // cloud mode

// After:
if (svc instanceof LocalChatBackend) { ... }    // local mode
if (svc instanceof CloudChatBackend) { ... }    // cloud mode (OpenAI or Anthropic)
```

To distinguish OpenAI vs Anthropic cloud mode:

```java
if (svc instanceof CloudChatBackend cloud) {
    if (cloud.provider() == CloudChatBackend.Provider.OPENAI) { ... }
    if (cloud.provider() == CloudChatBackend.Provider.ANTHROPIC) { ... }
}
```

### 3. If you instantiated cloud services directly

This was never officially supported for external plugins (the host owns service
lifecycle). If you did it anyway:

```java
// Before:
OpenAiService svc = new OpenAiService();
svc.configure(endpoint, apiKey, model);

// After:
CloudChatBackend svc = CloudChatBackend.openAi(endpoint, apiKey, model);
// (CloudChatBackend.anthropic(...) for Anthropic)
```

### 4. Update pom.xml

**No change required.** `SwissKitJ-Api` 3.1 still has no LC4j dependency —
plugin authors do NOT need to add LC4j to their pom. The `ChatBackend` interface
and its implementations are accessible through the existing `SwissKitJ-Api`
dependency (interface in API module, implementations in host module, supplied
at runtime via the fat JAR).

### 5. Custom backends

If you implemented `AiService` to plug in a custom backend (e.g. a self-hosted
LLM), you now need to implement `ChatBackend` instead. The interface is
non-sealed — anyone can implement it. Method signatures are identical to the
old `AiService` minus the tool-registration methods (`registerTool` /
`unregisterTool` / `getTools`), which moved to `AiServiceProvider` only.

To register your custom backend with the host:

```java
AiServiceProvider.switchMode("my-mode", myCustomBackend);
```

The host will treat it like any other backend.

## Things that did NOT change

- **Tool declarations** — the `AiTool` interface, `AiToolParam`, `AiToolCall`,
  `AiToolResult` are all unchanged. Register via `AiServiceProvider.registerTool(AiTool)`.
- **Chat messages** — `AiChatMessage` and its factory methods (`user(...)`,
  `assistant(...)`, `system(...)`, etc.) are unchanged.
- **Streaming callback** — `AiStreamCallback` interface (`onToken`, `onComplete`,
  `onError`, `onToolCall`, `onToolResult`) is unchanged.
- **Tool registry** — `AiServiceProvider.registerTool()` / `getTools()` /
  `unregisterTool()` / `clearTools()` are unchanged.
- **Plugin entry point** — `SwissKitJPlugin` interface is unchanged.
- **Logger** — `LoggerFactory.getLogger(Class)` is unchanged.

## Why the change

Before 3.1, the cloud stack had hand-rolled HTTP/SSE code across two separate
service classes (`OpenAiService` ~240 LOC, `AnthropicService` ~280 LOC). 3.1
rebuilds the cloud layer on LangChain4j and unifies both providers into a single
`CloudChatBackend` (~450 LOC including the LC4j adapter), eliminating
provider-specific bugs and feature gaps. Local mode (in-process GGUF) was
renamed from `AiServiceImpl` to `LocalChatBackend` — pure rename, no behavior
change.

Local mode (in-process GGUF) was renamed from `AiServiceImpl` to
`LocalChatBackend` — pure rename, no behavior change.

## Getting help

If you hit an issue not covered here, open a discussion at
[github.com/MuskStark/SwissKitJ/discussions](https://github.com/MuskStark/SwissKitJ/discussions).
