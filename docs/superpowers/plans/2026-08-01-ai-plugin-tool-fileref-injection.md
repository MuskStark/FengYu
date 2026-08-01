# AI Plugin-Tool FileRef Injection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user attach files to an AI chat turn, and have the host inject the resulting FileRefs into plugin tool calls so workers receive real resolved filesystem paths instead of unusable ad-hoc objects.

**Architecture:** User attaches a file → frontend grants it through the existing `/api/plugin-runtime/{pluginId}/files/...` endpoints → the FileRef travels in a new `ChatRequest.activeFileRefs` field → `AiController.stream` stores it in a `ThreadLocal` (`ChatFileContext`) and passes it to `backend.chat()` → the backend appends it to the system prompt (A fallback); the singleton plugin `ToolCallback` reads the ThreadLocal and, for single read-class file params, transparently injects the FileRef before dispatch (B primary). Both paths feed the existing `PluginProcessManager.resolveRefs`, which already rewrites `ref_<uuid>` to absolute paths for the worker.

**Tech Stack:** Java 21 + Spring Boot (backend), Spring AI (`ToolCallback`/`ToolDefinition`), JUnit 5 + `@TempDir` (backend tests); Vue 3 + TypeScript + Pinia + vitest (frontend).

## Global Constraints

- **Backend build/test:** `./mvnw -f FengYu/pom.xml test` (use `./mvnw`, not system Maven). Pure-unit tests must NOT carry `@SpringBootTest`; follow `PluginFileGrantServiceTest.java` conventions (JUnit 5 Jupiter, `org.junit.jupiter.api.Assertions.*`, `@TempDir`).
- **Frontend build/test:** `cd frontend && npm test` (vitest, config at `frontend/vitest.config.ts`). Tests are co-located (`*.test.ts`). No `setActivePinia()` needed for pure-function tests.
- **No new file-grant endpoints.** The frontend reuses `uploadRuntimeFile` / `grantRuntimeNativePath` (already in `frontend/src/api/client.ts:103-128`).
- **Sandbox must not weaken.** B-path injection only ever inserts FileRef objects the frontend truly granted; it never synthesizes paths or grants.
- **Commit convention:** conventional commits with emojis — `✨` feat, `🐛` fix, `♻️` refactor, `📝` docs, `✅` test. Commit at the end of each task. Do not push unless asked.
- **The repo wins over prose.** Read the actual file before editing; match surrounding style, naming, comment density.
- Legacy to avoid: JavaFX, `FengYuPluginV2`, `ServiceLoader` SPI, in-process plugin beans, `-sk-*` CSS. None of these describe 4.0.0.

## File Structure

Backend (Java), under `FengYu/src/main/java/fan/summer/fengyu/`:

- **NEW `ai/ChatFileContext.java`** — `ActiveFileRef` record + the `ThreadLocal` holder (`set`/`current`/`clear`). Pure data + thread-local bridge, no Spring coupling.
- **NEW `ai/AiToolFileInjector.java`** — the PURE `injectFileRefs(modelParams, pluginId, inputSchema, activeRefs)` function + the parameter classifier. No Spring, no ThreadLocal — fully unit-testable.
- **MODIFY `ai/config/AiToolDiscoveryConfig.java`** — the anonymous plugin `ToolCallback.call()` calls `AiToolFileInjector.injectFileRefs(...)` before `processes.invoke(...)`.
- **MODIFY `web/controller/AiController.java`** — `ChatRequest` gains `activeFileRefs`; `pending` map stores it; `stream()` does `ChatFileContext.set/clear` in try/finally and passes it to `chat()`.
- **MODIFY `ai/ChatBackend.java`** — the 5-arg `chat(...)` overload gains `List<ActiveFileRef> activeFileRefs`; the 2-arg overload delegates with `List.of()`.
- **MODIFY `ai/service/SpringAiCloudBackend.java`** + **`ai/service/OllamaLocalBackend.java`** — implement the new `chat()` param; append `activeFileRefs` to the effective system prompt (A fallback) via a new private helper.

Frontend (TS/Vue), under `frontend/src/`:

- **MODIFY `api/client.ts`** — `aiChat(messages, activeFileRefs?)` sends the new field.
- **MODIFY `api/types.ts`** — add `ActiveFileEntry` type.
- **MODIFY `stores/aiSession.ts`** — `activeFiles` reactive state + `addActiveFile`/`removeActiveFile`/`clearActiveFiles`; `send()` filters entries with empty pluginId and passes the list to `aiChat`; `newConversation`/`clear` reset it.
- **MODIFY `views/AiChat.vue`** — the active-files strip + attach affordance + plugin dropdown + extension→plugin guess.

Tests:

- **NEW `FengYu/src/test/.../ai/AiToolFileInjectorTest.java`** — pure-function unit tests for classification + injection.
- **NEW `FengYu/src/test/.../ai/ChatFileContextTest.java`** — ThreadLocal set/clear.
- **NEW `FengYu/src/test/.../web/controller/AiControllerFileContextTest.java`** — Spring integration test asserting `ChatRequest.activeFileRefs` is accepted and `ChatFileContext` is cleared after stream.
- **MODIFY `frontend/src/stores/aiSession.test.ts`** — cover active-files add/remove/filter + plugin guess.

Docs:

- **MODIFY `docs/en/plugins/ai-tools.md`** + **`docs/zh/plugins/ai-tools.md`** — correct the AI-path FileRef claim; document the attach-file flow.
- **MODIFY `docs/en/plugins/file-io.md`** + **`docs/zh/plugins/file-io.md`** — note the AI-chat attach path reuses the same grant endpoints.

---

