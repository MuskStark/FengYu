# AI Plugin-Tool FileRef Injection Design

## Goal

Close the broken link between the AI chat path and plugin workers that need files, so that
when an AI model calls a plugin-provided `aiTool` that takes a file argument, the worker
receives a real resolved filesystem path instead of an unusable ad-hoc object or a fabricated
reference id.

Today the UI→worker RPC path works correctly (the host's `PluginProcessManager.resolveRefs`
rewrites FileRefs to absolute paths before dispatch), but the model→worker path is disconnected:
the model has no way to obtain a valid `ref_<uuid>` grant id, `resolveRefs` therefore rewrites
nothing, and the worker gets whatever the model invented. This design wires the missing bridge:
let the user attach files to a chat conversation, and have the host inject the resulting FileRefs
into plugin tool calls.

## Architecture Decisions (decided in brainstorming)

1. **Route: transparent injection (B) primary, model-visible FileRef (A) fallback.** The host
   injects FileRefs into tool arguments without the model knowing FileRefs exist (B). When the
   host cannot confidently inject (multiple file params, write params, no matching grant), it
   falls back to listing available FileRefs in the system prompt and lets the model fill them (A).
2. **Parameter binding: single-param auto + multi-param degrade to A.** The host only auto-injects
   when a tool has exactly one file-class parameter AND a matching grant exists. Otherwise it
   degrades to A rather than guessing.
3. **Lifetime: in-session memory only.** Active file grants live in the in-process
   `ConcurrentHashMap` (`PluginFileGrantService.grants`). They are not persisted to the
   conversation DB and do not survive a host restart. Persistence is a follow-up, out of scope.
4. **State carrier: explicit threading.** `ChatRequest` gains an `activeFileRefs` field, the
   `ChatBackend.chat()` signature gains a parameter, and a `ThreadLocal` carries the per-request
   FileRefs from the streaming entry point to the singleton `ToolCallback` instances.
5. **write-dir params: also degrade to A.** The host does NOT auto-create output-directory grants.
   Only read-class parameters are eligible for auto-injection.

## Non-Goals

- Persisting active files across host restarts or page refreshes (conversation-DB schema changes,
  re-grant-on-reload, missing-file handling — all deferred).
- Cross-conversation or permanent grant lifetime.
- A generalized multi-parameter heuristic that distributes several FileRefs across several params.
- Surfacing file selection from inside a plugin iframe into the AI chat ("share to conversation").
- Changing the manifest `aiTools` schema, the worker FileRef contract, or `resolveRefs` detection.

## Background: why the model→worker path is broken

- `AiToolDiscoveryConfig.aiToolCallbacks` (`AiToolDiscoveryConfig.java:82-94`) builds one
  `ToolCallback` per plugin manifest `aiTool`. Its `call(String input)` deserializes the model's
  JSON arguments into a `Map` and passes it **unchanged** to `PluginProcessManager.invoke`.
- `PluginProcessManager.resolveRefs` (`PluginProcessManager.java:113,153-164`) is the only place
  the host inspects arguments for FileRef-shaped values. It matches a `Map` whose `"id"` starts
  with `ref_` and that has a `"kind"` key, then calls `PluginFileGrantService.resolve(pluginId, id)`
  to recover the absolute path.
- `ref_<uuid>` ids are minted **only** by `PluginFileGrantService.register` (`:106-111`), reachable
  only from the file grant endpoints (`PluginRuntimeFileController` upload/native/output). The
  model has no channel to obtain a valid grant id.
- `AiController.ChatRequest` carries only `messages` (`AiController.java:222`); the system prompt
  assembly (`SkillPromptAppender`) appends only the skills catalog. Nothing inserts a granted
  FileRef into the model's context.

Result: `resolveRefs` never matches anything in the AI path, the model's raw argument reaches the
worker unchanged, and the worker fails (Excel's `requiredString` returns null; Email's handler
throws `"FileRef must be resolved by the FengYu host"`).

## §1 — Data Model & End-to-End Data Flow

### New types (backend, new file `ai/ChatFileContext.java`)

```java
/** A file grant active for one AI chat turn, scoped to the plugin whose tool may consume it. */
public record ActiveFileRef(String pluginId, PluginFileGrantService.FileRef ref) {}

/**
 * Per-request bridge that lets the singleton plugin ToolCallbacks read the current chat turn's
 * active FileRefs. Set by AiController.stream around backend.chat(); cleared in finally.
 */
public final class ChatFileContext {
    private static final ThreadLocal<List<ActiveFileRef>> CURRENT = new ThreadLocal<>();
    public static void set(List<ActiveFileRef> refs) { CURRENT.set(refs == null ? List.of() : refs); }
    public static List<ActiveFileRef> current() { List<ActiveFileRef> v = CURRENT.get(); return v == null ? List.of() : v; }
    public static void clear() { CURRENT.remove(); }
}
```

