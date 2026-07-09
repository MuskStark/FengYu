# AI Flow Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the ZhiFlow plugin system into a breaking-change 4.0.0 architecture where every plugin is an independent UI-bearing tool, AI capability is an optional Spring AI 2.0-native layer, plugins declare category + source, the host is fully i18n'd with plugins following the host language, and a Plan-and-Execute Agent runtime is added.

**Architecture:** Delete the hand-rolled `AiTool`/`AiServiceProvider`/`ToolExecutor` tool layer and the dead v1 `fan.summer.api.*` package. Plugins implement a new `ZhiFlowPlugin` contract (descriptor + invoke RPC + mandatory ESM UI); AI tools are plain Spring AI `@Tool`/`ToolCallback` beans. The deprecated `ChatModel` internal tool-execution is replaced by `ChatClient` + `ToolCallingManager`. A new `AgentRunner` does Plan-and-Execute. Frontend gains vue-i18n; the host is the single language source and injects locale into plugin micro-frontends.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring AI 2.0.0 (`spring-ai-openai`/`-anthropic`/`-ollama`), Spring Data JPA; Vue 3.5.39 + TypeScript 5.7 + Vite 6 + Pinia 2 + vue-router 4 + vue-i18n 10.

## Global Constraints

- **4.0.0 BREAKING update — no 3.x compatibility.** No compat shims, no deprecated constructors kept "for now". Old plugins must be rewritten.
- Java 21, Maven 3.6+. Modules: `ZhiFlow-Api` (contract, `provided`), `plugin-markdown`, `ZhiFlow` (runtime).
- Spring AI 2.0.0 GA. Never use the deprecated `ChatModel` internal tool-execution path (removed in 3.0.0) — use `ChatClient` + `ToolCallingAdvisor` / `ToolCallingManager`.
- AI tools MUST be Spring AI native: `@Tool` annotation or `ToolCallback` bean. No ZhiFlow adapter layer between plugins and Spring AI.
- Every plugin MUST have a UI (`PluginDescriptor.uiEntry` non-null, enforced at registration). UI = ESM micro-frontend served at `/plugin-ui/{id}/**`.
- The canonical (v2) package is `fan.summer.zhiflow.api.*`. The legacy `fan.summer.api.*` package is dead code (0 references) and is deleted entirely.
- Host is the ONLY language-switch entry point. Plugins must NOT ship a language switcher; plugin locale follows `ctx.locale`.
- Category list is backend-driven (the `ToolCategory` enum is the source of truth); frontend sidebar fetches it dynamically.
- Commit messages use gitmoji + conventional commits, matching recent history (e.g. `🗑️ chore(ai): remove hand-rolled AiTool layer`).
- After every task: `mvn -q -pl <module> compile` (or `mvn -q test` where tests exist) must pass before committing. Frequent commits.
- **Working tree note:** `ZhiFlow-Api/src/main/java/fan/summer/api/` (v1, legacy) and `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/` (v2, canonical) are TWO DIFFERENT packages. Edit only the v2 package unless a task explicitly deletes the v1 one.

---

## File Structure

### Backend — ZhiFlow-Api (contract module)

| File | Action | Responsibility |
|---|---|---|
| `fan/summer/api/**` (44 files) + `src/test/java/fan/summer/api/**` | DELETE | v1 dead code |
| `fan/summer/zhiflow/api/ZhiFlowPlugin.java` (JavaFX createView) | DELETE | old v2 JavaFX contract |
| `fan/summer/zhiflow/api/plugin/ZhiFlowPluginV2.java` | RENAME → `plugin/ZhiFlowPlugin.java` | new UI-plugin contract |
| `fan/summer/zhiflow/api/plugin/PluginDescriptor.java` | MODIFY | add `supportsAi`, `source` |
| `fan/summer/zhiflow/api/plugin/PluginSource.java` | CREATE | OFFICIAL/THIRD_PARTY enum |
| `fan/summer/zhiflow/api/ToolCategory.java` | MODIFY | add `AI`, change labelKey prefix to `category.*` |
| `fan/summer/zhiflow/api/ToolType.java` | DELETE | replaced by PluginSource |
| `fan/summer/zhiflow/api/ai/AiTool.java`, `AiToolParam.java`, `AiToolResult.java`, `AiToolCall.java` | DELETE | hand-rolled layer, replaced by Spring AI ToolCallback |
| `fan/summer/zhiflow/api/ai/AiServiceProvider.java` | DELETE | replaced by AiModeService + Spring AI |
| `fan/summer/zhiflow/api/ai/AiChatMessage.java`, `ChatBackend.java`, `AiStreamCallback.java`, `AiServiceException.java` | KEEP | chat contract still needed |

### Backend — ZhiFlow (runtime module)