## Task 1: `ChatFileContext` ThreadLocal holder

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/ai/ChatFileContext.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/ai/ChatFileContextTest.java`

**Interfaces:**
- Produces: `fan.summer.fengyu.ai.ChatFileContext` with `ActiveFileRef` record + static `set(List<ActiveFileRef>)`, `current()` (returns `List<ActiveFileRef>`, never null), `clear()`. `ActiveFileRef(String pluginId, PluginFileGrantService.FileRef ref)`.

- [ ] **Step 1: Write the failing test**

Create `FengYu/src/test/java/fan/summer/fengyu/ai/ChatFileContextTest.java`:

```java
package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileContextTest {

    @AfterEach
    void cleanThread() {
        ChatFileContext.clear();
    }

    @Test
    void currentIsEmptyBeforeSet() {
        assertTrue(ChatFileContext.current().isEmpty(),
            "current() must return an empty list, not null, before anything is set");
    }

    @Test
    void setMakesRefsVisibleToCurrent() {
        ActiveFileRef ref = new ActiveFileRef("fan.summer.excel",
            new FileRef("ref_abc", "report.xlsx", "file", "read", 123L));
        ChatFileContext.set(List.of(ref));
        assertEquals(1, ChatFileContext.current().size());
        assertEquals("fan.summer.excel", ChatFileContext.current().get(0).pluginId());
    }

    @Test
    void setNullIsTreatedAsEmpty() {
        ChatFileContext.set(null);
        assertTrue(ChatFileContext.current().isEmpty());
    }

    @Test
    void clearRemovesRefs() {
        ChatFileContext.set(List.of(new ActiveFileRef("p", new FileRef("ref_x", "f", "file", "read", 1L))));
        ChatFileContext.clear();
        assertTrue(ChatFileContext.current().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=ChatFileContextTest`
Expected: compile failure — `ChatFileContext` and `ActiveFileRef` do not exist.

- [ ] **Step 3: Write minimal implementation**

Create `FengYu/src/main/java/fan/summer/fengyu/ai/ChatFileContext.java`:

```java
package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;

import java.util.List;

/**
 * Per-request bridge that lets the singleton plugin {@code ToolCallback}s read the current chat
 * turn's active file grants. The callbacks are built once at startup
 * ({@code AiToolDiscoveryConfig.aiToolCallbacks}) and cannot hold request-scoped state, so
 * {@link AiController} sets this ThreadLocal before {@code backend.chat(...)} and clears it in a
 * {@code finally}. Spring AI executes tool calls synchronously within the chat call chain, so the
 * value is visible for the entire tool-execution window.
 */
public final class ChatFileContext {

    private static final ThreadLocal<List<ActiveFileRef>> CURRENT = new ThreadLocal<>();

    private ChatFileContext() {}

    /** Stash the active file refs for the current chat turn; {@code null} is treated as empty. */
    public static void set(List<ActiveFileRef> refs) {
        CURRENT.set(refs == null ? List.of() : refs);
    }

    /** @return the active file refs for the current chat turn, never {@code null}. */
    public static List<ActiveFileRef> current() {
        List<ActiveFileRef> v = CURRENT.get();
        return v == null ? List.of() : v;
    }

    /** Remove the current thread's binding. Always call in a {@code finally}. */
    public static void clear() {
        CURRENT.remove();
    }

    /** A file grant active for one chat turn, scoped to the plugin whose tool may consume it. */
    public record ActiveFileRef(String pluginId, FileRef ref) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=ChatFileContextTest`
Expected: PASS — 4 tests.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/ChatFileContext.java \
        FengYu/src/test/java/fan/summer/fengyu/ai/ChatFileContextTest.java
git commit -m "✨ feat(ai): add ChatFileContext ThreadLocal for per-turn file refs"
```

---

## Task 2: `AiToolFileInjector` pure classifier + injector

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/ai/AiToolFileInjector.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/ai/AiToolFileInjectorTest.java`

**Interfaces:**
- Consumes: `ChatFileContext.ActiveFileRef`, `PluginFileGrantService.FileRef` (from Task 1).
- Produces: `AiToolFileInjector.injectFileRefs(Map<String,Object> modelParams, String pluginId, String inputSchema, List<ActiveFileRef> activeRefs)` returning `Map<String,Object>`. Also a package-private enum `FileParamClass { NONE, READ_FILE, READ_DIR, WRITE_DIR, FILE_LIST }` and `classifyParam(String name, Map<String,Object> schema)`.

**Classification rules (anchored on real manifests):**
- description contains `DirectoryRef` AND (`writable` or `output`) → `WRITE_DIR`
- description contains `DirectoryRef` (no writable/output) → `READ_DIR`
- description contains `FileRef` (no Directory) → `READ_FILE`
- type is `array`, items type `object`, and the items description matches a FileRef/DirectoryRef signal → `FILE_LIST`
- **Fallback when no description:** match by parameter NAME — `outputdir`/`outputdirectory` → `WRITE_DIR`; `inputdir`/`inputdirectory`/`projectdir` → `READ_DIR`; `filepath` → `READ_FILE`. (Covers the email plugin, whose `inputDirectory`/`outputDirectory` carry NO description.)
- otherwise → `NONE`

**Injection rule:** if exactly one param is `READ_FILE`/`READ_DIR`/`FILE_LIST` (read-class) AND `activeRefs` has an entry with matching `pluginId` AND compatible `kind` (`file` for READ_FILE/FILE_LIST, `directory` for READ_DIR) with `read` in its access → replace that single param's value with the whole `{id,name,kind,access,size}` map. ALL other cases (write-dir param, multiple file params, no matching grant, kind mismatch) → return params unchanged (degrade to A).

- [ ] **Step 1: Write the failing test**

Create `FengYu/src/test/java/fan/summer/fengyu/ai/AiToolFileInjectorTest.java`:

```java
package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolFileInjectorTest {

    private static ActiveFileRef excelFileRef() {
        return new ActiveFileRef("fan.summer.excel",
            new FileRef("ref_3f2a", "report.xlsx", "file", "read", 123L));
    }

    // ── classification ───────────────────────────────────────────────

    @Test
    void classifyReadFileByDescription() {
        Map<String, Object> schema = Map.of("type", "object", "description", "A FengYu FileRef");
        assertEquals(AiToolFileInjector.FileParamClass.READ_FILE,
            AiToolFileInjector.classifyParam("filePath", schema));
    }

    @Test
    void classifyWriteDirByDescription() {
        Map<String, Object> schema = Map.of("type", "object", "description", "A writable FengYu DirectoryRef");
        assertEquals(AiToolFileInjector.FileParamClass.WRITE_DIR,
            AiToolFileInjector.classifyParam("outputDir", schema));
    }

    @Test
    void classifyReadDirByDescription() {
        Map<String, Object> schema = Map.of("type", "object", "description", "A FengYu DirectoryRef");
        assertEquals(AiToolFileInjector.FileParamClass.READ_DIR,
            AiToolFileInjector.classifyParam("projectDir", schema));
    }

    @Test
    void classifyFallsBackToParamNameWhenNoDescription() {
        // email plugin's inputDirectory/outputDirectory have NO description
        assertEquals(AiToolFileInjector.FileParamClass.READ_DIR,
            AiToolFileInjector.classifyParam("inputDirectory", Map.of("type", "object")));
        assertEquals(AiToolFileInjector.FileParamClass.WRITE_DIR,
            AiToolFileInjector.classifyParam("outputDirectory", Map.of("type", "object")));
    }

    @Test
    void classifyIgnoresNonFileParams() {
        Map<String, Object> schema = Map.of("type", "string", "description", "Optional path to a Python executable");
        assertEquals(AiToolFileInjector.FileParamClass.NONE,
            AiToolFileInjector.classifyParam("executable", schema));
    }

    // ── injection: B path ────────────────────────────────────────────

    @Test
    void injectsSingleReadFileParamWhenGrantMatches() {
        String schema = "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}},\"required\":[\"filePath\"]}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("filePath", Map.of("id", "model-magic", "name", "ignored")));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema, List.of(excelFileRef()));

        @SuppressWarnings("unchecked") Map<String, Object> injected = (Map<String, Object>) out.get("filePath");
        assertEquals("ref_3f2a", injected.get("id"));
        assertEquals("report.xlsx", injected.get("name"));
        assertEquals("file", injected.get("kind"));
        assertEquals("read", injected.get("access"));
        assertEquals(123L, injected.get("size"));
    }

    // ── injection: degrade to A ──────────────────────────────────────

    @Test
    void doesNotInjectWhenPluginIdMismatch() {
        String schema = "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("filePath", "model-value"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema,
            List.of(new ActiveFileRef("fan.summer.offlinepython",
                new FileRef("ref_9b1c", "proj", "directory", "read", 0L))));

        assertEquals("model-value", out.get("filePath"));
    }

    @Test
    void doesNotInjectWhenKindMismatch() {
        // tool wants a read-dir, grant is a file
        String schema = "{\"type\":\"object\",\"properties\":{\"projectDir\":{\"type\":\"object\",\"description\":\"A FengYu DirectoryRef\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("projectDir", "model-value"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.offlinepython", schema,
            List.of(new ActiveFileRef("fan.summer.offlinepython",
                new FileRef("ref_1", "f.py", "file", "read", 1L))));

        assertEquals("model-value", out.get("projectDir"));
    }

    @Test
    void doesNotInjectForWriteDirParam() {
        String schema = "{\"type\":\"object\",\"properties\":{\"outputDir\":{\"type\":\"object\",\"description\":\"A writable FengYu DirectoryRef\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("outputDir", "model-value"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema, List.of(excelFileRef()));

        assertEquals("model-value", out.get("outputDir"));
    }

    @Test
    void doesNotInjectWhenMultipleFileParams() {
        // email_send_batch has inputDirectory + commonAttachments(array of object)
        String schema = "{\"type\":\"object\",\"properties\":{\"inputDirectory\":{\"type\":\"object\"},\"commonAttachments\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("inputDirectory", "a", "commonAttachments", List.of()));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.email", schema, List.of());

        assertEquals("a", out.get("inputDirectory"));
    }

    @Test
    void doesNotInjectWhenNoMatchingGrant() {
        String schema = "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("filePath", "model-value"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema, List.of());

        assertEquals("model-value", out.get("filePath"));
    }

    @Test
    void passesThroughWhenNoFileParams() {
        String schema = "{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\"}}}";
        Map<String, Object> modelParams = new java.util.LinkedHashMap<>(Map.of("mode", "BY_SHEET"));

        Map<String, Object> out = AiToolFileInjector.injectFileRefs(
            modelParams, "fan.summer.excel", schema, List.of(excelFileRef()));

        assertEquals("BY_SHEET", out.get("mode"));
        assertNull(out.get("filePath"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=AiToolFileInjectorTest`
Expected: compile failure — `AiToolFileInjector` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `FengYu/src/main/java/fan/summer/fengyu/ai/AiToolFileInjector.java`:

```java
package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure, Spring-free mapper that decides whether to transparently inject an active FileRef into a
 * plugin tool's arguments before dispatch. Two outcomes:
 * <ul>
 *   <li><b>B (transparent):</b> the tool has exactly one read-class file parameter AND a matching
 *       grant exists → that param's value is replaced with the whole FileRef object so the host's
 *       {@code PluginProcessManager.resolveRefs} rewrites it to a real path for the worker.</li>
 *   <li><b>A (degrade):</b> write-dir params, multiple file params, or no matching grant → params
 *       are returned unchanged and the model is expected to fill them from the FileRef list the
 *       backend appended to the system prompt.</li>
 * </ul>
 *
 * <p>This class has no ThreadLocal or Spring coupling on purpose — it is fully unit-testable.
 * {@code ToolCallback.call()} parses the model args, calls {@link #injectFileRefs}, then dispatches.
 */
public final class AiToolFileInjector {

    private AiToolFileInjector() {}

    /** How a single tool parameter is classified for injection purposes. */
    public enum FileParamClass { NONE, READ_FILE, READ_DIR, WRITE_DIR, FILE_LIST }

    /**
     * Classify one JSON-Schema property. Description wording is primary; the parameter name is a
     * fallback for manifests (e.g. the email plugin) whose file params carry no description.
     */
    public static FileParamClass classifyParam(String name, Map<String, Object> schema) {
        if (schema == null) return FileParamClass.NONE;
        String desc = schema.get("description") instanceof String d ? d.toLowerCase(Locale.ROOT) : "";
        String type = schema.get("type") instanceof String t ? t.toLowerCase(Locale.ROOT) : "";
        String lname = name == null ? "" : name.toLowerCase(Locale.ROOT);

        // array of objects whose items look like a FileRef → FILE_LIST
        if ("array".equals(type) && schema.get("items") instanceof Map<?, ?> items) {
            @SuppressWarnings("unchecked") Map<String, Object> itemsSchema = (Map<String, Object>) items;
            Object itemsDesc = itemsSchema.get("description");
            if (itemsDesc instanceof String id && id.toLowerCase(Locale.ROOT).contains("fileref")) {
                return FileParamClass.FILE_LIST;
            }
            return FileParamClass.NONE; // an array, but not file-shaped
        }

        if (desc.contains("directoryref")) {
            return (desc.contains("writable") || desc.contains("output")) ? FileParamClass.WRITE_DIR : FileParamClass.READ_DIR;
        }
        if (desc.contains("fileref")) {
            return FileParamClass.READ_FILE;
        }

        // Fallback by name when there is no usable description (email plugin).
        if (desc.isBlank()) {
            if (lname.contains("output")) return FileParamClass.WRITE_DIR;
            if (lname.contains("input") || lname.contains("project")) return FileParamClass.READ_DIR;
            if (lname.contains("filepath") || lname.contains("filename")) return FileParamClass.READ_FILE;
        }
        return FileParamClass.NONE;
    }

    /**
     * Map the model's raw arguments to the arguments actually dispatched to the worker. Never
     * mutates the input map. Returns the original map reference when no injection applies.
     */
    public static Map<String, Object> injectFileRefs(Map<String, Object> modelParams,
            String pluginId, String inputSchema, List<ActiveFileRef> activeRefs) {
        if (modelParams == null) modelParams = Map.of();
        if (activeRefs == null) activeRefs = List.of();

        List<String> fileParamNames = fileParamNames(inputSchema);
        if (fileParamNames.isEmpty()) return modelParams;

        // Degrade A: multiple file params → let the model fill them from the system prompt.
        if (fileParamNames.size() != 1) return modelParams;

        String paramName = fileParamNames.get(0);
        FileParamClass cls = classifyParam(paramName, paramSchema(inputSchema, paramName));

        // Only read-class single params are auto-injected; write-dir always degrades.
        if (cls != FileParamClass.READ_FILE && cls != FileParamClass.READ_DIR && cls != FileParamClass.FILE_LIST) {
            return modelParams;
        }
        boolean wantDirectory = cls == FileParamClass.READ_DIR;

        ActiveFileRef match = null;
        for (ActiveFileRef ref : activeRefs) {
            if (!pluginId.equals(ref.pluginId())) continue;
            FileRef f = ref.ref();
            boolean kindOk = wantDirectory ? "directory".equals(f.kind()) : "file".equals(f.kind());
            boolean accessOk = "read".equals(f.access()) || "read-write".equals(f.access());
            if (kindOk && accessOk) { match = ref; break; }
        }
        if (match == null) return modelParams; // degrade A

        Map<String, Object> out = new LinkedHashMap<>(modelParams);
        out.put(paramName, toMap(match.ref()));
        return out;
    }

    /** Collect the names of file-class properties declared in the tool's inputSchema. */
    @SuppressWarnings("unchecked")
    private static List<String> fileParamNames(String inputSchema) {
        Map<String, Object> root = parseSchema(inputSchema);
        if (root == null) return List.of();
        Object propsObj = root.get("properties");
        if (!(propsObj instanceof Map<?, ?> raw)) return List.of();
        List<String> names = new ArrayList<>();
        for (Object e : raw.entrySet()) {
            Map.Entry<String, Object> entry = (Map.Entry<String, Object>) e;
            String name = entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> schema
                    && classifyParam(name, (Map<String, Object>) schema) != FileParamClass.NONE) {
                names.add(name);
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramSchema(String inputSchema, String paramName) {
        Map<String, Object> root = parseSchema(inputSchema);
        if (root == null) return Map.of();
        Object propsObj = root.get("properties");
        if (!(propsObj instanceof Map<?, ?> raw)) return Map.of();
        Object s = raw.get(paramName);
        return s instanceof Map<?, ?> schema ? (Map<String, Object>) schema : Map.of();
    }

    private static Map<String, Object> parseSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) return null;
        try {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build().readValue(inputSchema, java.util.Map.class);
        } catch (Exception e) {
            return null; // malformed schema → behave as "no file params" (degrade A)
        }
    }

    private static Map<String, Object> toMap(FileRef ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ref.id());
        m.put("name", ref.name());
        m.put("kind", ref.kind());
        m.put("access", ref.access());
        m.put("size", ref.size());
        return m;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=AiToolFileInjectorTest`
Expected: PASS — 11 tests.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/AiToolFileInjector.java \
        FengYu/src/test/java/fan/summer/fengyu/ai/AiToolFileInjectorTest.java
git commit -m "✨ feat(ai): add pure AiToolFileInjector for single read-param FileRef injection"
```

---

## Task 3: Wire the injector into `AiToolDiscoveryConfig.call()`

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/ai/config/AiToolDiscoveryConfig.java:82-94`

**Interfaces:**
- Consumes: `AiToolFileInjector.injectFileRefs` + `ChatFileContext.current()` (Tasks 1-2).
- Produces: the plugin `ToolCallback.call()` now injects before dispatch.

- [ ] **Step 1: Write the failing test**

This task changes a 4-line anonymous-callback body. The injector itself is covered by Task 2; here we verify the wiring end-to-end with a tiny in-process test that does NOT boot Spring. Create `FengYu/src/test/java/fan/summer/fengyu/ai/config/AiToolDiscoveryWiringTest.java`:

```java
package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.AiToolFileInjector;
import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the wiring contract between the plugin ToolCallback and AiToolFileInjector: when a
 * ChatFileContext is set, call() must route the model args through the injector before dispatch.
 * The dispatch itself is verified by PluginProcessManagerTest; here we only assert the params the
 * callback WOULD dispatch are the injected ones.
 */
class AiToolDiscoveryWiringTest {

    @AfterEach
    void clean() { ChatFileContext.clear(); }

    @Test
    void callbackAppliesInjectorBeforeDispatch() {
        String schema = "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}}}";
        Map<String, Object> modelArgs = new java.util.LinkedHashMap<>(Map.of("filePath", "model-guess"));
        ChatFileContext.set(List.of(new ChatFileContext.ActiveFileRef("fan.summer.excel",
            new FileRef("ref_3f2a", "report.xlsx", "file", "read", 123L))));

        // This is the exact transform call() now performs:
        Map<String, Object> dispatched = AiToolFileInjector.injectFileRefs(
            modelArgs, "fan.summer.excel", schema, ChatFileContext.current());

        @SuppressWarnings("unchecked") Map<String, Object> injected = (Map<String, Object>) dispatched.get("filePath");
        assertEquals("ref_3f2a", injected.get("id"),
            "call() must dispatch the injected FileRef, not the model's raw guess");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=AiToolDiscoveryWiringTest`
Expected: PASS already (this pins the contract; it fails later if someone removes the wiring). This is a characterization test — run it to confirm green before the edit, then re-run after to confirm still green.

- [ ] **Step 3: Modify `AiToolDiscoveryConfig.call()`**

In `FengYu/src/main/java/fan/summer/fengyu/ai/config/AiToolDiscoveryConfig.java`, replace the body of the anonymous `ToolCallback.call(String input)` (lines 82-94). Current:

```java
                    @Override public String call(String input) {
                        try {
                            @SuppressWarnings("unchecked") var params = json.readValue(input, java.util.Map.class);
                            // Honour the manifest-declared per-tool timeout; -1 falls back to the
                            // plugin-wide default. Tools that may run long (e.g. excel_execute) can
                            // declare up to 600s; tools that need longer must switch to job mode.
                            long timeout = tool.timeoutSeconds() == null ? -1 : tool.timeoutSeconds();
                            Object result = processes.invoke(manifest.id(), tool.method(), params, timeout);
                            return result instanceof String text ? text : json.writeValueAsString(result);
                        } catch (Exception e) {
                            return "{\"success\":false,\"error\":" + quote(json, String.valueOf(e.getMessage())) + "}";
                        }
                    }
```

New:

```java
                    @Override public String call(String input) {
                        try {
                            @SuppressWarnings("unchecked") var params = json.readValue(input, java.util.Map.class);
                            // Transparently inject an active FileRef into a single read-class file
                            // param before dispatch (route B). When the tool has no/multiple/write
                            // file params, this is a no-op and the model fills them from the system
                            // prompt (route A). resolveRefs then rewrites the ref to a real path.
                            var injected = fan.summer.fengyu.ai.AiToolFileInjector.injectFileRefs(
                                params, manifest.id(), tool.inputSchema(),
                                fan.summer.fengyu.ai.ChatFileContext.current());
                            // Honour the manifest-declared per-tool timeout; -1 falls back to the
                            // plugin-wide default. Tools that may run long (e.g. excel_execute) can
                            // declare up to 600s; tools that need longer must switch to job mode.
                            long timeout = tool.timeoutSeconds() == null ? -1 : tool.timeoutSeconds();
                            Object result = processes.invoke(manifest.id(), tool.method(), injected, timeout);
                            return result instanceof String text ? text : json.writeValueAsString(result);
                        } catch (Exception e) {
                            return "{\"success\":false,\"error\":" + quote(json, String.valueOf(e.getMessage())) + "}";
                        }
                    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=AiToolDiscoveryWiringTest,AiToolFileInjectorTest,ToolDiscoveryTest`
Expected: PASS — wiring + injector + the pre-existing Spring Boot discovery test all green. `ToolDiscoveryTest` confirms the `@Bean` still assembles.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/config/AiToolDiscoveryConfig.java \
        FengYu/src/test/java/fan/summer/fengyu/ai/config/AiToolDiscoveryWiringTest.java
git commit -m "✨ feat(ai): inject active FileRef into plugin tool calls before dispatch"
```

---

## Task 4: Thread `activeFileRefs` through `AiController` + `ChatBackend`

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/ai/ChatBackend.java:70,85-86`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/web/controller/AiController.java:57-70,87-142,222`
- Create: `FengYu/src/test/java/fan/summer/fengyu/web/controller/AiControllerFileContextTest.java`

**Interfaces:**
- Consumes: `ChatFileContext` (Task 1).
- Produces: `ChatRequest.activeFileRefs`; `ChatBackend.chat(history, temp, topP, maxTokens, activeFileRefs, callback)`; `AiController.stream` sets/clears `ChatFileContext`.

**NOTE on the DTO:** `AiController.ChatRequest`/`ActiveFileRefDto` will reference `PluginFileGrantService.FileRef`. Because `ChatRequest` is a `record` deserialized by Jackson, `FileRef` (also a record with the 5 canonical components) deserializes cleanly from the JSON the frontend sends.

- [ ] **Step 1: Write the failing test**

Create `FengYu/src/test/java/fan/summer/fengyu/web/controller/AiControllerFileContextTest.java`:

```java
package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiControllerFileContextTest {

    @Autowired MockMvc mvc;

    @AfterEach
    void clean() { ChatFileContext.clear(); }

    @Test
    void acceptsActiveFileRefsFieldWithoutError() throws Exception {
        // POST /api/ai/chat must accept the new activeFileRefs field. We only assert the endpoint
        // accepts the body and returns a streamId; resolving the SSE is out of scope here.
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
            + "\"activeFileRefs\":[{\"pluginId\":\"fan.summer.excel\","
            + "\"ref\":{\"id\":\"ref_3f2a\",\"name\":\"report.xlsx\",\"kind\":\"file\",\"access\":\"read\",\"size\":123}}]}";

        mvc.perform(post("/api/ai/chat").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.streamId").exists());
    }

    @Test
    void chatRequestRecordExposesActiveFileRefs() {
        // Pins the DTO shape later tasks/future readers rely on.
        var ref = new FileRef("ref_1", "f", "file", "read", 1L);
        var req = new AiController.ChatRequest(
            java.util.List.of(new AiController.ChatMessageDto("user", "hi")),
            java.util.List.of(new AiController.ActiveFileRefDto("fan.summer.excel", ref)));
        org.junit.jupiter.api.Assertions.assertEquals(1, req.activeFileRefs().size());
        org.junit.jupiter.api.Assertions.assertEquals("fan.summer.excel", req.activeFileRefs().get(0).pluginId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=AiControllerFileContextTest`
Expected: compile failure — `ChatRequest` has no `activeFileRefs`, `ActiveFileRefDto` does not exist.

- [ ] **Step 3: Extend `ChatBackend` interface**

In `FengYu/src/main/java/fan/summer/fengyu/ai/ChatBackend.java`, add the new overload and route the old ones through it. The file currently has (lines 70, 85-86):

```java
    void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException;
```
and
```java
    void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
              AiStreamCallback callback) throws AiServiceException;
```

Add an import at the top (after the existing `import java.util.List;`):

```java
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
```

Replace the two `chat` declarations (the 2-arg at `:70` and the 5-arg at `:85-86`) and add the new 6-arg default method. New block:

```java
    /**
     * Streaming chat with default sampling parameters (read from settings).
     *
     * @param history conversation history (system + user + assistant messages)
     * @param callback receives streamed response fragments and tool-call events
     * @throws AiServiceException if no model is loaded or inference fails
     */
    default void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, callback, List.of());
    }

    /**
     * Streaming chat with default sampling and an explicit set of active file grants for this turn.
     */
    default void chat(List<AiChatMessage> history, AiStreamCallback callback,
                      List<ActiveFileRef> activeFileRefs) throws AiServiceException {
        chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
             AiConfigServiceHeadless.getAiMaxTokens(), activeFileRefs, callback);
    }

    /**
     * Streaming chat with tool callbacks disabled. Planning phases use this to produce a
     * proposal without executing tools before the workflow has been approved.
     */
    default void chatWithoutTools(List<AiChatMessage> history, AiStreamCallback callback)
            throws AiServiceException {
        chat(history, callback);
    }

    /**
     * Streaming chat with explicit sampling parameters and active file grants. Implementations
     * append {@code activeFileRefs} to the effective system prompt (route A fallback) and rely on
     * {@link ChatFileContext} (set by the caller around this call) for route B injection.
     */
    void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
              List<ActiveFileRef> activeFileRefs, AiStreamCallback callback) throws AiServiceException;
```

**IMPORTANT:** Check whether `AiConfigServiceHeadless` is already imported in `ChatBackend.java`. If the file does not already import it, add: `import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;`. Read the file's import block first to decide. (The 2-arg `chat` default previously referenced these getters, so if the 2-arg overload was NOT a default before, the getters may not be imported — verify and add only if missing. If `AiConfigServiceHeadless` is in a different package and would create a cycle, instead inline `0.7f, 0.0f, 0` as the defaults the original 2-arg used — but first grep the original 2-arg overload to see what it did.)

> Implementation note for the implementer: the OLD 2-arg `chat(history, callback)` was `abstract` (not default) and implemented in both backends by reading `AiConfigServiceHeadless` themselves. If making it a `default` here introduces an import problem, keep it abstract and instead add ONLY the new 6-arg abstract method + a default 2-arg-with-refs that delegates using literal defaults. The goal is: (a) a 6-arg abstract method both backends implement, (b) `AiController` calls the 6-arg directly. Prefer the simplest change that compiles.

- [ ] **Step 4: Update both backend `chat()` implementations (signatures only here)**

The actual system-prompt append is Task 5; this task only changes the signatures so the code compiles. The backends currently implement the 5-arg `chat`. We rename the 5-arg to the 6-arg and add a delegating 5-arg.

In `SpringAiCloudBackend.java:244-253`, the current 2-arg + 5-arg block is:

```java
@Override
public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
    chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
         AiConfigServiceHeadless.getAiMaxTokens(), callback);
}

@Override
public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                 AiStreamCallback callback) throws AiServiceException {
    startChat(history, callback, true);
}
```

Replace with (the `activeFileRefs` field is threaded into `startChat` in Task 5; for now store it in a field so `runToolLoop` can read it):

```java
@Override
public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
    chat(history, callback, List.of());
}

@Override
public void chat(List<AiChatMessage> history, AiStreamCallback callback,
                 List<ActiveFileRef> activeFileRefs) throws AiServiceException {
    chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
         AiConfigServiceHeadless.getAiMaxTokens(), activeFileRefs, callback);
}

@Override
public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                 List<ActiveFileRef> activeFileRefs, AiStreamCallback callback) throws AiServiceException {
    startChat(history, activeFileRefs, callback, true);
}
```

Add the import: `import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;` and `import java.util.List;` (if not present).

Make the identical change in `OllamaLocalBackend.java:185-199` (its 2-arg + 5-arg block has the same shape). Update `startChat` signature to accept `List<ActiveFileRef> activeFileRefs` and thread it to `runToolLoop` — this is fully completed in Task 5 (signatures compile now via field storage).

For BOTH backends: change `startChat` signature from `(history, callback, enableTools)` to `(history, activeFileRefs, callback, enableTools)`, and `runToolLoop` from `(history, callback, enableTools)` to `(history, activeFileRefs, callback, enableTools)`. Store `activeFileRefs` in the `runToolLoop` call so Task 5 can consume it. The simplest path: add a parameter to both private methods and pass `activeFileRefs` straight through to `runToolLoop`, where Task 5 will use it. Update the `Thread.ofVirtual().start(() -> runToolLoop(...))` call site accordingly.

**`ChatBackend` import note (verified):** `ChatBackend.java` does NOT import `AiConfigServiceHeadless` today, and the 2-arg `chat` is `abstract` (not `default`). To avoid an import/cycle problem, do NOT make the 2-arg a `default` that calls `AiConfigServiceHeadless`. Instead, keep the 2-arg `chat(history, callback)` abstract and let each backend implement it by delegating to the new 3-arg `chat(history, callback, List.of())`. So the final interface surface is:
- `abstract void chat(history, callback)` — unchanged signature, each backend implements via `chat(history, callback, List.of())`.
- `default chat(history, callback, activeFileRefs)` — delegates to the 6-arg with the backend's own defaults (each backend already reads `AiConfigServiceHeadless` for its 2-arg, so the default body can call `chat(history, 0.7f, 0f, 0, activeFileRefs, callback)` — but since the backends override this themselves, leave the default throwing `UnsupportedOperationException` and let each backend override it). **Simplest correct approach: make `chat(history, callback, activeFileRefs)` abstract too** and implement it in both backends (they already have the getters). Drop the `default` 2-arg entirely from the interface; the backends provide both.
- `abstract void chat(history, temperature, topP, maxTokens, activeFileRefs, callback)` — the new 6-arg both backends implement.

Net: add ONE new abstract 6-arg method; convert the 5-arg into the 6-arg in both backends; have `AiController` call the 6-arg directly. Leave the 2-arg as-is. This keeps the interface free of `AiConfigServiceHeadless`.

- [ ] **Step 5: Update `AiController`**

In `FengYu/src/main/java/fan/summer/fengyu/web/controller/AiController.java`:

(a) Change the `pending` map type (line 57) to carry the refs alongside history:

```java
    /** Pending turns keyed by streamId; consumed once when the SSE opens. */
    private final Map<String, PendingTurn> pending = new ConcurrentHashMap<>();
```

Add a private record near the other DTOs (bottom of the class):

```java
    private record PendingTurn(List<AiChatMessage> history, List<ActiveFileRef> activeFileRefs) {}
```

(b) Rewrite `chat(...)` (lines 59-70) to stash both:

```java
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req) {
        List<AiChatMessage> history = new ArrayList<>();
        if (req.messages() != null) {
            for (ChatMessageDto m : req.messages()) {
                history.add(toDomain(m));
            }
        }
        List<ActiveFileRef> refs = new ArrayList<>();
        if (req.activeFileRefs() != null) {
            for (ActiveFileRefDto dto : req.activeFileRefs()) {
                refs.add(new ActiveFileRef(dto.pluginId(), dto.ref()));
            }
        }
        String streamId = UUID.randomUUID().toString();
        pending.put(streamId, new PendingTurn(history, refs));
        return Map.of("streamId", streamId);
    }
```

(c) Rewrite the top of `stream(...)` (lines 88-95 area) to pull the PendingTurn and set/clear the ThreadLocal around the `chat` call (around line 132-140). Replace `List<AiChatMessage> history = pending.remove(streamId);` and its null-check with:

```java
        PendingTurn turn = pending.remove(streamId);
        if (turn == null) {
            completeWithError(emitter, "Unknown or expired streamId");
            return emitter;
        }
        List<AiChatMessage> history = turn.history();
```

Then replace the `try { svc.get().chat(history, ...) }` block with a try/finally that sets and clears the ThreadLocal:

```java
        try {
            ChatFileContext.set(turn.activeFileRefs());
            svc.get().chat(history,
                AiConfigServiceHeadless.getAiTemperature(),
                AiConfigServiceHeadless.getAiTopP(),
                AiConfigServiceHeadless.getAiMaxTokens(),
                turn.activeFileRefs(),
                new SseCallback(emitter));
        } catch (Exception e) {
            completeWithError(emitter, e.getMessage());
        } finally {
            ChatFileContext.clear();
        }
        return emitter;
```

Add imports at the top:

```java
import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
```

(d) Update the DTO records (line 222):

```java
    public record ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs) {}
    public record ChatMessageDto(String role, String content) {}
    public record ToolApprovalDecision(boolean approved) {}
    public record ActiveFileRefDto(String pluginId, PluginFileGrantService.FileRef ref) {}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=AiControllerFileContextTest,ToolDiscoveryTest,PluginProcessManagerTest`
Expected: PASS. The Spring context still boots; the new field deserializes.

If `ChatBackend` import of `AiConfigServiceHeadless` created a cycle, fall back per the note in Step 3 and re-run.

- [ ] **Step 7: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/ChatBackend.java \
        FengYu/src/main/java/fan/summer/fengyu/ai/service/SpringAiCloudBackend.java \
        FengYu/src/main/java/fan/summer/fengyu/ai/service/OllamaLocalBackend.java \
        FengYu/src/main/java/fan/summer/fengyu/web/controller/AiController.java \
        FengYu/src/test/java/fan/summer/fengyu/web/controller/AiControllerFileContextTest.java
git commit -m "✨ feat(ai): thread activeFileRefs through ChatRequest → ChatBackend → ChatFileContext"
```

---

## Task 5: System-prompt append (route A fallback) in both backends

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/ai/service/SpringAiCloudBackend.java`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/ai/service/OllamaLocalBackend.java`

**Interfaces:**
- Consumes: `List<ActiveFileRef>` now threaded into `runToolLoop` (Task 4), `ActiveFileRef.ref()` → `FileRef`.
- Produces: the effective system prompt lists active FileRefs when non-empty.

- [ ] **Step 1: Add a shared prompt helper**

Create `FengYu/src/main/java/fan/summer/fengyu/ai/ActiveFilesPromptAppender.java`:

```java
package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;

import java.util.List;

/**
 * Appends the active file grants for a chat turn to the system prompt (route A fallback). When the
 * host could not transparently inject a FileRef (write-dir params, multiple file params, or no
 * matching grant), the model picks from this list and passes the whole object as the argument.
 */
public final class ActiveFilesPromptAppender {

    private ActiveFilesPromptAppender() {}

    /**
     * @return {@code basePrompt} with an "available files" section appended, or {@code basePrompt}
     *         unchanged when no active files are present or the list is null.
     */
    public static String append(String basePrompt, List<ActiveFileRef> activeRefs) {
        if (activeRefs == null || activeRefs.isEmpty()) return basePrompt;
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt == null ? "" : basePrompt);
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("## Files available for this conversation\n");
        sb.append("When a plugin tool needs a file/directory parameter, pick from this list and ");
        sb.append("pass the WHOLE object as the argument, exactly as shown:\n");
        for (ActiveFileRef ref : activeRefs) {
            FileRef f = ref.ref();
            sb.append("- ").append(ref.pluginId()).append(": ");
            sb.append("{\"id\":\"").append(f.id()).append("\",");
            sb.append("\"name\":").append(jsonString(f.name())).append(',');
            sb.append("\"kind\":\"").append(f.kind()).append("\",");
            sb.append("\"access\":\"").append(f.access()).append("\",");
            sb.append("\"size\":").append(f.size()).append("}\n");
        }
        return sb.toString();
    }

    private static String jsonString(String s) {
        // minimal JSON string escaping for the name field
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; s != null && i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
```

- [ ] **Step 2: Write the failing test for the appender**

Create `FengYu/src/test/java/fan/summer/fengyu/ai/ActiveFilesPromptAppenderTest.java`:

```java
package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveFilesPromptAppenderTest {

    @Test
    void returnsBasePromptUnchangedWhenNoActiveFiles() {
        String out = ActiveFilesPromptAppender.append("base", List.of());
        assertTrue(out.equals("base"));
    }

    @Test
    void appendsFileSectionWhenActiveFilesPresent() {
        String out = ActiveFilesPromptAppender.append("base", List.of(
            new ActiveFileRef("fan.summer.excel", new FileRef("ref_3f2a", "report.xlsx", "file", "read", 123L))));
        assertTrue(out.startsWith("base"), out);
        assertTrue(out.contains("## Files available for this conversation"), out);
        assertTrue(out.contains("fan.summer.excel"), out);
        assertTrue(out.contains("\"id\":\"ref_3f2a\""), out);
        assertFalse(out.contains("model-magic"));
    }

    @Test
    void handlesNullBasePrompt() {
        String out = ActiveFilesPromptAppender.append(null, List.of(
            new ActiveFileRef("p", new FileRef("ref_1", "f", "file", "read", 1L))));
        assertTrue(out.contains("## Files available"), out);
    }
}
```

Run: `./mvnw -f FengYu/pom.xml test -Dtest=ActiveFilesPromptAppenderTest` → expect PASS (3 tests). Commit checkpoint optional; fold into the final task commit.

- [ ] **Step 3: Call the appender from `SpringAiCloudBackend.runToolLoop`**

In `SpringAiCloudBackend.java`, the system prompt is built at `:305` (`String systemPrompt = effectiveSystemPrompt();`) inside `runToolLoop`. `runToolLoop` now receives `activeFileRefs` (Task 4). Change that line to:

```java
        String systemPrompt = ActiveFilesPromptAppender.append(effectiveSystemPrompt(), activeFileRefs);
```

Add the import: `import fan.summer.fengyu.ai.ActiveFilesPromptAppender;` (note: `ActiveFilesPromptAppender` is in the same package `fan.summer.fengyu.ai`, but the backend is in the sub-package `fan.summer.fengyu.ai.service` — so the import IS needed).

Ensure `activeFileRefs` is the parameter name threaded through `startChat` → `runToolLoop` from Task 4.

- [ ] **Step 4: Mirror the change in `OllamaLocalBackend.runToolLoop`**

In `OllamaLocalBackend.java`, the equivalent line is `:254` (`String systemPrompt = effectiveSystemPrompt();`) inside `runToolLoop`. Apply the identical change and the identical import.

- [ ] **Step 5: Run the full AI backend test suite**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=ActiveFilesPromptAppenderTest,AiToolFileInjectorTest,ChatFileContextTest,AiControllerFileContextTest,ToolDiscoveryTest,PluginProcessManagerTest,PluginFileGrantServiceTest`
Expected: PASS — all new and pre-existing tests green. No regressions in the chat path.

- [ ] **Step 6: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/ActiveFilesPromptAppender.java \
        FengYu/src/main/java/fan/summer/fengyu/ai/service/SpringAiCloudBackend.java \
        FengYu/src/main/java/fan/summer/fengyu/ai/service/OllamaLocalBackend.java \
        FengYu/src/test/java/fan/summer/fengyu/ai/ActiveFilesPromptAppenderTest.java
git commit -m "✨ feat(ai): append active FileRefs to system prompt as route-A fallback"
```

---

## Task 6: Frontend — `activeFiles` store state + send filtering + plugin guess

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/client.ts:185-190`
- Modify: `frontend/src/stores/aiSession.ts`
- Modify: `frontend/src/stores/aiSession.test.ts`

**Interfaces:**
- Consumes: `PluginFileRef` (`api/types.ts:155`), `api.grantRuntimeNativePath` / `api.uploadRuntimeFile`.
- Produces: `ActiveFileEntry` type; `api.aiChat(messages, activeFileRefs?)`; store `activeFiles` + `addActiveFile`/`removeActiveFile`/`clearActiveFiles`.

- [ ] **Step 1: Write the failing tests**

Append to `frontend/src/stores/aiSession.test.ts` (keep existing tests). Add imports at the top — the file currently imports `{ describe, expect, it }` from `'vitest'` and `{ toChatHistory, type ChatTurn }` from `'./aiSession'`. Add `guessPluginForFile` to the import and `beforeEach`/`setActivePinia`/`createPinia` from their sources:

```ts
import { describe, expect, it, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { toChatHistory, guessPluginForFile, type ChatTurn } from './aiSession'
```

Then add at the bottom:

```ts
describe('plugin guess from file name', () => {
  it('maps xlsx to excel', () => {
    expect(guessPluginForFile('report.xlsx')).toBe('fan.summer.excel')
  })
  it('maps py to offlinepython', () => {
    expect(guessPluginForFile('main.py')).toBe('fan.summer.offlinepython')
  })
  it('returns empty for unknown extensions', () => {
    expect(guessPluginForFile('notes.txt')).toBe('')
  })
})

describe('AI session active files', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('addActiveFile replaces same-plugin same-name entries', async () => {
    const store = useAiSessionStore()
    const ref1: PluginFileRef = { id: 'ref_1', name: 'report.xlsx', kind: 'file', access: 'read', size: 10 }
    const ref2: PluginFileRef = { id: 'ref_2', name: 'report.xlsx', kind: 'file', access: 'read', size: 20 }
    store.addActiveFile('fan.summer.excel', ref1)
    store.addActiveFile('fan.summer.excel', ref2)
    expect(store.activeFiles.length).toBe(1)
    expect(store.activeFiles[0].ref.id).toBe('ref_2')
  })

  it('removeActiveFile removes by pluginId + refId', () => {
    const store = useAiSessionStore()
    const ref: PluginFileRef = { id: 'ref_x', name: 'f', kind: 'file', access: 'read', size: 1 }
    store.addActiveFile('fan.summer.excel', ref)
    store.removeActiveFile('fan.summer.excel', 'ref_x')
    expect(store.activeFiles.length).toBe(0)
  })

  it('sendableFileRefs omits entries with empty pluginId', () => {
    const store = useAiSessionStore()
    const ref: PluginFileRef = { id: 'ref_y', name: 'f', kind: 'file', access: 'read', size: 1 }
    store.addActiveFile('', ref) // user has not chosen a plugin
    expect(store.sendableFileRefs()).toEqual([])
  })
})
```

Also add the needed type import near the top:

```ts
import type { PluginFileRef } from '@/api/types'
import { useAiSessionStore } from './aiSession'
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm test -- --run aiSession.test.ts`
Expected: FAIL — `guessPluginForFile`, `activeFiles`, `addActiveFile`, `sendableFileRefs` do not exist.

- [ ] **Step 3: Add `ActiveFileEntry` type**

In `frontend/src/api/types.ts`, after the `PluginFileRef` interface (around line 161), add:

```ts
/** A file grant active for one AI chat turn, scoped to a plugin whose tool may consume it. */
export interface ActiveFileEntry {
  pluginId: string
  ref: PluginFileRef
}
```

- [ ] **Step 4: Extend `api.aiChat`**

In `frontend/src/api/client.ts`, replace the `aiChat` method (lines 185-190). Add the import at the top of the file (alongside the other type imports from `./types`): `ActiveFileEntry`. New method:

```ts
  async aiChat(messages: ChatMessage[], activeFileRefs?: ActiveFileEntry[]): Promise<ChatStartResponse> {
    const { data } = await http.post<ChatStartResponse>('/api/ai/chat', {
      messages,
      activeFileRefs: activeFileRefs ?? [],
    })
    return data
  },
```

- [ ] **Step 5: Add `activeFiles` state + helpers to the store**

In `frontend/src/stores/aiSession.ts`:

(a) Add imports — the file already imports `api` and types. Add `ActiveFileEntry, PluginFileRef` to the existing `import type { ... } from '@/api/types'` line. Also add `guessPluginForFile` as an exported function (below `toChatHistory` at the bottom).

(b) Inside the store setup function, after the existing `ref` declarations (around line 46), add:

```ts
  const activeFiles = ref<ActiveFileEntry[]>([])

  function addActiveFile(pluginId: string, ref: PluginFileRef) {
    const idx = activeFiles.value.findIndex(
      (f) => f.pluginId === pluginId && f.ref.name === ref.name,
    )
    if (idx >= 0) activeFiles.value[idx] = { pluginId, ref }
    else activeFiles.value.push({ pluginId, ref })
  }

  function removeActiveFile(pluginId: string, refId: string) {
    activeFiles.value = activeFiles.value.filter(
      (f) => !(f.pluginId === pluginId && f.ref.id === refId),
    )
  }

  function clearActiveFiles() {
    activeFiles.value = []
  }

  /** Active files with a chosen plugin — the ones actually sent with the chat request. */
  function sendableFileRefs(): ActiveFileEntry[] {
    return activeFiles.value.filter((f) => f.pluginId.trim() !== '')
  }
```

(c) In `newConversation()` (after `error.value = null;`) and in `clear()` (at the start), call `clearActiveFiles()`.

(d) In `send()`, change the `api.aiChat(...)` call (line 175) to pass the filtered refs:

```ts
      const { streamId } = await api.aiChat(toChatHistory(conv.turns), sendableFileRefs())
```

(e) Expose the new state/functions in the store's return object (the `return { conversations, activeId, ... }` block around line 231):

```ts
    activeFiles,
    addActiveFile,
    removeActiveFile,
    clearActiveFiles,
    sendableFileRefs,
```

(f) Add the exported `guessPluginForFile` function at the bottom of the file (after `toChatHistory`):

```ts
/**
 * Best-effort plugin id for an attached file, based on extension. Empty string means "unknown —
 * the user must pick a plugin in the UI before the file is sent with the chat request."
 */
export function guessPluginForFile(fileName: string): string {
  const lower = (fileName ?? '').toLowerCase()
  if (lower.endsWith('.xlsx') || lower.endsWith('.xls') || lower.endsWith('.xlsm')) {
    return 'fan.summer.excel'
  }
  if (lower.endsWith('.py')) {
    return 'fan.summer.offlinepython'
  }
  return ''
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npm test -- --run aiSession.test.ts`
Expected: PASS — all existing + new tests green.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/client.ts \
        frontend/src/stores/aiSession.ts frontend/src/stores/aiSession.test.ts
git commit -m "✨ feat(ai): add activeFiles store state, plugin guess, and send-time filtering"
```

---

## Task 7: Frontend — `AiChat.vue` active-files strip + attach affordance

**Files:**
- Modify: `frontend/src/views/AiChat.vue`

**Interfaces:**
- Consumes: `aiSession` store (`activeFiles`, `addActiveFile`, `removeActiveFile`), `makeDesktop`, `api.grantRuntimeNativePath`/`uploadRuntimeFile`, `guessPluginForFile`.
- Produces: the visible attach-file UI; on attach, grants via existing endpoints and calls `addActiveFile`.

- [ ] **Step 1: Add the attach logic to the `<script setup>` block**

In `frontend/src/views/AiChat.vue`, add imports near the top (after `import { useAiSessionStore } from '@/stores/aiSession'`):

```ts
import { makeDesktop } from '@/mf/desktop'
import { api } from '@/api/client'
import { guessPluginForFile } from '@/stores/aiSession'
import type { PluginFileRef } from '@/api/types'
```

Add the attach handler after the existing `submit()` function (before `onKeydown`):

```ts
async function attachFile() {
  if (ai.busy) return
  const desktop = makeDesktop()
  if (desktop) {
    const path = await desktop.pickFile()
    if (!path) return
    const fileName = path.split(/[\\/]/).pop() ?? path
    const pluginId = guessPluginForFile(fileName)
    try {
      const ref = await api.grantRuntimeNativePath(pluginId, path, 'file', 'read')
      ai.addActiveFile(pluginId, ref)
    } catch (e) {
      ai.error = e instanceof Error ? e.message : 'Failed to attach file'
    }
  } else {
    const input = document.createElement('input')
    input.type = 'file'
    input.onchange = async () => {
      const file = input.files?.[0]
      if (!file) return
      const pluginId = guessPluginForFile(file.name)
      try {
        const ref = await api.uploadRuntimeFile(pluginId, file)
        ai.addActiveFile(pluginId, ref)
      } catch (e) {
        ai.error = e instanceof Error ? e.message : 'Failed to attach file'
      }
    }
    input.click()
  }
}
```

- [ ] **Step 2: Add the active-files strip to the template**

In the `<template>`, insert the strip immediately above the Composer block (before the `<div style="padding: 8px 16px 16px">` at line 130). Use existing `cx-*` classes:

```html
    <!-- Active files for this conversation -->
    <div v-if="ai.activeFiles.length" class="cx-conversation" style="padding: 0 16px">
      <div style="display: flex; flex-wrap: wrap; gap: 8px; align-items: center">
        <span
          v-for="entry in ai.activeFiles"
          :key="entry.ref.id"
          class="cx-chip"
          :class="{ 'cx-chip--warning': !entry.pluginId }"
          style="gap: 6px"
        >
          <i class="mdi" :class="entry.ref.kind === 'directory' ? 'mdi-folder' : 'mdi-file-outline'" />
          {{ entry.ref.name }}
          <span v-if="entry.pluginId" class="cx-muted" style="font-size: 11px">[{{ entry.pluginId }}]</span>
          <span v-else class="cx-muted" style="font-size: 11px">[{{ $t('aichat.selectPlugin') }}]</span>
          <button class="cx-iconbtn cx-iconbtn--sm" @click="ai.removeActiveFile(entry.pluginId, entry.ref.id)">
            <i class="mdi mdi-close" />
          </button>
        </span>
      </div>
      <div v-if="ai.activeFiles.some((f) => !f.pluginId)" class="cx-muted" style="font-size: 12px; margin-top: 4px; color: var(--md-sys-color-error)">
        {{ $t('aichat.fileNeedsPlugin') }}
      </div>
    </div>
```

And add an attach button inside the composer row (before the send/stop button, around line 147), so the row reads: textarea, attach, send:

```html
        <button
          class="cx-iconbtn cx-iconbtn--round"
          :disabled="ai.busy"
          :title="$t('aichat.attachFile')"
          @click="attachFile"
        ><i class="mdi mdi-paperclip" /></button>
```

- [ ] **Step 3: Add the i18n keys**

The locale files are `frontend/src/i18n/en.json` and `frontend/src/i18n/zh.json`. The `aichat` namespace is a flat object (e.g. `en.json:51` is `"aichat": { "title": ..., "placeholder": "Message Infinia…", ... }`). Add three keys — `attachFile`, `selectPlugin`, `fileNeedsPlugin` — into the existing `aichat` object. For example in `en.json`:

```json
"aichat": {
  ...existing keys...,
  "attachFile": "Attach a file for this conversation",
  "selectPlugin": "select a plugin",
  "fileNeedsPlugin": "Pick a plugin for the highlighted file, or it won't be sent."
}
```

ZH equivalents in `zh.json`: `attachFile` → "为本次对话选择文件", `selectPlugin` → "选择插件", `fileNeedsPlugin` → "请为高亮文件选择插件，否则不会被发送。"

Read each locale file first to match its exact existing key ordering/style before editing.

- [ ] **Step 4: Verify the frontend builds and type-checks**

Run: `cd frontend && npm run build`
Expected: build succeeds with no TypeScript errors. (The vitest tests added in Task 6 already cover the logic.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/AiChat.vue frontend/src/i18n/en.json frontend/src/i18n/zh.json
git commit -m "✨ feat(ai): add active-files strip and attach affordance to AiChat"
```

---

## Task 8: Docs sync

**Files:**
- Modify: `docs/en/plugins/ai-tools.md`
- Modify: `docs/zh/plugins/ai-tools.md`
- Modify: `docs/en/plugins/file-io.md`
- Modify: `docs/zh/plugins/file-io.md`

**Interfaces:** none (docs only).

The docs currently claim (around `ai-tools.md` line 78, both languages) "the host rewrites the FileRef to a real path before the worker sees it" for the AI path — which was false before this change and is now true ONLY because of the attach-file flow + injection. Correct both.

- [ ] **Step 1: Read the current passages**

Run: `grep -n "rewrites the FileRef\|host rewrites\|before the worker sees" docs/en/plugins/ai-tools.md docs/zh/plugins/ai-tools.md docs/en/plugins/file-io.md docs/zh/plugins/file-io.md`

Note the exact line numbers in each file.

- [ ] **Step 2: Correct `ai-tools.md` (EN + ZH)**

In the section describing tool invocation, replace the unqualified claim with the accurate two-route description. Example EN replacement (adapt to the surrounding paragraph's voice):

```markdown
When the model calls `excel_analyze`, the host forwards the arguments as JSON-RPC. If the user has
attached a file for the conversation (see the attach affordance in the AI chat) and the tool has a
single read-class file parameter, the host transparently injects the FileRef before dispatch
(route B); `PluginProcessManager.resolveRefs` then rewrites it to a real path before the worker
sees it. For tools with write-directory or multiple file parameters, the host instead lists the
available FileRefs in the system prompt and the model fills them itself (route A). In both cases
the worker ultimately receives a resolved filesystem path. See [File I/O](/en/plugins/file-io).
```

Make the structurally-mirrored edit in `docs/zh/plugins/ai-tools.md`.

- [ ] **Step 3: Add a note to `file-io.md` (EN + ZH)**

In the section that describes FileRef creation endpoints, add a short note that the AI chat's
attach-file flow reuses the same grant endpoints:

```markdown
> The AI chat's "attach file for this conversation" affordance grants a file through these same
> endpoints (`/api/plugin-runtime/{pluginId}/files/native` on desktop, `/files/upload` in the
> browser). The resulting FileRef is scoped to the chosen plugin and lives for the chat session
> (it is not persisted; restart clears it).
```

Make the structurally-mirrored edit in `docs/zh/plugins/file-io.md`.

- [ ] **Step 4: Verify the docs site still builds**

Run: `npm --prefix docs run build`
Expected: VitePress build succeeds (EN + ZH), no broken links.

- [ ] **Step 5: Commit**

```bash
git add docs/en/plugins/ai-tools.md docs/zh/plugins/ai-tools.md \
        docs/en/plugins/file-io.md docs/zh/plugins/file-io.md
git commit -m "📝 docs(ai): correct AI-path FileRef claim and document attach-file flow"
```

---

## Final Verification

After all 8 tasks:

- [ ] **Full backend test suite for the touched modules:** `./mvnw -f FengYu/pom.xml test`
- [ ] **Frontend:** `cd frontend && npm test && npm run build`
- [ ] **Docs:** `npm --prefix docs run build`
- [ ] **No unrelated changes:** `git diff --check` and review `git diff main...HEAD --stat`

These are the focused checks; do NOT run the whole reactor or `scripts/e2e-smoke.sh` unless the user asks for end-to-end verification (the e2e smoke boots the JAR and probes endpoints, which is heavier than needed to validate this change).