### `ChatRequest` extension (`AiController.java`)

```java
public record ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs) {}
public record ActiveFileRefDto(String pluginId, PluginFileGrantService.FileRef ref) {}
```

`activeFileRefs` is nullable/empty-safe; older callers omitting it behave exactly as today.

### Data flow

```
[AiChat.vue] user clicks "attach file for this conversation"
  → makeDesktop().pickFile() / <input type=file>        (reuse PluginView.vue:74-106 pattern)
  → api.grantRuntimeNativePath(pluginId, path, ...)      (desktop)
     or api.uploadRuntimeFile(pluginId, file)            (browser)
  → PluginFileRef stored in aiSession.activeFiles (grouped by pluginId)
  → on send: api.aiChat(messages, activeFiles)           ← ChatRequest extended

[AiController.chat] stash history + activeFileRefs together in `pending` map
[AiController.stream] pending.remove(streamId) yields both
  → try { ChatFileContext.set(activeFileRefs);
          backend.chat(history, temp, topP, maxTokens, activeFileRefs, callback); }
    finally { ChatFileContext.clear(); }                 ← leak guard

[backend.startChat] before calling the model, append activeFileRefs to the system prompt (A fallback)
[model invokes plugin aiTool] → ToolCallback.call(input)
  → injectFileRefs(input, pluginId, tool.inputSchema(), ChatFileContext.current())   ← pure function
  → processes.invoke(...) → resolveRefs matches ref_<uuid> → worker receives real path
```

### Why both a signature parameter AND a ThreadLocal

They serve different consumers and are not redundant:

- The **`activeFileRefs` parameter on `chat()`** is the explicit, type-safe channel by which
  `backend.startChat` receives the list to append to the system prompt (the A fallback). Backends
  are normal Spring beans and can take method arguments directly.
- The **`ChatFileContext` ThreadLocal** exists because the plugin `ToolCallback`s are
  `@Bean`-singletons built once at startup (`AiToolDiscoveryConfig.java:78`) — they cannot have
  request-scoped state injected into them. `ChatFileContext.current()` is the only way a singleton
  callback can read the *current* request's FileRefs at `call()` time.

`AiController.stream` does both in the same `try`: sets the ThreadLocal (for the callbacks) and
passes the argument (for the backend). The ThreadLocal is set before `chat()` returns and cleared
in `finally`.

Two load-bearing facts: (1) `ChatFileContext` is ThreadLocal and always cleared in a `finally`;
(2) the frontend grants files through the **existing** `/api/plugin-runtime/{pluginId}/files/...`
endpoints — no new endpoints are added.

## §2 — Frontend Interaction & State

### Session state (`aiSession.ts`)

`ChatTurn` is unchanged. The store gains an in-memory, non-persisted active-files list grouped by
`pluginId` (because a FileRef is only valid for the plugin it was granted to):

```ts
interface ActiveFileEntry { pluginId: string; ref: PluginFileRef }

const activeFiles = ref<ActiveFileEntry[]>([])

function addActiveFile(pluginId: string, ref: PluginFileRef) {
  // same-plugin, same-name file replaces rather than duplicates
  const idx = activeFiles.value.findIndex(f => f.pluginId === pluginId && f.ref.name === ref.name)
  if (idx >= 0) activeFiles.value[idx] = { pluginId, ref }
  else activeFiles.value.push({ pluginId, ref })
}
function removeActiveFile(pluginId: string, refId: string) {
  activeFiles.value = activeFiles.value.filter(f => !(f.pluginId === pluginId && f.ref.id === refId))
}
function clearActiveFiles() { activeFiles.value = [] }
```

- `newConversation()` and `clear()` call `clearActiveFiles()` — a fresh conversation starts clean.
- `send()` calls `api.aiChat(toChatHistory(turns), activeFiles.value)`.

### `api.aiChat` signature (`client.ts:185`)

```ts
async aiChat(messages: ChatMessage[], activeFileRefs?: ActiveFileEntry[]): Promise<ChatStartResponse>
// body: { messages, activeFileRefs: activeFileRefs ?? [] }
```

### Chat UI (`AiChat.vue`)