| File | Action | Responsibility |
|---|---|---|
| `ai/ToolExecutor.java` | DELETE | replaced by ToolCallingManager |
| `ai/SlashCommandHandler.java` | DELETE | orphaned (0 call sites) |
| `ai/adapter/AiToolCallback.java`, `ToolSchemaJson.java`, `AiToolDescriptions.java` | DELETE | hand-rolled adapters |
| `ai/tools/Builtin*.java` (4 files) | DELETE | orphaned (0 call sites) |
| `ai/service/AiModeService.java` | CREATE | backend mode mgmt (was AiServiceProvider's non-tool half) |
| `ai/service/SpringAiCloudBackend.java` | MODIFY | runToolLoop → ChatClient + ToolCallingAdvisor |
| `ai/service/OllamaLocalBackend.java` | MODIFY | runToolLoop → ChatClient + ToolCallingAdvisor |
| `ai/spring/AiBackendInitializer.java` | MODIFY | switchMode → AiModeService |
| `plugin/PluginRegistryService.java` | MODIFY | drop registerAiTools; add uiEntry/source validation |
| `web/controller/AiController.java` | MODIFY | getService → AiModeService |
| `web/controller/PluginController.java` | MODIFY | add /api/plugin-categories |
| `web/controller/AgentController.java` | CREATE | agent run/stream/approve/cancel + tools list |
| `ai/agent/AgentRunner.java` | CREATE | Plan-and-Execute runtime |
| `ai/agent/AgentModels.java` | CREATE | AgentPlan/Step/Run records |

### Frontend — frontend/src

| File | Action | Responsibility |
|---|---|---|
| `i18n/index.ts`, `i18n/en.json`, `i18n/zh.json` | CREATE | vue-i18n setup + locales |
| `main.ts` | MODIFY | app.use(i18n); locale from settings store |
| `api/types.ts` | MODIFY | add PluginSource, supportsAi, AI category, CategoryDescriptor |
| `api/client.ts` | MODIFY | add getPluginCategories(), agent endpoints |
| `stores/settings.ts` | MODIFY | watch language → i18n locale |
| `stores/categories.ts` | CREATE | fetch + cache backend categories |
| `shell/Sidebar.vue` | MODIFY | dynamic categories (drop hardcoded); i18n labels; agent nav |
| `mf/loader.ts`, `views/PluginView.vue` | MODIFY | PluginContext: locale + t + onLocaleChange |
| `views/AiAgent.vue` | CREATE | agent plan/step/approval UI |
| `views/*.vue` (existing) | MODIFY | hardcoded strings → $t() |
| `router/index.ts` | MODIFY | add /agent route |

---

## Task 1: Delete v1 dead-code package

**Files:**
- Delete: `ZhiFlow-Api/src/main/java/fan/summer/api/**` (44 files)
- Delete: `ZhiFlow-Api/src/test/java/fan/summer/api/**` (5 test files)

**Interfaces:**
- Consumes: nothing
- Produces: a cleaner `ZhiFlow-Api` with only the canonical `fan.summer.zhiflow.api.*` package

The `fan.summer.api.*` package (JavaFX-era v1) is confirmed dead: zero references from `ZhiFlow/src`, `plugin-markdown/src`, or the v2 package itself. Deleting it as a unit.

- [ ] **Step 1: Verify zero references before deleting**

Run from repo root:
```bash
grep -rn "fan\.summer\.api\." ZhiFlow/src plugin-markdown/src ZhiFlow-Api/src/main/java/fan/summer/zhiflow \
  --include="*.java" | grep -v "fan.summer.zhiflow.api"
```
Expected: **no output**. If any line appears, STOP — a v2 file references v1; resolve before deleting.

- [ ] **Step 2: Delete the v1 main + test packages**

```bash
rm -rf ZhiFlow-Api/src/main/java/fan/summer/api
rm -rf ZhiFlow-Api/src/test/java/fan/summer/api
```

- [ ] **Step 3: Verify the API module compiles**

Run: `mvn -q -pl ZhiFlow-Api compile`
Expected: BUILD SUCCESS. (If it fails, a v2 file was importing v1 — fix the import to the v2 equivalent, then recompile.)

- [ ] **Step 4: Verify the runtime + plugin modules compile**

Run: `mvn -q -pl ZhiFlow,plugin-markdown -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "🗑️ chore(api): delete dead v1 fan.summer.api.* package (44 classes)

4.0.0 breaking cleanup. The JavaFX-era v1 package had zero references
from the runtime, plugin-markdown, or the v2 package. Only the canonical
fan.summer.zhiflow.api.* remains."
```

---

## Task 2: Delete orphaned AI tool files (zero call sites)

**Files:**
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/SlashCommandHandler.java`
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinJsonFormatTool.java`
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinBase64Tool.java`
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinHashTool.java`
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinColorConvertTool.java`
- Delete: tests: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/{AiToolCallbackTest,ToolSchemaJsonTest,AiToolDescriptionsTest}.java`, `.../api/ai/{AiServiceProviderModeFilterTest,AiToolCloudLocalDefaultsTest}.java`

**Interfaces:**
- Consumes: nothing
- Produces: fewer dead files before the contract layer is touched

`SlashCommandHandler` and the 4 `Builtin*Tool` classes have **zero call sites** (confirmed by grep). They are pure dead code. Deleting them now removes noise before the contract refactor.

- [ ] **Step 1: Confirm SlashCommandHandler has no callers**

Run:
```bash
grep -rn "SlashCommandHandler" ZhiFlow/src --include="*.java" | grep -v "class SlashCommandHandler"
```
Expected: **no output**.

- [ ] **Step 2: Confirm the 4 builtin tools are never instantiated/registered**

Run:
```bash
grep -rn "BuiltinJsonFormatTool\|BuiltinBase64Tool\|BuiltinHashTool\|BuiltinColorConvertTool" \
  ZhiFlow/src plugin-markdown/src --include="*.java" | grep -v "class Builtin"
```
Expected: **no output** (only the class definitions themselves remain).

- [ ] **Step 3: Delete the orphaned files + their now-invalid tests**

```bash
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/SlashCommandHandler.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinJsonFormatTool.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinBase64Tool.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinHashTool.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinColorConvertTool.java
# tests that reference deleted classes / the AiServiceProvider registry
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/AiToolCallbackTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/ToolSchemaJsonTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/AiToolDescriptionsTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/api/ai/AiServiceProviderModeFilterTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/api/ai/AiToolCloudLocalDefaultsTest.java
```

- [ ] **Step 4: Verify compile**

Run: `mvn -q -pl ZhiFlow -am compile`
Expected: BUILD SUCCESS. (If a test compile fails citing another test referencing deleted symbols, delete that test too — it only existed to test the deleted code.)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "🗑️ chore(ai): delete orphaned SlashCommandHandler + 4 builtin AiTools

SlashCommandHandler and the 4 Builtin*Tool classes had zero call sites
and were never registered. Removing dead code before the contract refactor."
```

---

## Task 3: Delete old v2 ZhiFlowPlugin (JavaFX createView) + ToolType

**Files:**
- Delete: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ZhiFlowPlugin.java`
- Delete: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ToolType.java`

**Interfaces:**
- Consumes: nothing
- Produces: the old JavaFX `ZhiFlowPlugin` contract is gone; the headless `plugin/ZhiFlowPluginV2` is the sole plugin contract (renamed in Task 5)

The v2-package `ZhiFlowPlugin.java` (note: this is the OLD JavaFX `createView()` contract that lives at the api package root, NOT the `plugin/ZhiFlowPluginV2` we're keeping) and `ToolType` (BUILTIN/PLUGIN, a v1 concept dropped in the headless descriptor) are dead.

- [ ] **Step 1: Verify no references to the old ZhiFlowPlugin / ToolType**

Run:
```bash
grep -rn "fan.summer.zhiflow.api.ZhiFlowPlugin\b\|fan.summer.zhiflow.api.ToolType" \
  ZhiFlow/src plugin-markdown/src --include="*.java"
```
Expected: **no output**. (The runtime uses `plugin.ZhiFlowPluginV2`, not the root `api.ZhiFlowPlugin`.) If output appears, change those imports to `plugin.ZhiFlowPluginV2` first.

- [ ] **Step 2: Delete**

```bash
rm ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ZhiFlowPlugin.java
rm ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ToolType.java
```

- [ ] **Step 3: Verify compile**

Run: `mvn -q -pl ZhiFlow-Api,plugin-markdown -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "🗑️ chore(api): delete old JavaFX ZhiFlowPlugin contract + ToolType

The root api.ZhiFlowPlugin (JavaFX createView) and ToolType are v1
concepts with no headless consumers. plugin.ZhiFlowPluginV2 is the
sole plugin contract."
```

---

## Task 4: Add ToolCategory.AI + unify labelKey prefix

**Files:**
- Modify: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ToolCategory.java`
- Test: `ZhiFlow-Api/src/test/java/fan/summer/zhiflow/api/ToolCategoryTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `ToolCategory.AI` enum value; all labelKeys now `category.<id>` (e.g. `category.dev`, `category.ai`); `fromId("ai")` returns `AI`

- [ ] **Step 1: Write the failing test**

Create `ZhiFlow-Api/src/test/java/fan/summer/zhiflow/api/ToolCategoryTest.java`:
```java
package fan.summer.zhiflow.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolCategoryTest {

    @Test
    void hasAiCategory() {
        assertEquals("ai", ToolCategory.AI.getId());
        assertEquals("category.ai", ToolCategory.AI.getLabelKey());
    }

    @Test
    void allLabelKeysUseUnifiedPrefix() {
        for (ToolCategory c : ToolCategory.values()) {
            assertTrue(c.getLabelKey().startsWith("category."),
                "labelKey " + c.getLabelKey() + " should start with 'category.'");
        }
    }

    @Test
    void fromIdResolvesAi() {
        assertEquals(ToolCategory.AI, ToolCategory.fromId("ai"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow-Api test -Dtest=ToolCategoryTest`
Expected: FAIL — `AI` does not exist / labelKeys still use old prefixes.

- [ ] **Step 3: Modify the enum**

Read the current `ToolCategory.java` first to get exact formatting, then replace the enum constants and constructor so the values are:
```java
DEV("dev", "category.dev"),
TEXT("text", "category.text"),
IMAGE("image", "category.image"),
NET("net", "category.net"),
AI("ai", "category.ai"),
OTHER("other", "category.other");
```
Keep `getId()`, `getLabelKey()` (rename from `getI18nKey()` if that's the current name — read the file to confirm, update accessor to `getLabelKey()`), and `fromId(String)` (add `"ai"` → `AI`). Fallback stays `OTHER`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow-Api test -Dtest=ToolCategoryTest`
Expected: PASS.

- [ ] **Step 5: Fix any callers of the renamed accessor**

Run: `grep -rn "getI18nKey" ZhiFlow/src plugin-markdown/src --include="*.java"`
If any appear, rename to `getLabelKey()`. (Expected: none — it was dead code, but verify.)

- [ ] **Step 6: Verify full compile**

Run: `mvn -q -pl ZhiFlow-Api,plugin-markdown -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "✨ feat(api): add ToolCategory.AI, unify labelKey prefix to category.*

Breaking: i18n keys for categories change from developer.tools/
text.processing/etc. to category.dev/category.text/etc. Adds an AI
category for Agent/prompt tools. Frontend vue-i18n will use these keys."
```

---

## Task 5: Rename ZhiFlowPluginV2 → ZhiFlowPlugin + remove aiTools()

**Files:**
- Rename: `ZhiFlow-Api/.../api/plugin/ZhiFlowPluginV2.java` → `ZhiFlowPlugin.java`
- Modify: `plugin-markdown/src/main/java/fan/summer/zhiflow/plugin/markdown/MarkdownPlugin.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/plugin/PluginRegistryService.java`

**Interfaces:**
- Consumes: `PluginDescriptor` (Task 6 will add fields; for now descriptor stays as-is)
- Produces: `ZhiFlowPlugin` interface with `descriptor()` + `invoke(String, Map<String,Object>)` only (no `aiTools()`). MarkdownPlugin + PluginRegistryService compile against the new name.

- [ ] **Step 1: Read the current files**

Read `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/ZhiFlowPluginV2.java` to see its exact current content (it has `descriptor()`, `invoke()`, and a default `aiTools()`).

- [ ] **Step 2: Create the renamed interface without aiTools()**

Create `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/ZhiFlowPlugin.java`:
```java
package fan.summer.zhiflow.api.plugin;

import java.util.Map;

/**
 * 4.0.0 plugin contract (UI plugin). Each plugin = backend logic ({@link #invoke})
 * plus a self-contained UI delivered as an ESM micro-frontend
 * ({@link PluginDescriptor#uiEntry()}), rendered by the host at a designated location.
 *
 * <p>AI capability is optional and independent: if a plugin supports AI calls, it
 * provides Spring AI-native {@code ToolCallback} beans (annotated with {@code @Tool}
 * or implementing {@code ToolCallback}) in the same module. {@link PluginDescriptor#supportsAi()}
 * is metadata only; the actual tools are Spring AI beans.
 */
public interface ZhiFlowPlugin {

    PluginDescriptor descriptor();

    /**
     * Backend JSON-RPC. The UI micro-frontend calls this via
     * {@code POST /api/plugins/{id}/invoke}.
     *
     * @param action plugin-defined action string (e.g. "render")
     * @param args   action arguments (JSON-deserialized map)
     * @return a JSON-serializable result (controller serializes it)
     * @throws IllegalArgumentException if the action is unknown or args are invalid
     */
    Object invoke(String action, Map<String, Object> args);
}
```

Then delete the old file:
```bash
rm ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/ZhiFlowPluginV2.java
```

- [ ] **Step 3: Update MarkdownPlugin**

Edit `plugin-markdown/src/main/java/fan/summer/zhiflow/plugin/markdown/MarkdownPlugin.java`:
- Change import `fan.summer.zhiflow.api.plugin.ZhiFlowPluginV2` → `fan.summer.zhiflow.api.plugin.ZhiFlowPlugin`
- Change `implements ZhiFlowPluginV2` → `implements ZhiFlowPlugin`
- The `descriptor()` and `invoke()` methods stay identical. (Task 6 will update the descriptor call to add `supportsAi`/`source`.)

- [ ] **Step 4: Update PluginRegistryService imports + remove registerAiTools**

Edit `ZhiFlow/src/main/java/fan/summer/zhiflow/plugin/PluginRegistryService.java`:
- Change import `ZhiFlowPluginV2` → `ZhiFlowPlugin`; remove the `AiTool` and `AiServiceProvider` imports.
- Change all `ZhiFlowPluginV2` references in the file to `ZhiFlowPlugin` (the `byId` map type, constructor param type, method locals).
- **Delete the entire `@PostConstruct registerAiTools()` method** and its `import jakarta.annotation.PostConstruct;`.
- Keep `descriptors()`, `find()`, `invoke()` as-is (they already match the new contract).

- [ ] **Step 5: Verify compile**

Run: `mvn -q -pl ZhiFlow,plugin-markdown -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "♻️ refactor(api): rename ZhiFlowPluginV2 → ZhiFlowPlugin, drop aiTools()

Breaking. The plugin contract is now descriptor() + invoke() only.
AI capability is no longer declared on the interface; plugins provide
Spring AI-native ToolCallback beans in the same module instead.
PluginRegistryService no longer auto-registers plugin tools (Spring AI
discovers @Tool/ToolCallback beans itself)."
```

---

## Task 6: Add PluginDescriptor.supportsAi + source; create PluginSource

**Files:**
- Modify: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/PluginDescriptor.java`
- Create: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/PluginSource.java`
- Test: `ZhiFlow-Api/src/test/java/fan/summer/zhiflow/api/plugin/PluginDescriptorTest.java`
- Modify: `plugin-markdown/.../MarkdownPlugin.java`

**Interfaces:**
- Consumes: `ToolCategory` (Task 4)
- Produces: `PluginDescriptor(id, name, description, category, icon, iconStyle, version, uiEntry, supportsAi, source)` record; `PluginSource.OFFICIAL`/`THIRD_PARTY` enum with `getId()` + `getLabelKey()`

- [ ] **Step 1: Create PluginSource enum**

Create `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/PluginSource.java`:
```java
package fan.summer.zhiflow.api.plugin;

/**
 * Declared origin of a plugin. Drives UI badges (Official / Third-party) and
 * optional trust checks. {@code labelKey} is resolved by the frontend via vue-i18n.
 */
public enum PluginSource {
    OFFICIAL("official", "source.official"),
    THIRD_PARTY("third_party", "source.third_party");

    private final String id;
    private final String labelKey;

    PluginSource(String id, String labelKey) {
        this.id = id;
        this.labelKey = labelKey;
    }

    public String getId() { return id; }
    public String getLabelKey() { return labelKey; }
}
```

- [ ] **Step 2: Write the failing test**

Create `ZhiFlow-Api/src/test/java/fan/summer/zhiflow/api/plugin/PluginDescriptorTest.java`:
```java
package fan.summer.zhiflow.api.plugin;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PluginDescriptorTest {

    @Test
    void descriptorCarriesSupportsAiAndSource() {
        PluginDescriptor d = new PluginDescriptor(
            "fan.summer.markdown", "MD", "desc", ToolCategory.TEXT,
            "language-markdown", IconStyle.BLUE, "4.0.0",
            "/plugin-ui/markdown/index.js", true, PluginSource.OFFICIAL);
        assertTrue(d.supportsAi());
        assertEquals(PluginSource.OFFICIAL, d.source());
    }

    @Test
    void pluginSourceLabelKeys() {
        assertEquals("source.official", PluginSource.OFFICIAL.getLabelKey());
        assertEquals("source.third_party", PluginSource.THIRD_PARTY.getLabelKey());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow-Api test -Dtest=PluginDescriptorTest`
Expected: FAIL — no 10-arg constructor, no `source` field.

- [ ] **Step 4: Modify PluginDescriptor record**

Edit `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/plugin/PluginDescriptor.java` — replace the record definition:
```java
public record PluginDescriptor(
    String id,
    String name,
    String description,
    ToolCategory category,
    String icon,
    IconStyle iconStyle,
    String version,
    String uiEntry,
    boolean supportsAi,
    PluginSource source
) {}
```
No compatibility constructor. Update the javadoc to mention `uiEntry` is mandatory and `source`/`supportsAi` are declarative.

- [ ] **Step 4b: Update MarkdownPlugin.descriptor()**

Edit `MarkdownPlugin.java` `descriptor()` to pass the two new args. It's an official plugin with no AI tool for now, so:
```java
return new PluginDescriptor(
    ID, "Markdown Editor",
    "Split-pane Markdown editor with live server-rendered HTML preview",
    ToolCategory.TEXT, "language-markdown", IconStyle.BLUE, "4.0.0",
    "/plugin-ui/markdown/index.js",
    false,                 // supportsAi
    PluginSource.OFFICIAL  // source
);
```
Add `import fan.summer.zhiflow.api.plugin.PluginSource;`.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow-Api test -Dtest=PluginDescriptorTest`
Expected: PASS.

- [ ] **Step 6: Verify full compile**

Run: `mvn -q -pl ZhiFlow,plugin-markdown -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "✨ feat(api): add PluginDescriptor.supportsAi + source, PluginSource enum

Plugins now declare whether they support AI (metadata; actual tools are
Spring AI beans) and their origin (OFFICIAL/THIRD_PARTY). uiEntry remains
mandatory. MarkdownPlugin marked OFFICIAL, supportsAi=false for now."
```

---

## Task 7: PluginRegistryService uiEntry + source validation

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/plugin/PluginRegistryService.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/plugin/PluginRegistryServiceTest.java`

**Interfaces:**
- Consumes: `PluginDescriptor` (Task 6), `ZhiFlowPlugin` (Task 5)
- Produces: `PluginRegistryService` rejects plugins with blank `uiEntry`; downgrades `OFFICIAL` plugins whose id doesn't start with `fan.summer.` to `THIRD_PARTY` (logging a warning)

- [ ] **Step 1: Write the failing test**

Create `ZhiFlow/src/test/java/fan/summer/zhiflow/plugin/PluginRegistryServiceTest.java`:
```java
package fan.summer.zhiflow.plugin;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.plugin.PluginDescriptor;
import fan.summer.zhiflow.api.plugin.PluginSource;
import fan.summer.zhiflow.api.plugin.ZhiFlowPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryServiceTest {

    private ZhiFlowPlugin plugin(String id, String uiEntry, PluginSource source) {
        return new ZhiFlowPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor(id, "n", "d", ToolCategory.OTHER,
                    "icon", IconStyle.BLUE, "1.0.0", uiEntry, false, source);
            }
            @Override public Object invoke(String action, java.util.Map<String, Object> args) {
                return null;
            }
        };
    }

    @Test
    void rejectsPluginWithBlankUiEntry() {
        PluginRegistryService svc = new PluginRegistryService(List.of(
            plugin("com.example.no-ui", "", PluginSource.THIRD_PARTY)));
        assertTrue(svc.find("com.example.no-ui").isEmpty(),
            "plugin with blank uiEntry must not be registered");
    }

    @Test
    void downgradesOfficialWithWrongIdPrefix() {
        PluginRegistryService svc = new PluginRegistryService(List.of(
            plugin("com.example.fake", "/plugin-ui/x/index.js", PluginSource.OFFICIAL)));
        Optional<ZhiFlowPlugin> found = svc.find("com.example.fake");
        assertTrue(found.isPresent());
        assertEquals(PluginSource.THIRD_PARTY, found.get().descriptor().source(),
            "OFFICIAL plugin whose id lacks 'fan.summer.' prefix is downgraded to THIRD_PARTY");
    }

    @Test
    void keepsOfficialWithCorrectPrefix() {
        PluginRegistryService svc = new PluginRegistryService(List.of(
            plugin("fan.summer.real", "/plugin-ui/x/index.js", PluginSource.OFFICIAL)));
        assertEquals(PluginSource.OFFICIAL,
            svc.find("fan.summer.real").orElseThrow().descriptor().source());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow test -Dtest=PluginRegistryServiceTest`
Expected: FAIL — current constructor registers everything unconditionally; no downgrade logic.

- [ ] **Step 3: Implement validation in the constructor**

Edit `PluginRegistryService` constructor. After computing the descriptor, before `byId.put`:
- If `d.uiEntry()` is null or blank → `log.warn("Skipping plugin '{}': uiEntry is blank", d.id())` and `continue`.
- If `d.source() == PluginSource.OFFICIAL` AND `d.id()` does not start with `"fan.summer."` → `log.warn("Plugin '{}' declared OFFICIAL but id lacks 'fan.summer.' prefix; downgrading to THIRD_PARTY", d.id())` and build a replacement descriptor with `source = PluginSource.THIRD_PARTY`. Since `PluginDescriptor` is a record, use the 1-arg copy method: `d = new PluginDescriptor(d.id(), d.name(), d.description(), d.category(), d.icon(), d.iconStyle(), d.version(), d.uiEntry(), d.supportsAi(), PluginSource.THIRD_PARTY);`

- [ ] **Step 3b: supportsAi consistency warning (spec §3.1.3, warn-only, optional)**

Spec §3.1.3 wants a loose warning when a plugin declares `supportsAi()==true` but ships no matching `ToolCallback` bean. Because bean ownership is hard to attribute strictly, this is **warn-only, never blocking**, and must not complicate the existing unit tests (whose `PluginRegistryService(List.of(...))` constructor has no tool collection). Approach: add a SECOND constructor that also takes `ObjectProvider<ToolCallback>` (Spring injects it; the test keeps using the list-only constructor). After registration, for each descriptor with `supportsAi==true`, if the discovered tool set is empty → `log.warn("Plugin '{}' declares supportsAi=true but no ToolCallback beans were discovered", d.id())`. If wiring a second constructor is awkward, defer this to a startup `@EventListener(ApplicationReadyEvent.class)` in a separate small bean — either way it stays advisory. Skip entirely if it risks the Task 7 tests; note the decision in the result.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow test -Dtest=PluginRegistryServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "✨ feat(plugin): enforce mandatory uiEntry + downgrade bogus OFFICIAL source

PluginRegistryService now rejects plugins with a blank uiEntry (every
plugin must have a UI) and downgrades any plugin declaring OFFICIAL whose
id lacks the 'fan.summer.' prefix to THIRD_PARTY (with a warning)."
```

---

## Task 8: Add /api/plugin-categories endpoint

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/PluginController.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/web/controller/PluginControllerCategoriesTest.java`

**Interfaces:**
- Consumes: `ToolCategory` (Task 4)
- Produces: `GET /api/plugin-categories` → JSON array of `{"id","labelKey","icon"}` derived from the `ToolCategory` enum

- [ ] **Step 1: Write the failing test**

Create `ZhiFlow/src/test/java/fan/summer/zhiflow/web/controller/PluginControllerCategoriesTest.java`:
```java
package fan.summer.zhiflow.web.controller;

import fan.summer.zhiflow.api.ToolCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginControllerCategoriesTest {

    @Test
    void categoriesEndpointReturnsAllEnums() {
        PluginController controller = new PluginController(null); // registry unused for categories
        @SuppressWarnings("unchecked")
        List<Map<String, String>> result = (List<Map<String, String>>) controller.categories();
        assertEquals(ToolCategory.values().length, result.size());
        // every entry has the 3 keys
        for (Map<String, String> entry : result) {
            assertEquals(3, entry.size(), entry.toString());
            assertNotNull(entry.get("id"));
            assertTrue(entry.get("labelKey").startsWith("category."));
            assertNotNull(entry.get("icon"));
        }
        // AI is present
        assertTrue(result.stream().anyMatch(e -> "ai".equals(e.get("id"))));
    }
}
```

> Note: read `PluginController.java` first to confirm its constructor signature (it currently takes `PluginRegistryService`). If `categories()` doesn't need the registry, passing `null` is fine for this unit test. If the constructor does other work that NPEs on null, instead instantiate a tiny stub or use `Mockito.mock(PluginRegistryService.class)` and add the mockito dependency if not present — check `ZhiFlow/pom.xml` test deps first.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow test -Dtest=PluginControllerCategoriesTest`
Expected: FAIL — no `categories()` method.

- [ ] **Step 3: Add the endpoint + method**

Edit `PluginController.java`. Add:
```java
import fan.summer.zhiflow.api.ToolCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@GetMapping("/api/plugin-categories")
public List<Map<String, String>> categories() {
    // Backend is the source of truth for which categories exist.
    // The frontend fetches this and renders the sidebar dynamically.
    List<Map<String, String>> out = new ArrayList<>();
    for (ToolCategory c : ToolCategory.values()) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("id", c.getId());
        entry.put("labelKey", c.getLabelKey());
        entry.put("icon", iconFor(c.getId()));
        out.add(entry);
    }
    return out;
}

private static String iconFor(String id) {
    return switch (id) {
        case "dev"  -> "⚙";
        case "text" -> "¶";
        case "image" -> "▩";
        case "net"  -> "☍";
        case "ai"   -> "✦";
        default     -> "◇";
    };
}
```
(If `PluginController` already has a class-level `@RequestMapping`, place `@GetMapping("/plugin-categories")` instead and verify the full path is `/api/plugin-categories` by reading the existing mappings.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow test -Dtest=PluginControllerCategoriesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "✨ feat(web): add GET /api/plugin-categories (backend-driven sidebar)

The ToolCategory enum is now the single source of truth for the sidebar
category list. Frontend will fetch this and render dynamically instead of
hardcoding. Each entry carries a stable id + i18n labelKey + icon."
```

---

## Task 9: Delete hand-rolled AiTool contract layer

**Files:**
- Delete: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiTool.java`
- Delete: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiToolParam.java`
- Delete: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiToolResult.java`
- Delete: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiToolCall.java`
- Delete: `ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiServiceProvider.java`
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/AiToolCallback.java`
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/ToolSchemaJson.java`

**Interfaces:**
- Consumes: AiModeService MUST exist (Task 10) before this compiles — **do Task 10 first**, then this. (This task is listed here for clarity; execute after Task 10.)
- Produces: the hand-rolled tool contract + adapters are gone; only Spring AI types remain

> **Execution order:** This task deletes the contract layer that the runtime still imports. It will not compile on its own. Tasks 10–12 create `AiModeService` and rewire all 7 production call sites. **Do this task LAST among the backend-AI tasks (after 10, 11, 12), or interleave: after Task 10 creates AiModeService, do the deletions here together with the rewiring in 11/12 in a single compile-fix pass.** The simplest sequencing: Task 10 (create AiModeService), Task 11 (rewire call sites away from AiServiceProvider), Task 12 (rewire backends off ToolExecutor), THEN Task 9 (delete now-unreferenced files).

- [ ] **Step 1: Confirm no remaining references (after Tasks 10–12)**

Run:
```bash
grep -rn "AiTool\b\|AiToolParam\|AiToolResult\|AiToolCall\|AiServiceProvider" \
  ZhiFlow/src/main plugin-markdown/src --include="*.java"
```
Expected: **no output** (all call sites migrated in Tasks 11–12). If references remain, migrate them first.

- [ ] **Step 2: Delete the files**

```bash
rm ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiTool.java
rm ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiToolParam.java
rm ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiToolResult.java
rm ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiToolCall.java
rm ZhiFlow-Api/src/main/java/fan/summer/zhiflow/api/ai/AiServiceProvider.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/AiToolCallback.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/ToolSchemaJson.java
# their tests
rm -f ZhiFlow/src/test/java/fan/summer/zhiflow/ai/ToolExecutorErrorJsonTest.java
rm -f ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/MessageMapperTest.java
```

- [ ] **Step 3: Verify compile + tests**

Run: `mvn -q -pl ZhiFlow-Api,ZhiFlow,plugin-markdown -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "🗑️ chore(ai): delete hand-rolled AiTool contract + adapters

AiTool/AiToolParam/AiToolResult/AiToolCall/AiServiceProvider and the
AiToolCallback/ToolSchemaJson adapters are removed. AI tools are now
pure Spring AI-native ToolCallback beans. Backend mode management moved
to AiModeService."
```

---

## Task 10: Create AiModeService (extract non-tool half of AiServiceProvider)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/AiModeService.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/AiModeServiceTest.java`

**Interfaces:**
- Consumes: `ChatBackend` (kept in api.ai)
- Produces: `AiModeService` Spring bean with `getCurrentMode()`, `getService() : Optional<ChatBackend>`, `switchMode(String mode, ChatBackend)`, `setService(ChatBackend)`, state-change listeners (instance methods, no static)

- [ ] **Step 1: Write the failing test**

Create `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/AiModeServiceTest.java`:
```java
package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.api.ai.ChatBackend;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AiModeServiceTest {

    private static ChatBackend stub() {
        return new ChatBackend() {
            public void loadModel(java.nio.file.Path p) {}
            public void unloadModel() {}
            public boolean isReady() { return false; }
            public Optional<String> getModelName() { return Optional.empty(); }
            public long getMemoryUsage() { return -1; }
            public boolean isGenerating() { return false; }
            public boolean isNativeAvailable() { return false; }
            public void chat(java.util.List<fan.summer.zhiflow.api.ai.AiChatMessage> h,
                             fan.summer.zhiflow.api.ai.AiStreamCallback c) {}
            public void chat(java.util.List<fan.summer.zhiflow.api.ai.AiChatMessage> h,
                             float t, float tp, int m,
                             fan.summer.zhiflow.api.ai.AiStreamCallback c) {}
            public void cancelGeneration() {}
        };
    }

    @Test
    void switchModeStoresBackendAndFiresListeners() {
        AiModeService svc = new AiModeService();
        AtomicInteger fired = new AtomicInteger();
        svc.addOnStateChangeListener(fired::incrementAndGet);
        assertEquals("local", svc.getCurrentMode());
        ChatBackend b = stub();
        svc.switchMode("openai", b);
        assertEquals("openai", svc.getCurrentMode());
        assertTrue(svc.getService().isPresent());
        assertSame(b, svc.getService().get());
        assertEquals(1, fired.get());
    }
}
```
> Read `ChatBackend.java` first to get the EXACT method signatures (the stub above must implement all abstract methods; adjust if signatures differ — e.g. `chat` may throw `AiServiceException`).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow test -Dtest=AiModeServiceTest`
Expected: FAIL — `AiModeService` doesn't exist.

- [ ] **Step 3: Create AiModeService**

Create `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/AiModeService.java`:
```java
package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.api.ai.ChatBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Backend mode management (the non-tool half of the former AiServiceProvider).
 * Holds the active {@link ChatBackend} + mode label and notifies listeners on switch.
 * Tool registry responsibilities are gone — Spring AI discovers tools itself.
 */
@Component
public class AiModeService {

    private static final Logger log = LoggerFactory.getLogger(AiModeService.class);

    private volatile ChatBackend activeBackend;
    private volatile String mode = "local";
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Optional<ChatBackend> getService() { return Optional.ofNullable(activeBackend); }

    public String getCurrentMode() { return mode; }

    public synchronized void switchMode(String mode, ChatBackend newBackend) {
        if (activeBackend != null) {
            try { activeBackend.unloadModel(); }
            catch (Exception e) { log.warn("Failed to unload previous backend: {}", e.getMessage()); }
        }
        this.mode = mode;
        this.activeBackend = newBackend;
        for (Runnable l : listeners) l.run();
    }

    public void setService(ChatBackend backend) { this.activeBackend = backend; }

    public void addOnStateChangeListener(Runnable l) { listeners.add(l); }
    public void removeOnStateChangeListener(Runnable l) { listeners.remove(l); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow test -Dtest=AiModeServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "✨ feat(ai): add AiModeService bean (backend mode management)

Extracts the non-tool half of AiServiceProvider into a Spring bean:
active backend + mode label + state-change listeners. Tool registry
responsibilities are dropped (Spring AI discovers @Tool/ToolCallback)."
```

---

## Task 11: Rewire AiServiceProvider call sites to AiModeService

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/AiController.java` (`getService` :71)
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiBackendInitializer.java` (`switchMode` :40,44,48)
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/OllamaLocalBackend.java` (`getTools` :212 — will be fully rewritten in Task 12; here just stop importing AiServiceProvider)
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/AiToolDescriptions.java` (`getCurrentMode` :22) — DELETE if unused after backend rewrites

**Interfaces:**
- Consumes: `AiModeService` (Task 10)
- Produces: zero references to `AiServiceProvider` in production main source (test references die with Task 9/12)

The 7 production call sites (from the inventory): `AiController:71` (getService), `AiBackendInitializer:40/44/48` (switchMode ×3), `SpringAiCloudBackend:215` + `OllamaLocalBackend:207` (ToolExecutor — handled in Task 12), `PluginRegistryService:55` (already removed in Task 5), `AiToolDescriptions:22` (getCurrentMode).

- [ ] **Step 1: AiController — inject AiModeService**

Read `AiController.java` to see how it's currently constructed (likely no DI — uses static `AiServiceProvider.getService()`). Convert to constructor-inject `AiModeService`:
```java
private final AiModeService aiMode;
public AiController(AiModeService aiMode) { this.aiMode = aiMode; }
```
Replace `AiServiceProvider.getService()` (line ~71) → `aiMode.getService()`. Remove the `AiServiceProvider` import.

- [ ] **Step 2: AiBackendInitializer — inject AiModeService**

Read `AiBackendInitializer.java`. It currently calls `AiServiceProvider.switchMode("openai", SpringAiCloudBackend.openAi(...))` etc. Inject `AiModeService` (if it's a `@Component`/`@Service`, add a constructor field; if it's wired some other way, follow the existing pattern) and replace the 3 `AiServiceProvider.switchMode(...)` calls with `aiMode.switchMode(...)`. Remove the import.

- [ ] **Step 3: Handle AiToolDescriptions.getCurrentMode**

Read `AiToolDescriptions.java`. It's `return "local".equals(AiServiceProvider.getCurrentMode());`. This helper picked cloud-vs-local params for the deleted AiTool adapter. Since `AiTool`/the adapter are being deleted (Task 9), **delete `AiToolDescriptions.java` entirely** if nothing else references it:
```bash
grep -rn "AiToolDescriptions" ZhiFlow/src --include="*.java"
```
If only itself + its (already-deleted) test appear, `rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/AiToolDescriptions.java`.

- [ ] **Step 4: Verify compile (expect remaining ToolExecutor/AiTool refs in backends — those are Task 12)**

Run: `mvn -q -pl ZhiFlow compile`
Expected: may still fail on `SpringAiCloudBackend`/`OllamaLocalBackend` (Task 12) and the deleted-adapter refs. Fix ONLY the AiServiceProvider call sites in this task. If `MessageMapper` (used by backends) is the only remaining breakage, that's fine — Task 12 handles it.

> If the compile is too broken to iterate, you may merge this step with Task 12 into a single fix-pass. The goal of keeping them separate is reviewer clarity.

- [ ] **Step 5: Commit (only if compiles; otherwise fold into Task 12)**

```bash
git add -A
git commit -m "♻️ refactor(ai): migrate AiServiceProvider callers to AiModeService bean

AiController + AiBackendInitializer now inject AiModeService instead of
calling the AiServiceProvider static singleton. AiToolDescriptions deleted
(was only consumed by the removed AiTool adapter)."
```

---

## Task 12: Rewrite chat backends on ChatClient + ToolCallingAdvisor (delete ToolExecutor)

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackend.java`
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/OllamaLocalBackend.java`
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/ToolExecutor.java`
- Delete: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/MessageMapper.java` (if fully replaced)
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/ChatClientToolLoopTest.java`

**Interfaces:**
- Consumes: `AiModeService` (Task 10), Spring AI `ChatClient`, available `ToolCallback` beans
- Produces: chat backends that use Spring AI's non-deprecated tool-execution path; `AiStreamCallback` events still fired; `ToolExecutor` gone

> This is the highest-risk task. **Spike first:** before writing the test, read the [Spring AI Tool Calling reference](https://docs.spring.io/spring-ai/reference/api/tools.html) and confirm the exact `ChatClient.builder(chatModel).defaultAdvisors(new ToolCallingAdvisor(...)).build()` + `.prompt().tools(toolCallbacks).stream()` API for Spring AI 2.0.0. The current code uses `chatModel.stream(prompt)` + manual loop — the replacement must preserve streaming tokens + tool-call/tool-result SSE events.

- [ ] **Step 1: Spike — verify ChatClient + ToolCallingAdvisor streaming**

Create a throwaway test or `main` that: builds a `ChatClient` from a mock/scripted `ChatModel`, registers one `@Tool`, streams a prompt, and asserts (a) tokens stream via the reactive flow, (b) the tool is invoked, (c) the tool result is fed back and a final answer streams. Do NOT commit the spike. If the API differs from the plan's assumption, document the actual working pattern in this step's result and adapt the implementation steps.

- [ ] **Step 2: Write the integration test for the new cloud backend**

Create `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/ChatClientToolLoopTest.java`. Using a scripted `ChatModel` (mirror the old `SpringAiCloudBackendToolLoopTest`'s approach — read that file before deleting it to reuse the scripted model), assert: given a prompt that triggers a tool call, `chat()` delivers `onToken` deltas, an `onToolCall` event, an `onToolResult` event, and a final `onComplete`. (Exact assertions depend on the spike's confirmed API.)

- [ ] **Step 3: Rewrite SpringAiCloudBackend.runToolLoop**

Replace the body of `runToolLoop` (currently lines ~172–217) with a `ChatClient`-driven flow:
- Build/obtain a `ChatClient` from the `ChatModel` (cache it as a field, built once).
- Configure `ToolCallingAdvisor` (or pass tools via `.tools(...)`) so available `ToolCallback` beans are callable.
- `.prompt(messages).stream()` → subscribe/`doOnNext` to emit `callback.onToken(delta)`; detect tool-call/tool-result phases to emit `onToolCall`/`onToolResult`; on completion call `callback.onComplete(...)`.
- Remove the `ToolExecutor.executeAndFeed` call and the manual `MAX_TOOL_ROUNDS` loop (Spring AI handles the rounds).
- Keep the public `chat(...)` signatures, `AiStreamCallback` contract, and `isGenerating` semantics unchanged so `AiController` still works.

- [ ] **Step 4: Rewrite OllamaLocalBackend.runToolLoop the same way**

Apply the same `ChatClient`-based rewrite to `OllamaLocalBackend`. Remove its `AiServiceProvider.getTools()` (line ~212) and `ToolExecutor.executeAndFeed` (line ~207) usage.

- [ ] **Step 5: Delete ToolExecutor + MessageMapper + obsolete backend tests**

```bash
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/ToolExecutor.java
# MessageMapper mapped AiChatMessage<->Spring AI for the manual loop; if ChatClient
# now builds messages directly, delete it (verify nothing else imports it first):
grep -rn "MessageMapper" ZhiFlow/src/main --include="*.java"
# if only the backends used it and they're rewritten:
rm -f ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/MessageMapper.java
rm -f ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackendToolLoopTest.java
rm -f ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/OllamaLocalBackendConnectionTest.java
```

- [ ] **Step 6: Run the new test + full compile**

Run: `mvn -q -pl ZhiFlow -am test`
Expected: BUILD SUCCESS, `ChatClientToolLoopTest` PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "♻️ refactor(ai): backends use ChatClient + ToolCallingAdvisor (de-deprecate)

SpringAiCloudBackend + OllamaLocalBackend no longer use the deprecated
ChatModel-internal tool execution (removed in Spring AI 3.0). They now
build a ChatClient with ToolCallingAdvisor and stream via the supported
path. ToolExecutor + MessageMapper deleted; Spring AI handles the tool
loop and message mapping."
```

> After Task 12, the deletion in **Task 9** (hand-rolled contract layer) should now compile clean — go back and execute Task 9's steps if not already folded in.

---

## Task 13: Confirm a Spring AI @Tool bean is discoverable (spike-as-test)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/JsonFormatTool.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/tools/ToolDiscoveryTest.java`

**Interfaces:**
- Consumes: Spring AI tool-discovery config
- Produces: one real `@Tool` bean (`json_format`) replacing the deleted `BuiltinJsonFormatTool`; a test proving it's registered as a `ToolCallback`

This re-implements one of the deleted builtins as a Spring AI-native `@Tool` and proves the discovery mechanism works end-to-end — the template for all future AI tools.

- [ ] **Step 1: Write the failing test**

Create `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/tools/ToolDiscoveryTest.java`:
```java
package fan.summer.zhiflow.ai.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ToolDiscoveryTest {

    @Autowired
    Collection<ToolCallback> toolCallbacks;

    @Test
    void jsonFormatToolIsRegistered() {
        assertTrue(toolCallbacks.stream()
            .anyMatch(t -> t.getToolDefinition().name().equals("json_format")),
            "@Tool-annotated json_format should be discovered as a ToolCallback bean");
    }
}
```
> If `@SpringBootTest` is too heavy / the context doesn't start in tests, instead assert via the `ToolCallback` provider bean directly, or use a sliced test. Read existing `ZhiFlow` test setup (do any tests use `@SpringBootTest`? check `src/test`) to match the established pattern.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow test -Dtest=ToolDiscoveryTest`
Expected: FAIL — no `json_format` tool.

- [ ] **Step 3: Create the @Tool bean**

Create `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/JsonFormatTool.java`:
```java
package fan.summer.zhiflow.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** Replaces the deleted BuiltinJsonFormatTool as a Spring AI-native tool. */
@Component
public class JsonFormatTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Tool(description = "Pretty-print a JSON string. Returns formatted JSON.")
    public String jsonFormat(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return "Invalid JSON: " + e.getMessage();
        }
    }
}
```
> Confirm the method name produces the tool name you assert. Spring AI derives the tool name from the method name by default (`jsonFormat` → may register as `jsonFormat`, not `json_format`). Either rename the method to match, or set the name explicitly per the spike's confirmed API, and align the test assertion. Verify Jackson is available (it's a transitive Spring Boot dep — check it imports resolve).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow test -Dtest=ToolDiscoveryTest`
Expected: PASS.

- [ ] **Step 4b: Confirm name-based resolution works (spec §3.2.3)**

The `AgentRunner` (Task 15) and both chat backends (Task 12) resolve tools by name. Spring AI 2.0 auto-registers a `ToolCallbackResolver` bean when `ToolCallback`/`@Tool` beans are present — verify it exists rather than hand-rolling one:
```bash
# during the ToolDiscoveryTest run, log the context beans or add a one-off assertion:
#   assertNotNull(applicationContext.getBean(ToolCallbackResolver.class));
```
If the auto-config does NOT provide a `ToolCallbackResolver` (or you need mode/visibility filtering later per spec §3.2.4), add a tiny `@Configuration` that exposes a `ToolCallbackProvider`/`ToolCallbackResolver` aggregating the discovered `Collection<ToolCallback>`. Otherwise no extra bean is needed — record which is the case in this step's result so Tasks 12/15 rely on the confirmed mechanism.

> **Structured output schema (spec §3.2.2, goal 2):** `json_format` here returns a `String`, which is enough to prove discovery. To also demonstrate the structured-output half of the goal, either add a second `@Tool` method that returns a `record`/POJO (Spring AI 2.0 derives the outputSchema from the return type) and assert its `getToolDefinition()` carries a non-trivial output schema, OR document in the result that structured output is proven by the record-returning tool and defer richer examples to the plugin migration. At minimum, note that String-returning tools are the floor, record-returning tools are the recommended pattern for composability.

- [ ] **Step 5: Commit**

Re-implements the deleted BuiltinJsonFormatTool using @Tool. Test asserts
Spring AI discovers it as a ToolCallback — the template for all future
plugin AI tools."
```

---

## Task 14: Agent domain models

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/agent/AgentModels.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/agent/AgentModelsTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `AgentPlan`, `AgentStep`, `StepStatus`, `StepExecution`, `AgentRunStatus`, `AgentRun`, `AgentRunConfig`

- [ ] **Step 1: Write the failing test**

Create `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/agent/AgentModelsTest.java`:
```java
package fan.summer.zhiflow.ai.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AgentModelsTest {

    @Test
    void planAndStepConstruction() {
        AgentStep step = new AgentStep(0, "json_format", Map.of("json", "{}"), "format json", false);
        AgentPlan plan = new AgentPlan("goal", List.of(step), "because");
        assertEquals(1, plan.steps().size());
        assertEquals(StepStatus.PENDING, new StepExecution(0, StepStatus.PENDING, null).status());
    }

    @Test
    void runStartsPlanning() {
        AgentRun run = new AgentRun("run-1", "format this json", new AgentRunConfig(false, false, true, 3));
        assertEquals(AgentRunStatus.PLANNING, run.getStatus());
        assertEquals("format this json", run.getGoal());
        assertFalse(run.isCancelled());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow test -Dtest=AgentModelsTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Create AgentModels**

Create `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/agent/AgentModels.java` containing:
```java
public record AgentPlan(String goal, List<AgentStep> steps, String reasoning) {}
public record AgentStep(int index, String toolName, Map<String,Object> args,
                        String description, boolean requiresApproval) {}
public enum StepStatus { PENDING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, SKIPPED }
public record StepExecution(int index, StepStatus status, String result) {}
public enum AgentRunStatus { PLANNING, AWAITING_PLAN_APPROVAL, EXECUTING,
                             AWAITING_STEP_APPROVAL, COMPLETED, FAILED, CANCELLED }
public record AgentRunConfig(boolean requirePlanApproval, boolean requireStepApproval,
                             boolean replanOnFailure, int maxReplans) {}
public class AgentRun {
    private final String runId;
    private final String goal;            // the user goal — read by AgentRunner in planning
    private final AgentRunConfig config;
    private volatile AgentRunStatus status = AgentRunStatus.PLANNING;
    private volatile boolean cancelled = false;
    private volatile AgentPlan plan;
    private final List<StepExecution> executions = new java.util.concurrent.CopyOnWriteArrayList<>();
    // ctor(runId, goal, config), getters, setStatus, setPlan, addExecution,
    // markCancelled, isCancelled, approval-gate (CountDownLatch): requestApproval/approve/awaitApproval
}
```
Place each public type in its own file OR as package-private siblings in `AgentModels.java` if the project permits nested — but Java requires each `public` top-level type in its own file. **Split into separate files:** `AgentPlan.java`, `AgentStep.java`, `StepStatus.java`, `StepExecution.java`, `AgentRunStatus.java`, `AgentRunConfig.java`, `AgentRun.java` under the `agent` package. Implement `AgentRun` with a `CountDownLatch approvalGate` field + `requestApproval()`/`approve()`/`awaitApproval()` methods for the approval gate, and `markCancelled()`/`isCancelled()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow test -Dtest=AgentModelsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "✨ feat(agent): add Plan-and-Execute domain models

AgentPlan/Step/Run + statuses + config records. AgentRun holds the
state machine + approval-gate synchronization primitives."
```

---

## Task 15: AgentRunner (Plan-and-Execute runtime)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/agent/AgentRunner.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/agent/AgentEventSink.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/agent/AgentRunnerTest.java`

**Interfaces:**
- Consumes: `AgentModels` (Task 14), `ChatClient` + available `ToolCallback` beans, `AgentEventSink` (SSE callback interface)
- Produces: `AgentRunner.run(AgentRun run, AgentEventSink sink)` driving PLANNING → (approval) → EXECUTING → (replan on failure ≤ maxReplans) → COMPLETE/FAILED. The `AgentRun` (created by the caller with goal + config) carries all state.

**`AgentEventSink`** (a small interface so the runner is testable without SSE):
```java
public interface AgentEventSink {
    void onPlanToken(String delta);
    void onPlanReady(AgentPlan plan);
    void onPlanApprovalRequested();
    void onStepStart(int index);
    void onStepComplete(int index, String result);
    void onStepApprovalRequested(int index);
    void onComplete(String summary);
    void onError(String message);
}
```

- [ ] **Step 1: Write the failing test (happy path, no approval, mock tool)**

Create `AgentRunnerTest.java`. Use a scripted `ChatClient`/planner that returns a fixed `AgentPlan` with one step calling a mock tool that succeeds. Assert the `AgentEventSink` receives, in order: `onPlanReady`, `onStepStart(0)`, `onStepComplete(0, ...)`, `onComplete(...)`. Then a second test: the tool fails and `maxReplans=1` → a second plan is requested → `onComplete` (or `onError` if replans exhausted). Use plain Mockito (or hand-rolled fakes) — keep Spring context out of this unit test.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl ZhiFlow test -Dtest=AgentRunnerTest`
Expected: FAIL — `AgentRunner` doesn't exist.

- [ ] **Step 3: Implement AgentRunner**

Implement `run(AgentRun run, AgentEventSink sink)` as in spec §3.3.2 (read `run.getConfig()` and `run.getRunId()`; the goal comes from the plan the LLM generates for the goal passed to the run). To keep the signature clean, the controller constructs the `AgentRun` with the goal stored on it — so add a `goal` field to `AgentRun` (Task 14) and read `run.getGoal()` here:
1. PLANNING: build a planning prompt (`run.getGoal()` + list of available tools' name/description/inputSchema from the `ToolCallback`s), stream via `ChatClient`, parse the returned JSON into an `AgentPlan`, emit `onPlanToken`/`onPlanReady`.
2. If `config.requirePlanApproval`: `run.setStatus(AWAITING_PLAN_APPROVAL)`; `sink.onPlanApprovalRequested()`; `run.awaitApproval()` (blocks on the latch until `AgentController` calls `approve`); if cancelled → CANCELLED.
3. EXECUTING: for each step — if step `requiresApproval` && config → await approval; `sink.onStepStart`; execute the step's tool via `ToolCallingManager` (resolve by `toolName`, call with `args`); `sink.onStepComplete`. Append result to conversation history (ChatClient/ToolCallingManager handle this). On failure + `replanOnFailure` + replans remaining → back to PLANNING with failure context (≤ `maxReplans`).
4. `onComplete(summary)` or `onError`. Check `run.isCancelled()` before each step.

Run the whole thing on a virtual thread (`Thread.ofVirtual().start(...)`), mirroring `SpringAiCloudBackend.chat`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl ZhiFlow test -Dtest=AgentRunnerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "✨ feat(agent): add AgentRunner (Plan-and-Execute runtime)

LLM plans a multi-step tool sequence; optional plan/step approval gates;
sequential execution via ToolCallingManager; re-plans on failure up to
maxReplans. Events flow to an AgentEventSink (SSE-agnostic for testability)."
```

---

## Task 16: AgentController + SSE stream

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/AgentController.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/agent/AgentRunRegistry.java`

**Interfaces:**
- Consumes: `AgentRunner` (Task 15), `AgentModels` (Task 14), available `ToolCallback` beans (for `/api/agent/tools`)
- Produces: `POST /api/agent/run` → `{runId}`; `GET /api/agent/stream?runId=` (SSE); `POST /api/agent/{runId}/approve`; `POST /api/agent/{runId}/cancel`; `GET /api/agent/tools` → orchestrable tool list (name/description/inputSchema) for the frontend + Phase 2 canvas (spec §3.6.1)

- [ ] **Step 1: Create AgentRunRegistry**

A `@Component` holding `Map<String, AgentRun>` (ConcurrentHashMap) so the controller can look up a run by id for approve/cancel/stream. `create(goal, config) → AgentRun`, `get(id)`, `remove(id)`.

- [ ] **Step 2: Create AgentController**

```java
@RestController
public class AgentController {
    private final AgentRunner runner;
    private final AgentRunRegistry registry;
    // constructor injection

    @PostMapping("/api/agent/run")
    public Map<String,String> run(@RequestBody AgentRunRequest req) {
        AgentRun run = registry.create(req.goal(), req.config());
        runner.run(run, sinkFor(run));   // spawns virtual thread
        return Map.of("runId", run.getRunId());
    }

    @GetMapping(value = "/api/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String runId) { /* emit buffered + live events */ }

    @PostMapping("/api/agent/{runId}/approve")
    public void approve(@PathVariable String runId, @RequestBody(required=false) AgentPlan edited) {
        registry.get(runId).approve(edited);
    }

    @PostMapping("/api/agent/{runId}/cancel")
    public void cancel(@PathVariable String runId) { registry.get(runId).markCancelled(); }

    // spec §3.6.1: list orchestrable tools (name/desc/inputSchema) for the
    // frontend agent UI + Phase 2 canvas. Sourced from Spring AI-discovered
    // ToolCallback beans (inject Collection<ToolCallback> or a ToolCallbackProvider).
    @GetMapping("/api/agent/tools")
    public List<Map<String,Object>> tools() {
        return toolCallbacks.stream().map(tc -> {
            var def = tc.getToolDefinition();
            return Map.<String,Object>of(
                "name", def.name(),
                "description", def.description(),
                "inputSchema", def.inputSchema());
        }).toList();
    }
}
```
The `sinkFor(run)` returns an `AgentEventSink` that writes SSE events to an emitter associated with the run (buffer events that arrive before the client connects to `/stream`, mirroring how `AiController` handles its streamId/stash).

For `/api/agent/tools`, inject the Spring AI-discovered tools — `private final Collection<ToolCallback> toolCallbacks;` via constructor (same collection Task 12/13 rely on). This is the single source of truth for "what can the agent orchestrate", consumed by `AiAgent.vue` (Task 20) and the Phase 2 canvas.

- [ ] **Step 3: Verify compile + manual smoke**

Run: `mvn -q -pl ZhiFlow compile`
Expected: BUILD SUCCESS. (Full SSE integration testing is heavy; rely on the AgentRunner unit test from Task 15 + a manual smoke with curl against a running backend. Add an `@WebMvcTest` slice asserting the run endpoint returns a runId if time permits.) Also `curl localhost:24056/api/agent/tools` should list `json_format` (Task 13) with its inputSchema.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "✨ feat(web): add AgentController (run/stream/approve/cancel/tools)

POST /api/agent/run starts a run; GET /api/agent/stream streams plan +
step events as SSE; approve/cancel endpoints drive the approval gate
and cancellation flag via AgentRunRegistry. GET /api/agent/tools lists
Spring AI-discovered ToolCallbacks (name/desc/inputSchema) for the agent
UI + Phase 2 canvas."
```

---

## Task 17: Frontend vue-i18n setup + locale files + host locale wiring

**Files:**
- Create: `frontend/src/i18n/index.ts`, `frontend/src/i18n/en.json`, `frontend/src/i18n/zh.json`
- Modify: `frontend/src/main.ts`
- Modify: `frontend/src/stores/settings.ts`
- Modify: `frontend/package.json`

**Interfaces:**
- Consumes: `LanguageName` (`'en'|'zh'`, already in `api/types.ts`)
- Produces: a vue-i18n instance with `en`/`zh` locales; host `language` setting drives the active locale reactively; `i18n.global.t` available app-wide

- [ ] **Step 1: Add vue-i18n dependency**

Run: `cd frontend && npm i vue-i18n@^10`
Verify `vue-i18n` appears in `package.json` dependencies.

- [ ] **Step 2: Create locale files**

Create `frontend/src/i18n/en.json`:
```json
{
  "category": { "dev": "Developer", "text": "Text", "image": "Image",
                "net": "Network", "ai": "AI", "other": "Other" },
  "source":   { "official": "Official", "third_party": "Third-party" },
  "sidebar":  { "all": "All Tools", "favorites": "Favorites", "categories": "CATEGORIES",
                "aiChat": "AI Chat", "settings": "Settings", "agent": "AI Agent",
                "expand": "Expand", "collapse": "Collapse" },
  "grid":     { "title": "Tools", "loading": "Loading plugins…",
                "empty": "No tools in this category." },
  "settings": { "title": "Settings", "theme": "Theme", "language": "Language",
                "dark": "Dark", "light": "Light" },
  "agent":    { "title": "AI Agent", "goalPlaceholder": "What should I do?",
                "run": "Plan", "approve": "Approve", "cancel": "Cancel" },
  "common":   { "back": "Back", "retry": "Retry", "loading": "Loading…" }
}
```
Create `frontend/src/i18n/zh.json` with the same keys, Chinese values (e.g. `category.dev` → `"开发者"`, `source.official` → `"官方"`, `sidebar.all` → `"全部工具"`, etc.). The `category.*`/`source.*` keys MUST match `ToolCategory.getLabelKey()`/`PluginSource.getLabelKey()` exactly (Task 4/6).

- [ ] **Step 3: Create the i18n instance**

Create `frontend/src/i18n/index.ts`:
```ts
import { createI18n } from 'vue-i18n'
import en from './en.json'
import zh from './zh.json'

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, zh },
})

export type MessageKey = string // keys are dotted paths
```

- [ ] **Step 4: Install i18n in main.ts**

Edit `frontend/src/main.ts`:
- `import { i18n } from '@/i18n'`
- After `app.use(router)` (or after pinia), add `app.use(i18n)`.
- After `useSettingsStore().load()` resolves, set the initial locale: `i18n.global.locale.value = settings.language` (with `legacy:false`, `i18n.global.locale` is a ref). Mirror the existing pattern where settings load happens before mount.

- [ ] **Step 5: Reactively switch locale on language change**

Edit `frontend/src/stores/settings.ts`: in `setLanguage(next)` (after `update({ language: next })`), also set `i18n.global.locale.value = next`. Import `i18n` from `@/i18n`. Now changing language in Settings instantly re-translates the host UI — the single language-entry-point requirement.

- [ ] **Step 6: Verify build**

Run: `cd frontend && npm run build`
Expected: `vue-tsc --noEmit && vite build` succeeds (no TS errors). If `vue-i18n` type setup complains about missing `vue-i18n` types or `defineI18nConfig`, follow its v10 docs for the TS shim.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "✨ feat(frontend): add vue-i18n (host full i18n), wire language setting

Introduces vue-i18n with en/zh locales covering host shell strings
(category/source/sidebar/grid/settings/agent/common). The host language
setting is now reactive: changing it in Settings updates i18n.locale
immediately. Host is the single language source per the i18n constraint."
```

---

## Task 18: Dynamic sidebar categories + i18n host strings migration

**Files:**
- Modify: `frontend/src/api/types.ts` (add `CategoryDescriptor`, `PluginSource`; extend `PluginDescriptor` with `supportsAi`/`source`; add `'AI'` to `ToolCategory`)
- Modify: `frontend/src/api/client.ts` (add `getPluginCategories()`)
- Create: `frontend/src/stores/categories.ts`
- Modify: `frontend/src/stores/nav.ts` (relax NavCategory type)
- Modify: `frontend/src/shell/Sidebar.vue` (dynamic categories, i18n labels, agent nav)
- Modify: `frontend/src/views/ToolGrid.vue` (source/AI badges on cards, spec §3.1.2/§3.1.5), `Settings.vue`, `AiChat.vue`, `PluginView.vue`, `shell/StatusBar.vue` (hardcoded → `$t()`)

**Interfaces:**
- Consumes: `GET /api/plugin-categories` (Task 8), `/api/plugins` now carrying `supportsAi`/`source`/`category=AI` (Task 6), vue-i18n (Task 17)
- Produces: sidebar rendered from backend categories; plugin cards show Official/Third-party + AI badges; all host UI strings localized

- [ ] **Step 1: Add types + API method**

Edit `frontend/src/api/types.ts`:
- Add `'AI'` to the `ToolCategory` union: `export type ToolCategory = 'TEXT' | 'IMAGE' | 'DEV' | 'NET' | 'AI' | 'OTHER'`.
- Add the `PluginSource` type: `export type PluginSource = 'OFFICIAL' | 'THIRD_PARTY'`.
- Extend `PluginDescriptor` with the two new backend fields (Task 6):
```ts
export interface PluginDescriptor {
  id: string
  name: string
  description: string
  category: ToolCategory
  icon: string
  iconStyle: string
  version: string
  uiEntry: string
  supportsAi: boolean   // NEW — drives the AI badge on the card
  source: PluginSource  // NEW — drives the Official/Third-party badge
}
```
- Add the category descriptor:
```ts
export interface CategoryDescriptor { id: string; labelKey: string; icon: string }
```
Edit `frontend/src/api/client.ts` — add:
```ts
getPluginCategories: () => http.get<CategoryDescriptor[]>('/api/plugin-categories').then(r => r.data),
```

- [ ] **Step 2: Create categories store**

Create `frontend/src/stores/categories.ts`:
```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { CategoryDescriptor } from '@/api/types'

export const useCategoriesStore = defineStore('categories', () => {
  const categories = ref<CategoryDescriptor[]>([])
  async function load() { categories.value = await api.getPluginCategories() }
  return { categories, load }
})
```

- [ ] **Step 3: Relax NavCategory + make Sidebar dynamic**

Edit `frontend/src/stores/nav.ts`: change `NavCategory` to `string` (or add `'ai'` to the union) so backend category ids fit. `category = ref<string>('all')`.

Edit `frontend/src/shell/Sidebar.vue`:
- Remove the hardcoded `categories: CatItem[]` array (lines ~20-28).
- Load categories on mount: `const cats = useCategoriesStore(); onMounted(() => cats.load())`.
- Build the nav list reactively: `[{ key: 'all', labelKey: 'sidebar.all', icon: '▦' }, ...cats.categories.map(c => ({ key: c.id, labelKey: c.labelKey, icon: c.icon })), { key: 'favorites', labelKey: 'sidebar.favorites', icon: '★' }]`.
- Render labels with `$t(item.labelKey)`.
- Add an "AI Agent" footer nav button (alongside AI Chat / Settings) → `router.push('/agent')`, labeled `$t('sidebar.agent')`.

- [ ] **Step 4: Migrate hardcoded host strings to $t()**

In each listed view, replace English literals with `$t('...')`:
- `ToolGrid.vue`: `Tools`→`$t('grid.title')`, `Loading plugins…`→`$t('grid.loading')`, `No tools...`→`$t('grid.empty')`.
- `Settings.vue`: headers/options → `$t('settings.*')`.
- `AiChat.vue`: `AI Chat`→`$t('sidebar.aiChat')`, etc.
- `PluginView.vue`: `← Back`→`$t('common.back')`, etc.
- `StatusBar.vue`: `Connecting…`/`Connected`/`Reconnecting…`→ add keys + `$t`.
(Use `const { t } = useI18n()` in `<script setup>` and `t('key')` in script, `$t('key')` in template.)

- [ ] **Step 4b: Add source + AI badges to plugin cards (spec §3.1.2/§3.1.5)**

Edit `frontend/src/views/ToolGrid.vue`. On each plugin card, render:
- A **source badge** from `descriptor.source`, label via `$t('source.official')` / `$t('source.third_party')` (map `OFFICIAL`→`source.official`, `THIRD_PARTY`→`source.third_party`). Style OFFICIAL vs THIRD_PARTY distinctly (e.g. accent vs neutral chip).
- An **AI badge** shown only when `descriptor.supportsAi === true`, label via `$t('category.ai')` (or a dedicated `$t('badge.ai')` key — add it to `en.json`/`zh.json` if you want distinct wording).

Example:
```vue
<span class="card-badge" :class="p.source === 'OFFICIAL' ? 'badge-official' : 'badge-third'">
  {{ $t(p.source === 'OFFICIAL' ? 'source.official' : 'source.third_party') }}
</span>
<span v-if="p.supportsAi" class="card-badge badge-ai">{{ $t('category.ai') }}</span>
```
Add the `.card-badge`/`.badge-*` styles to the component's `<style scoped>` (token-based: `var(--sk-accent)` etc.). These two badges are the frontend half of the `source`/`supportsAi` descriptor fields — without them those backend fields are invisible.

- [ ] **Step 5: Verify build + typecheck**

Run: `cd frontend && npm run build`
Expected: success.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "✨ feat(frontend): dynamic backend-driven sidebar + card badges + host i18n

Sidebar fetches categories from /api/plugin-categories and renders them
with vue-i18n labels (no more hardcoded list). PluginDescriptor gains
supportsAi/source; plugin cards now show Official/Third-party + AI badges.
All host shell strings migrated to \$t(). Adds AI Agent nav entry."
```

---

## Task 19: Plugin micro-frontend locale context (plugins follow host)

**Files:**
- Modify: `frontend/src/mf/loader.ts` (extend PluginContext)
- Modify: `frontend/src/views/PluginView.vue` (inject locale + t + onLocaleChange)

**Interfaces:**
- Consumes: vue-i18n (Task 17)
- Produces: `PluginContext` has `locale: string`, `t: (key)=>string`, `onLocaleChange: (cb)=>()=>void`; plugins re-render when the host language changes

- [ ] **Step 1: Extend the PluginContext interface**

Edit `frontend/src/mf/loader.ts` `PluginContext`:
```ts
export interface PluginContext {
  api: { invoke: (action: string, args?: Record<string, unknown>) => Promise<unknown> }
  theme: 'dark' | 'light'
  onThemeChange: (cb: (t: 'dark' | 'light') => void) => () => void
  // NEW — locale follows the host; plugins must NOT ship a language switcher
  locale: string
  t: (key: string) => string
  onLocaleChange: (cb: (locale: string) => void) => () => void
  notify: (msg: string) => void
}
```
Remove the old `i18n: (key: string) => string` field (replaced by `t`).

- [ ] **Step 2: Inject the locale context in PluginView**

Edit `frontend/src/views/PluginView.vue` ctx object (line ~47-55): replace `i18n: (key) => key` with:
```ts
locale: i18n.global.locale.value,
t: (key: string) => i18n.global.t(key),
onLocaleChange: (cb: (locale: string) => void) => {
  const unwatch = watch(() => i18n.global.locale.value, (l) => cb(l as string))
  return unwatch
},
```
Import `i18n` from `@/i18n` and `watch` from `vue`. Now when the host language changes, every loaded plugin's `onLocaleChange` callback fires → plugins re-render in the new locale. The plugin reads `ctx.locale` to pick its own bundled locale resources.

- [ ] **Step 3: Verify build**

Run: `cd frontend && npm run build`
Expected: success.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "✨ feat(mf): plugin context carries locale + t + onLocaleChange

Plugins follow the host language. PluginContext exposes the current
locale, the host's t() for shared keys, and an onLocaleChange hook so
plugins re-render when the host language switches. Plugins must NOT
provide their own language switcher."
```

---

## Task 20: AiAgent.vue (minimal) + route

**Files:**
- Create: `frontend/src/views/AiAgent.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/api/client.ts` (agent endpoints)
- Modify: `frontend/src/api/types.ts` (agent types)

**Interfaces:**
- Consumes: `POST /api/agent/run`, `GET /api/agent/stream` (SSE), `GET /api/agent/tools` (Task 16), vue-i18n (Task 17)
- Produces: a minimal agent UI — goal input, available-tools hint, plan display, step progress, approve/cancel buttons

- [ ] **Step 1: Add agent API + types**

Edit `api/types.ts` — add:
```ts
export interface AgentRunConfig { requirePlanApproval: boolean; requireStepApproval: boolean; replanOnFailure: boolean; maxReplans: number }
export interface AgentRunRequest { goal: string; config: AgentRunConfig }
export interface AgentRunResponse { runId: string }
export interface AgentStep { index: number; toolName: string; description: string; status: string }
export interface AgentPlan { goal: string; steps: AgentStep[]; reasoning: string }
export interface AgentTool { name: string; description: string; inputSchema: string }
```
Edit `api/client.ts` — add:
```ts
agentRun: (req: AgentRunRequest) => http.post<AgentRunResponse>('/api/agent/run', req).then(r => r.data),
agentApprove: (runId: string, plan?: AgentPlan) => http.post(`/api/agent/${runId}/approve`, { plan }).then(r => r.data),
agentCancel: (runId: string) => http.post(`/api/agent/${runId}/cancel`).then(r => r.data),
agentTools: () => http.get<AgentTool[]>('/api/agent/tools').then(r => r.data),
```

- [ ] **Step 2: Create AiAgent.vue**

A `<script setup>` component: goal `<textarea>` + "Plan" button → `api.agentRun({goal, config})` → open an `EventSource` on `/api/agent/stream?runId=...`. Parse SSE event types (`plan_ready`, `step_start`, `step_complete`, `plan_approval_requested`, `complete`, `error`) and update reactive `plan`/`steps`/`status`. Show an "Approve" button when `plan_approval_requested` arrives → `api.agentApprove(runId)`. All labels via `t('agent.*')`.

- [ ] **Step 3: Add the route**

Edit `frontend/src/router/index.ts` `routes` array — add:
```ts
{ path: '/agent', name: 'agent', component: () => import('@/views/AiAgent.vue') },
```

- [ ] **Step 4: Verify build**

Run: `cd frontend && npm run build`
Expected: success.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "✨ feat(frontend): add AiAgent.vue (minimal Plan-and-Execute UI)

Goal input → /api/agent/run → SSE stream of plan/step events. Plan
display, step progress, approve/cancel buttons. All strings i18n'd.
Canvas-based editing deferred to Phase 2."
```

---

## Task 21: Final full build + regression sweep

**Files:** none (verification only)

- [ ] **Step 1: Full backend build + tests**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Full frontend build**

Run: `cd frontend && npm run build`
Expected: success (vue-tsc + vite).

- [ ] **Step 3: Spec coverage spot-check**

Re-read `docs/superpowers/specs/2026-07-09-ai-flow-orchestration-design.md` and confirm each item maps to a task above. Cover:
- **§1.5** — the 7 goals.
- **§3.8** — the delete list.
- **§3.6.1** — the endpoint table: `/api/agent/run`, `/api/agent/stream`, `/api/agent/{id}/approve`, `/api/agent/{id}/cancel`, **`/api/agent/tools`** (Task 16), `/api/plugin-categories` (Task 8). Confirm none is missing.
- **§3.6.2 / §3.1.2 / §3.1.5** — frontend surfaces: dynamic sidebar, plugin-card **source + AI badges** (Task 18 Step 4b).
- **§3.7.3 / §3.8** — legacy JavaFX i18n keys in `ZhiFlow/src/main/resources/i18n/messages*.properties` (`sidebar.label.*`/`detail.*`/`store.*`) are dead under headless. Optional cleanup: `grep -rn "sidebar.label\|detail.category\|store.online.category\|detail.tag" ZhiFlow/src` — if only the properties files match, delete those keys (or leave them, since nothing consumes them — note the choice). Not blocking.

Note any gap and open a follow-up task rather than silently skipping.

- [ ] **Step 4: Manual smoke (if a backend can run)**

Start the backend, then:
- `GET /api/plugin-categories` → expect AI + `category.*` keys.
- `GET /api/plugins` → expect `supportsAi` + `source` fields on each descriptor.
- `GET /api/agent/tools` → expect `json_format` with a non-empty `inputSchema`.
- `POST /api/agent/run` + `GET /api/agent/stream` → expect plan/step SSE events.
- Switch language in Settings → confirm the host UI + a loaded plugin re-render, and plugin cards show translated source/AI badges.

- [ ] **Step 5: Final commit (docs/changelog if any)**

If the build introduced doc drift, update `docs/` minimally and commit. Otherwise nothing to commit here.