A compact "active files" strip above the composer, shown only when `activeFiles` is non-empty, plus
an attach affordance. It reuses existing `cx-*` classes and matches the confirmation-card style:

```
┌─ Active files (only when non-empty) ───────────────────┐
│ 📄 report.xlsx  [fan.summer.excel ▾]  ✕                │
│ 📁 /work/proj   [fan.summer.offlinepython ▾]  ✕        │
│ ＋ Attach a file for this conversation                  │
└────────────────────────────────────────────────────────┘
[ textarea ..................................... ] [▲]
```

### "Attach a file" flow

Reuses the desktop/browser split proven in `PluginView.vue:74-106`:

```ts
async function attachFile() {
  const desktop = makeDesktop()
  let pluginId: string
  if (desktop) {
    const path = await desktop.pickFile(...)     // or pickDirectory()
    pluginId = guessPlugin(path)                 // see guess rules below
    const ref = await api.grantRuntimeNativePath(pluginId, path, kind, access)
    addActiveFile(pluginId, ref)
  } else {
    // browser: <input type=file> → uploadRuntimeFile / uploadRuntimeDirectory
    const input = document.createElement('input'); input.type = 'file'
    input.onchange = async () => { /* upload → guessPlugin(ref.name) → addActiveFile */ }
    input.click()
  }
}
```

### Determining the plugin for an attached file

Because the AI chat has no "current plugin" (unlike a plugin iframe), attaching a file must
associate it with a target plugin. The chosen approach is **guess-after-select, correctable**:

- After the file is selected and granted, the frontend guesses the plugin from the file
  extension via an extensible mapping table (frontend constant):

  | File signal | Guessed plugin |
  |---|---|
  | `.xlsx` / `.xls` / `.xlsm` | `fan.summer.excel` |
  | `.py`, or a directory containing `.py` | `fan.summer.offlinepython` |
  | anything else | no guess (empty pluginId) |

- The active-files strip renders the guessed plugin as a **clickable dropdown** listing every
  installed plugin that declares the relevant file permission (`files.read`, or `files.write` for
  write grants). The user can correct the guess at any time.
- When no plugin was guessed, the entry shows "[select a plugin ▾]" as its default state.

### Send-time handling of files with no plugin chosen

When `send()` runs, any active-file entry whose `pluginId` is empty is **filtered out** of the
request body and the strip marks it (red hint) "please choose a plugin". Sending is not blocked —
the user can still send a text-only message and the omitted file simply will not be injected this
turn.

## §3 — Injection Decision (core algorithm in `ToolCallback.call()`)

When the model invokes a plugin `aiTool`, `call()` runs a pure function BEFORE
`processes.invoke(...)`. The function inspects the tool's `inputSchema` and the request's
`activeFileRefs` and decides whether to inject (B) or pass through unchanged (degrade to A).

### Step 1 — classify the tool's file-class parameters

Parse `tool.inputSchema()` (a JSON Schema string) and classify each property:

| Parameter signal | Class |
|---|---|
| `type:"object"` + description contains `FileRef` (not `Directory`/`writable`/`output`) | **read-file** |
| `type:"object"` + description contains `DirectoryRef` and `writable`/`output` | **write-dir** |
| `type:"object"` + description contains `DirectoryRef` (no writable) | **read-dir** |
| `type:"array"` whose items are object + FileRef signal | **file-list** |
| otherwise | non-file (leave untouched) |

The classification rules are anchored on the real `description` wording across the four official
plugin manifests (`plugin-excel`, `plugin-email`, `plugin-offlinepython`, `plugin-markdown`).

### Step 2 — injection decision

```
fileParams  = file-class parameters detected in step 1
pluginRefs  = activeFileRefs entries whose pluginId == this tool's owning plugin id

if fileParams is empty:
    pass through unchanged                     // tool needs no file (e.g. offlinepython_doctor)

elif fileParams.size == 1
     and the single param is read-class (read-file | read-dir | file-list)
     and pluginRefs contains a matching read grant:
    [B: transparent injection]
    replace that one param's value with the whole matched FileRef object
    {id,name,kind,access,size}
    → resolveRefs matches ref_<uuid> → worker gets real path

else (write-dir param, OR multiple file params, OR no matching read grant):
    [degrade to A: do not inject]
    pass params through unchanged
    (the model has seen the FileRef list in the system prompt and fills them itself)
```

The single read-class matching rule: the grant's `kind` must be compatible with the parameter
(`file` for read-file/file-list, `directory` for read-dir) and its `access` must include `read`.

### Step 3 — A fallback (system-prompt injection)

In `backend.startChat`, before calling the model, append the request's `activeFileRefs` to the
effective system prompt when non-empty:

```
## Files available for this conversation
When a plugin tool needs a file parameter, pick from this list and pass the WHOLE object as the
argument, exactly as shown:
- fan.summer.excel: {"id":"ref_3f2a…","name":"report.xlsx","kind":"file","access":"read","size":12345}
- fan.summer.offlinepython: {"id":"ref_9b1c…","name":"proj","kind":"directory","access":"read","size":0}
```

The prompt is only the fallback; the primary path is B auto-injection. Both consume the same
`activeFileRefs` list, so they are mutually reinforcing rather than redundant: B covers the common
single-read-param case with zero model burden; A covers multi-param/write cases where the model
must choose.

### Pure function contract (testability)

The decision MUST be a pure function with no ThreadLocal/Spring coupling:

```java
package fan.summer.fengyu.ai;

/** Pure: maps the model's raw tool args to the args actually dispatched to the worker. */
static Map<String, Object> injectFileRefs(
    Map<String, Object> modelParams,      // the model's raw deserialized arguments
    String pluginId,                       // the owning plugin id of the tool being called
    String inputSchema,                    // the tool's JSON Schema (tool.inputSchema())
    List<ActiveFileRef> activeRefs         // ChatFileContext.current() for this request
);
```

`ToolCallback.call()` becomes: parse model args → `injectFileRefs(...)` → `processes.invoke(...)`.
All branching logic lives in the pure function and is unit-testable without Spring.

## §4 — Error Handling, Security & Boundaries

### FileRef invalidation

grants live in an in-process `ConcurrentHashMap` (`PluginFileGrantService.java:24`); these cases
make `resolveRefs` → `files.resolve()` (`:100-104`) throw `Unknown or unauthorized file reference`:

| Case | Handling |
|---|---|
| Host restart (`close()` clears grants + runtime-files) | User must re-attach files. No preflight at send. |
| pluginId mismatch (user attached to the wrong plugin) | Same — `resolve` enforces `grant.pluginId`. |
| User removed the active-file entry but the model still references the old FileRef | Not in `ChatFileContext.current()` → injection misses → degrade to A → not in prompt either → model may fabricate a ref → `resolve` throws. |

**Universal safety net:** `call()` already wraps dispatch in try/catch
(`AiToolDiscoveryConfig.java:91-93`) and returns `{"success":false,"error":"..."}`. Any FileRef
failure becomes a structured error the model can recover from (re-choose, or ask the user). No
FileRef invalidation crashes the conversation.

### Injection does not weaken the sandbox

B injects FileRef objects that were **already granted** by the frontend through the grant
endpoints — the identical path used by UI→worker RPCs. Read access still snapshots into the
sandboxed runtime-files root (`PluginFileGrantService.snapshot` `:113-138`). Sandbox semantics
are unchanged.

B never accepts a model-fabricated ref: it only reads from `ChatFileContext.current()`, which
contains only refs the frontend truly granted. If the model puts a fake ref in a parameter, B
does not overwrite it; it passes through unchanged to `resolveRefs`, which throws (correct).

### ThreadLocal lifecycle (leak guard)

```java
// AiController.stream
try {
    ChatFileContext.set(activeFileRefs);
    backend.chat(history, temp, topP, maxTokens, activeFileRefs, callback);
} finally {
    ChatFileContext.clear();
}
```

Spring AI executes tool calls synchronously within the `chat()` call chain (before SSE callbacks
are dispatched), so the ThreadLocal is valid for the entire tool-execution window and reliably
cleared by `finally`. Even multi-turn tool calls within one chat turn stay inside this window.

### Permission checks (A fallback prompt content)

The FileRef list injected into the system prompt contains only already-granted refs. The grant
endpoints already enforced `require(id, "files.read"/"files.write")`
(`PluginRuntimeFileController.java:40,55,61`). The prompt never leaks information about files the
user cannot access.

### Inherent limitation of the A fallback

A depends on the model faithfully copying the whole FileRef JSON object. Strong models (GPT-4-class,
Claude) do this reliably; weaker models may return only the `id` field or rewrite the object, which
`resolveRefs` will not match → throw → model recovers from the error or asks the user. This is the
inherent cost of "B primary, A fallback" and cannot be eliminated — but B covers the high-frequency
single-read-param path, keeping overall reliability high.

## §5 — Testing Strategy

Three layers, each verifying only its key behavior. Backend uses JUnit; frontend uses vitest.

### Backend unit tests (new, around `AiToolDiscoveryConfig` / the pure `injectFileRefs`)

| Test | Asserts |
|---|---|
| `classify` read-file | schema with `FileRef` (no Directory/writable) → read-file |
| `classify` write-dir | description with `writable DirectoryRef` → write-dir |
| `classify` ignores non-file params | `executable: type:string` → not in file-param set |
| **B inject: single read param hits** | 1 read-file param + activeRefs has matching read grant → param replaced with whole FileRef object |
| **B inject: pluginId mismatch → no inject** | same but ref belongs to a different plugin → pass through (degrade to A) |
| **B inject: kind mismatch → no inject** | read-dir param but only a `file` grant available → pass through |
| **degrade A: write-dir → no inject** | 1 write-dir param → pass through, no grant created |
| **degrade A: multiple file params → no inject** | 2 file params → pass through |
| **degrade A: no matching grant → no inject** | 1 read-file param but activeRefs empty → pass through |
| `ChatFileContext` ThreadLocal | set → current returns list; clear → current returns empty list |

All pure-function tests mock `PluginProcessManager` to capture the dispatched params; no real
worker is started.

### Backend integration tests (`AiController` + backend)

| Test | Asserts |
|---|---|
| `ChatRequest` with `activeFileRefs` | `/api/ai/chat` accepts the new field, returns a streamId |
| system prompt contains the FileRef list (A fallback) | mock backend captures the final system prompt; assert it contains `ref_xxx` and the pluginId |
| ThreadLocal cleared | after `chat()` returns, `ChatFileContext.current()` is empty |

### Frontend unit tests (vitest, extending the existing `aiSession` tests)

| Test | Asserts |
|---|---|
| `addActiveFile` same-name replaces | same plugin + same file name added twice → one entry, latest ref |
| `removeActiveFile` | removes by pluginId + refId |
| `send` filters files with no plugin | an entry with empty pluginId is omitted from the request body |
| plugin guess mapping | `.xlsx` → excel; `.py` → offlinepython; unknown extension → empty |

### Not tested (YAGNI)

- Real worker end-to-end (already covered by `scripts/e2e-smoke.sh` for the invoke path).
- The desktop file picker (Electron layer, not core to this change).
- DB persistence (out of scope under the in-session-memory decision).

## Files Touched (summary)

Backend (Java):
- new `FengYu/src/main/java/fan/summer/fengyu/ai/ChatFileContext.java` — `ActiveFileRef` record + ThreadLocal holder.
- new `FengYu/src/main/java/fan/summer/fengyu/ai/AiToolFileInjector.java` — the pure `injectFileRefs` + parameter classification.
- `AiToolDiscoveryConfig.java` — `call()` invokes the injector before `processes.invoke`.
- `AiController.java` — `ChatRequest`/`ActiveFileRefDto` gain `activeFileRefs`; `pending` map stores it; `stream()` sets/clears `ChatFileContext` and passes it to `chat()`.
- `ChatBackend.java` + `SpringAiCloudBackend.java` + `OllamaLocalBackend.java` — the **5-arg `chat(history, temperature, topP, maxTokens, callback)` overload** (the one `AiController.stream` calls at `:85`) gains an `activeFileRefs` parameter; the 2-arg overload delegates with `List.of()`. Both backends append it to the effective system prompt (A fallback).

Frontend (TS/Vue):
- `frontend/src/api/client.ts` — `aiChat(messages, activeFileRefs?)`.
- `frontend/src/api/types.ts` — `ActiveFileEntry` type (or reuse `PluginFileRef` + pluginId).
- `frontend/src/stores/aiSession.ts` — `activeFiles` state + add/remove/clear + send-time filtering + plugin guess map.
- `frontend/src/views/AiChat.vue` — active-files strip, attach affordance, plugin dropdown.

Tests:
- backend: `AiToolFileInjectorTest`, `ChatFileContextTest`, an `AiController` integration test for the new field + prompt.
- frontend: extend the `aiSession` store test for active-files behavior.

Docs:
- `docs/en/plugins/ai-tools.md` and `docs/zh/plugins/ai-tools.md` — correct the "host rewrites the FileRef before the worker sees it" claim for the AI path; document the new attach-file flow.
- `docs/en/plugins/file-io.md` and `docs/zh/plugins/file-io.md` — note the AI-chat attach path reuses the same grant endpoints.

## Open Questions

None remaining — all five architecture questions were resolved during brainstorming.
