# Builtin AI Tool Adaptation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor 16 builtin AI tools so plugins self-declare them via `SwissKitJPlugin.aiTools()`, tools declare cloud/local capability + dual descriptions, and all schemas/descriptions/return JSON follow unified contracts.

**Architecture:** Three-layer change executed in three commits — (1) API surface + filtering infrastructure, (2) tool migration into plugins, (3) schema/description/return contract standardization. Each commit independently compilable and revertable.

**Tech Stack:** Java 21, JavaFX, JUnit 5, Gson, LangChain4j, MyBatis, H2.

**Spec:** `docs/superpowers/specs/2026-06-22-builtin-ai-tool-adaptation-design.md`

**Note on commit granularity:** The spec §7 described "3 commits" as a logical grouping. This plan follows the writing-plans convention of one commit per task (~20 commits total), still in three phases: Tasks 1-8 (API + infra), Tasks 9-15 (tool migration), Tasks 16-20 (contract). Each commit is small and independently revertable; if a single squashed commit per phase is desired at PR time, that's a separate decision.

---

## File Structure

### Files Created
| Path | Purpose |
|---|---|
| `SwissKit/src/main/java/fan/summer/ai/tools/AiToolDescriptions.java` | Pick description/params by current mode |
| `SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserAutomatePlugin.java` | UI-less plugin host for BrowserAutomateTool |
| `SwissKit/src/test/java/fan/summer/api/ai/AiToolCloudLocalDefaultsTest.java` | Verify AiTool default methods |
| `SwissKit/src/test/java/fan/summer/api/ai/AiServiceProviderModeFilterTest.java` | Verify mode-based filtering |
| `SwissKit/src/test/java/fan/summer/ai/tools/AiToolDescriptionsTest.java` | Verify description selection |
| `SwissKit/src/test/java/fan/summer/ai/tools/ToolSchemaBuilderLocalTest.java` | Verify prompt uses local desc |
| `SwissKit/src/test/java/fan/summer/ai/adapter/AiToolToToolSpecificationLocalTest.java` | Verify LC4j uses local desc/params |
| `SwissKit/src/test/java/fan/summer/ai/tools/ToolExecutorErrorJsonTest.java` | Verify catch returns JSON |
| `SwissKit/src/test/java/fan/summer/plugin/PluginRegistryAiToolsTest.java` | Verify plugin add/remove → tool reg/unreg |
| `SwissKit/src/test/java/fan/summer/api/SwissKitJPluginAiToolsDefaultTest.java` | Verify default aiTools() returns empty |

### Files Modified
| Path | Change |
|---|---|
| `SwissKitJ-Api/.../ai/AiTool.java` | +4 default methods |
| `SwissKitJ-Api/.../api/SwissKitJPlugin.java` | +`aiTools()` default |
| `SwissKitJ-Api/.../ai/AiServiceProvider.java` | Mode filter in `getTools()` |
| `SwissKit/.../plugin/PluginRegistry.java` | +`toolsByPlugin`, `registerPluginTools`, `unregisterPluginTools`; `addPlugins` public |
| `SwissKit/.../ai/tools/ToolSchemaBuilder.java` | Use `AiToolDescriptions` |
| `SwissKit/.../ai/adapter/AiToolToToolSpecification.java` | Use `AiToolDescriptions` |
| `SwissKit/.../ai/tools/ToolExecutor.java` | Catch returns JSON |
| `SwissKit/.../registrar/BuiltinToolRegistrar.java` | Use `addPlugins`, add BrowserAutomatePlugin |
| `SwissKit/.../app/SwissKitJApp.java` | Remove BuiltinAiToolRegistrar call |
| 10 plugin files | Override `aiTools()` |
| 16 AI tool files | Schema fixes, descriptions, return structures |
| `CLAUDE.md` | Document `aiTools()` + cloud/local capability |
| `CHANGELOG.md` | Note the new capabilities |

### Files Deleted
| Path | Reason |
|---|---|
| `SwissKit/.../ai/tools/BuiltinAiToolRegistrar.java` | Replaced by plugin-owned `aiTools()` |

---

# Commit 1: API + Filtering Infrastructure

**Goal:** Land all API surface and filtering logic. No plugin overrides `aiTools()` yet — BuiltinAiToolRegistrar still provides all 16 tools via the old path. Safe intermediate state.

---

## Task 1: AiTool gains 4 default methods (cloud/local)

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiTool.java`
- Test: `SwissKit/src/test/java/fan/summer/api/ai/AiToolCloudLocalDefaultsTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/api/ai/AiToolCloudLocalDefaultsTest.java`:

```java
package fan.summer.zhiflow.api.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolCloudLocalDefaultsTest {

    /** Minimal AiTool that relies entirely on defaults for the 4 new methods. */
    private static final AiTool DEFAULTS = new AiTool() {
        public String getName() { return "defaults"; }
        public String getDescription() { return "cloud-desc"; }
        public List<AiToolParam> getParameters() {
            return List.of(AiToolParam.of("p", "string", "param"));
        }
        public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
    };

    @Test
    void localDescriptionFallsBackToCloud() {
        assertEquals("cloud-desc", DEFAULTS.getLocalDescription());
    }

    @Test
    void localParametersFallBackToCloud() {
        assertEquals(DEFAULTS.getParameters(), DEFAULTS.getLocalParameters());
    }

    @Test
    void supportsLocalDefaultsTrue() {
        assertTrue(DEFAULTS.supportsLocal());
    }

    @Test
    void supportsCloudDefaultsTrue() {
        assertTrue(DEFAULTS.supportsCloud());
    }

    @Test
    void canOverrideAllFour() {
        AiTool custom = new AiTool() {
            public String getName() { return "custom"; }
            public String getDescription() { return "cloud"; }
            public String getLocalDescription() { return "local"; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public List<AiToolParam> getLocalParameters() { return List.of(); }
            public boolean supportsLocal() { return false; }
            public boolean supportsCloud() { return true; }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
        assertEquals("local", custom.getLocalDescription());
        assertTrue(custom.getLocalParameters().isEmpty());
        assertFalse(custom.supportsLocal());
        assertTrue(custom.supportsCloud());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run via IDEA MCP: `mcp__idea__build_project` with `filesToRebuild: ["SwissKit/src/test/java/fan/summer/api/ai/AiToolCloudLocalDefaultsTest.java"]`. Expected: compile error — `AiTool` has no `getLocalDescription`, `getLocalParameters`, `supportsLocal`, `supportsCloud`.

- [ ] **Step 3: Add the 4 default methods to AiTool**

Append to `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiTool.java` (before the closing brace):

```java

    /**
     * Local-mode description (short, keyword-dense, tuned for small local models
     * like Qwen3-4B). Default falls back to {@link #getDescription()}.
     *
     * @return description shown to the model in local mode
     */
    default String getLocalDescription() { return getDescription(); }

    /**
     * Local-mode parameter list (may be a simplified subset of
     * {@link #getParameters()}). Default falls back to {@link #getParameters()}.
     *
     * @return parameters shown to the model in local mode
     */
    default java.util.List<AiToolParam> getLocalParameters() { return getParameters(); }

    /**
     * Whether this tool is visible when the active backend is local.
     * Default {@code true} — override to {@code false} for tools that require
     * strong-model reasoning (e.g. tools that drive their own think-act loop).
     *
     * @return {@code true} if the tool should be visible in local mode
     */
    default boolean supportsLocal() { return true; }

    /**
     * Whether this tool is visible when the active backend is cloud
     * (OpenAI / Anthropic). Default {@code true}.
     *
     * @return {@code true} if the tool should be visible in cloud mode
     */
    default boolean supportsCloud() { return true; }
```

- [ ] **Step 4: Build API module**

Run `mvn install -f SwissKitJ-Api/pom.xml -DskipTests` via IDEA Maven (or `mcp__idea__build_project` with `projectPath: "SwissKitJ-Api"`). Expected: BUILD SUCCESS.

- [ ] **Step 5: Run test to verify it passes**

Via IDEA MCP build the test file. Expected: all 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiTool.java \
        SwissKit/src/test/java/fan/summer/api/ai/AiToolCloudLocalDefaultsTest.java
git commit -m "$(cat <<'EOF'
✨ feat(ai): AiTool interface gains cloud/local capability + dual descriptions

Adds 4 default methods to AiTool so each tool can declare its visibility
per backend mode and serve a separate (typically shorter) description
and parameter list to local mode. Defaults preserve existing behavior.
EOF
)"
```

---

## Task 2: AiServiceProvider.getTools() filters by current mode

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java:166-173`
- Test: `SwissKit/src/test/java/fan/summer/api/ai/AiServiceProviderModeFilterTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/api/ai/AiServiceProviderModeFilterTest.java`:

```java
package fan.summer.zhiflow.api.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiServiceProviderModeFilterTest {

    private static AiTool tool(String name, boolean local, boolean cloud) {
        return new AiTool() {
            public String getName() { return name; }
            public String getDescription() { return name; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public boolean supportsLocal() { return local; }
            public boolean supportsCloud() { return cloud; }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @BeforeEach
    void reset() {
        AiServiceProvider.clearTools();
        AiServiceProvider.clearConstrainedTool();
        AiServiceProvider.setCurrentMode("local");
    }

    @AfterEach
    void cleanup() {
        AiServiceProvider.clearTools();
        AiServiceProvider.clearConstrainedTool();
        AiServiceProvider.setCurrentMode("local");
    }

    @Test
    void localModeHidesCloudOnlyTool() {
        AiServiceProvider.registerTool(tool("both", true, true));
        AiServiceProvider.registerTool(tool("cloudOnly", false, true));

        AiServiceProvider.setCurrentMode("local");
        List<String> names = AiServiceProvider.getTools().stream().map(AiTool::getName).toList();
        assertEquals(List.of("both"), names);
    }

    @Test
    void cloudModeHidesLocalOnlyTool() {
        AiServiceProvider.registerTool(tool("both", true, true));
        AiServiceProvider.registerTool(tool("localOnly", true, false));

        AiServiceProvider.setCurrentMode("openai");
        List<String> names = AiServiceProvider.getTools().stream().map(AiTool::getName).toList();
        assertEquals(List.of("both"), names);
    }

    @Test
    void modeSwitchChangesVisibilityImmediately() {
        AiServiceProvider.registerTool(tool("cloudOnly", false, true));
        AiServiceProvider.setCurrentMode("local");
        assertTrue(AiServiceProvider.getTools().isEmpty());

        AiServiceProvider.setCurrentMode("anthropic");
        assertEquals(1, AiServiceProvider.getTools().size());
    }

    @Test
    void constrainedToolStillFilteredByMode() {
        AiServiceProvider.registerTool(tool("cloudOnly", false, true));
        AiServiceProvider.setCurrentMode("local");
        AiServiceProvider.setConstrainedTool("cloudOnly");
        assertTrue(AiServiceProvider.getTools().isEmpty(),
                "Constrained filter should not bypass mode filter");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Build the test file. Expected: test fails — `cloudOnly` is still visible in local mode (current `getTools()` ignores mode).

- [ ] **Step 3: Update `getTools()` to filter by mode**

Edit `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java:166-173`:

```java
    public static List<AiTool> getTools() {
        boolean isLocal = "local".equals(currentMode);
        String filter = constrainedTool;

        return tools.values().stream()
            .filter(t -> isLocal ? t.supportsLocal() : t.supportsCloud())
            .filter(t -> filter == null || filter.equals(t.getName()))
            .toList();
    }
```

- [ ] **Step 4: Build API module + run test**

Run `mvn install -f SwissKitJ-Api/pom.xml -DskipTests` then build test file. Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java \
        SwissKit/src/test/java/fan/summer/api/ai/AiServiceProviderModeFilterTest.java
git commit -m "$(cat <<'EOF'
✨ feat(ai): AiServiceProvider.getTools filters by current backend mode

Local mode shows only tools where supportsLocal()==true; cloud modes show
only tools where supportsCloud()==true. Mode filter is applied before the
constrained-tool filter so slash-command guidance cannot bypass mode
visibility.
EOF
)"
```

---

## Task 3: AiToolDescriptions helper

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/AiToolDescriptions.java`
- Test: `SwissKit/src/test/java/fan/summer/ai/tools/AiToolDescriptionsTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/tools/AiToolDescriptionsTest.java`:

```java
package fan.summer.ai.tools;

import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolDescriptionsTest {

    private static AiTool tool(String cloudDesc, String localDesc,
                                List<AiToolParam> cloudParams,
                                List<AiToolParam> localParams) {
        return new AiTool() {
            public String getName() { return "t"; }
            public String getDescription() { return cloudDesc; }
            public String getLocalDescription() { return localDesc; }
            public List<AiToolParam> getParameters() { return cloudParams; }
            public List<AiToolParam> getLocalParameters() { return localParams; }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @AfterEach
    void reset() {
        AiServiceProvider.setCurrentMode("local");
    }

    @Test
    void picksLocalDescriptionInLocalMode() {
        AiServiceProvider.setCurrentMode("local");
        AiTool t = tool("cloud", "local", List.of(), List.of());
        assertEquals("local", AiToolDescriptions.pickDescription(t));
    }

    @Test
    void picksCloudDescriptionInCloudMode() {
        AiServiceProvider.setCurrentMode("openai");
        AiTool t = tool("cloud", "local", List.of(), List.of());
        assertEquals("cloud", AiToolDescriptions.pickDescription(t));
    }

    @Test
    void picksLocalParametersInLocalMode() {
        AiServiceProvider.setCurrentMode("local");
        AiToolParam cloud = AiToolParam.of("a", "string", "cloud-only");
        AiToolParam local = AiToolParam.of("b", "string", "local-only");
        AiTool t = tool("c", "l", List.of(cloud), List.of(local));
        assertEquals(List.of(local), AiToolDescriptions.pickParameters(t));
    }

    @Test
    void picksCloudParametersInCloudMode() {
        AiServiceProvider.setCurrentMode("anthropic");
        AiToolParam cloud = AiToolParam.of("a", "string", "cloud-only");
        AiToolParam local = AiToolParam.of("b", "string", "local-only");
        AiTool t = tool("c", "l", List.of(cloud), List.of(local));
        assertEquals(List.of(cloud), AiToolDescriptions.pickParameters(t));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Build test file. Expected: compile error — `AiToolDescriptions` does not exist.

- [ ] **Step 3: Create AiToolDescriptions**

Create `SwissKit/src/main/java/fan/summer/ai/tools/AiToolDescriptions.java`:

```java
package fan.summer.ai.tools;

import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;

import java.util.List;

/**
 * Picks the description and parameter list appropriate to the current backend mode.
 *
 * <p>Used by {@link ToolSchemaBuilder} (local path) and
 * {@code AiToolToToolSpecification} (cloud path) so each consumer renders the
 * version the active backend should see.</p>
 */
public final class AiToolDescriptions {

    private AiToolDescriptions() {}

    /** @return {@code true} when the active backend is the local GGUF engine. */
    public static boolean isLocalMode() {
        return "local".equals(AiServiceProvider.getCurrentMode());
    }

    /**
     * @return {@link AiTool#getLocalDescription()} in local mode,
     *         {@link AiTool#getDescription()} otherwise
     */
    public static String pickDescription(AiTool tool) {
        return isLocalMode() ? tool.getLocalDescription() : tool.getDescription();
    }

    /**
     * @return {@link AiTool#getLocalParameters()} in local mode,
     *         {@link AiTool#getParameters()} otherwise
     */
    public static List<AiToolParam> pickParameters(AiTool tool) {
        return isLocalMode() ? tool.getLocalParameters() : tool.getParameters();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Build test file. Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/AiToolDescriptions.java \
        SwissKit/src/test/java/fan/summer/ai/tools/AiToolDescriptionsTest.java
git commit -m "✨ feat(ai): AiToolDescriptions picks description/params by current mode"
```

---

## Task 4: ToolSchemaBuilder uses AiToolDescriptions

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java:85-115`
- Test: `SwissKit/src/test/java/fan/summer/ai/tools/ToolSchemaBuilderLocalTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/tools/ToolSchemaBuilderLocalTest.java`:

```java
package fan.summer.ai.tools;

import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaBuilderLocalTest {

    @AfterEach
    void reset() {
        AiServiceProvider.setCurrentMode("local");
    }

    private static AiTool dualDescTool() {
        return new AiTool() {
            public String getName() { return "t"; }
            public String getDescription() { return "CLOUD-ONLY-MARKER"; }
            public String getLocalDescription() { return "LOCAL-ONLY-MARKER"; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public List<AiToolParam> getLocalParameters() { return List.of(); }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @Test
    void promptUsesLocalDescriptionInLocalMode() {
        AiServiceProvider.setCurrentMode("local");
        String md = ToolSchemaBuilder.buildPromptDefinitions(List.of(dualDescTool()));
        assertTrue(md.contains("LOCAL-ONLY-MARKER"));
        assertFalse(md.contains("CLOUD-ONLY-MARKER"));
    }

    @Test
    void promptUsesCloudDescriptionInCloudMode() {
        AiServiceProvider.setCurrentMode("openai");
        String md = ToolSchemaBuilder.buildPromptDefinitions(List.of(dualDescTool()));
        assertTrue(md.contains("CLOUD-ONLY-MARKER"));
        assertFalse(md.contains("LOCAL-ONLY-MARKER"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Build test file. Expected: fails — current builder always uses `getDescription()`.

- [ ] **Step 3: Update ToolSchemaBuilder.buildPromptDefinitions**

In `SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java:85-115`, replace the `for (AiTool tool : tools)` loop body to use `AiToolDescriptions`:

```java
        for (AiTool tool : tools) {
            sb.append("### ").append(tool.getName()).append("\n");
            sb.append(AiToolDescriptions.pickDescription(tool)).append("\n");
            List<AiToolParam> params = AiToolDescriptions.pickParameters(tool);
            if (!params.isEmpty()) {
                sb.append("Parameters:\n");
                for (AiToolParam p : params) {
                    sb.append("- ").append(p.name()).append(" (").append(p.type()).append(")");
                    if (p.required()) sb.append(" [required]");
                    sb.append(": ").append(p.description()).append("\n");
                }
            }
            sb.append("\n");
        }
```

Also update `buildOpenAiTools` and `buildAnthropicTools` (lines 41-73) to use `AiToolDescriptions.pickDescription` and `pickParameters` instead of `tool.getDescription()` / `tool.getParameters()`:

```java
    public static List<Map<String, Object>> buildOpenAiTools(List<AiTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.getName());
            fn.put("description", AiToolDescriptions.pickDescription(tool));
            fn.put("parameters", buildJsonSchema(AiToolDescriptions.pickParameters(tool)));
            result.add(Map.of("type", "function", "function", fn));
        }
        return result;
    }

    public static List<Map<String, Object>> buildAnthropicTools(List<AiTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", tool.getName());
            t.put("description", AiToolDescriptions.pickDescription(tool));
            t.put("input_schema", buildJsonSchema(AiToolDescriptions.pickParameters(tool)));
            result.add(t);
        }
        return result;
    }
```

Add the import at the top:

```java
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
// (already present)
```

(`AiToolDescriptions` is in the same package, no import needed.)

- [ ] **Step 4: Run test to verify it passes**

Build test file. Expected: both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java \
        SwissKit/src/test/java/fan/summer/ai/tools/ToolSchemaBuilderLocalTest.java
git commit -m "♻️ refactor(ai): ToolSchemaBuilder uses AiToolDescriptions for mode-aware rendering"
```

---

## Task 5: AiToolToToolSpecification uses AiToolDescriptions

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/adapter/AiToolToToolSpecification.java`
- Test: `SwissKit/src/test/java/fan/summer/ai/adapter/AiToolToToolSpecificationLocalTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/adapter/AiToolToToolSpecificationLocalTest.java`:

```java
package fan.summer.ai.adapter;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.ai.tools.AiToolDescriptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolToToolSpecificationLocalTest {

    @AfterEach
    void reset() {
        AiServiceProvider.setCurrentMode("local");
    }

    private static AiTool dualTool() {
        return new AiTool() {
            public String getName() { return "t"; }
            public String getDescription() { return "CLOUD-MARKER"; }
            public String getLocalDescription() { return "LOCAL-MARKER"; }
            public List<AiToolParam> getParameters() {
                return List.of(AiToolParam.of("cloudOnly", "string", "c"));
            }
            public List<AiToolParam> getLocalParameters() {
                return List.of(AiToolParam.of("localOnly", "string", "l"));
            }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @Test
    void localModeProducesLocalDescriptionAndParams() {
        AiServiceProvider.setCurrentMode("local");
        ToolSpecification spec = AiToolToToolSpecification.convert(dualTool());
        assertEquals("LOCAL-MARKER", spec.description());
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties().get("localOnly"));
        assertNull(schema.properties().get("cloudOnly"));
    }

    @Test
    void cloudModeProducesCloudDescriptionAndParams() {
        AiServiceProvider.setCurrentMode("openai");
        ToolSpecification spec = AiToolToToolSpecification.convert(dualTool());
        assertEquals("CLOUD-MARKER", spec.description());
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties().get("cloudOnly"));
        assertNull(schema.properties().get("localOnly"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Build test file. Expected: fails — current converter ignores mode.

- [ ] **Step 3: Update AiToolToToolSpecification.convert**

Edit `SwissKit/src/main/java/fan/summer/ai/adapter/AiToolToToolSpecification.java`, replace `convert`:

```java
    public static ToolSpecification convert(AiTool tool) {
        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (AiToolParam param : AiToolDescriptions.pickParameters(tool)) {
            properties.put(param.name(), buildSchema(param));
            if (param.required()) {
                required.add(param.name());
            }
        }

        JsonObjectSchema params = JsonObjectSchema.builder()
            .addProperties(properties)
            .required(required)
            .build();

        return ToolSpecification.builder()
            .name(tool.getName())
            .description(AiToolDescriptions.pickDescription(tool))
            .parameters(params)
            .build();
    }
```

Add the import:

```java
import fan.summer.ai.tools.AiToolDescriptions;
```

- [ ] **Step 4: Run test to verify it passes**

Build test file. Expected: both tests PASS. Also run the existing `AiToolToToolSpecificationTest` (it uses default mode = "local", descriptions are same for cloud/local in those tests so they still pass).

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/adapter/AiToolToToolSpecification.java \
        SwissKit/src/test/java/fan/summer/ai/adapter/AiToolToToolSpecificationLocalTest.java
git commit -m "♻️ refactor(ai): AiToolToToolSpecification uses AiToolDescriptions"
```

---

## Task 6: ToolExecutor catch returns JSON

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/ToolExecutor.java:48-61`
- Test: `SwissKit/src/test/java/fan/summer/ai/tools/ToolExecutorErrorJsonTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/tools/ToolExecutorErrorJsonTest.java`:

```java
package fan.summer.ai.tools;

import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.ai.util.JsonHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorErrorJsonTest {

    @BeforeEach
    void clear() {
        AiServiceProvider.clearTools();
    }

    @AfterEach
    void cleanup() {
        AiServiceProvider.clearTools();
    }

    @Test
    void executeUnknownToolReturnsJsonError() {
        AiToolResult r = ToolExecutor.execute("nonexistent_tool", Map.of());
        assertFalse(r.success());
        Map<String, Object> parsed = JsonHelper.parseObject(r.output());
        assertEquals(false, parsed.get("success"));
        assertNotNull(parsed.get("error"));
    }

    @Test
    void executeToolThatThrowsReturnsJsonError() {
        AiTool throwing = new AiTool() {
            public String getName() { return "thrower"; }
            public String getDescription() { return ""; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public AiToolResult execute(Map<String, Object> args) {
                throw new RuntimeException("boom");
            }
        };
        AiServiceProvider.registerTool(throwing);

        AiToolResult r = ToolExecutor.execute("thrower", Map.of());
        assertFalse(r.success());
        Map<String, Object> parsed = JsonHelper.parseObject(r.output());
        assertEquals(false, parsed.get("success"));
        String err = (String) parsed.get("error");
        assertNotNull(err);
        assertTrue(err.contains("boom"), "error should contain the original message");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Build test file. Expected: fails — current error output is `"Tool not found: ..."` (plain text, not JSON).

- [ ] **Step 3: Update ToolExecutor to emit JSON errors**

Replace `SwissKit/src/main/java/fan/summer/ai/tools/ToolExecutor.java:48-61`:

```java
    public static AiToolResult execute(String toolName, Map<String, Object> arguments) {
        AiTool tool = AiServiceProvider.getTool(toolName);
        if (tool == null) {
            log.warn("Tool not found: {}", toolName);
            return AiToolResult.error(jsonError("Tool not found: " + toolName));
        }
        try {
            log.debug("Executing tool: name={}, arguments={}", toolName, arguments);
            return tool.execute(arguments);
        } catch (Exception e) {
            log.error("Tool execution error: tool={}, error={}", toolName, e.getMessage());
            return AiToolResult.error(jsonError("Tool execution error: " + e.getMessage()));
        }
    }

    private static String jsonError(String message) {
        return JsonHelper.toJson(Map.of("success", false, "error", message));
    }
```

Add the import (JsonHelper is in the same package `fan.summer.ai.util`, so add):

```java
import fan.summer.ai.util.JsonHelper;
```

(`fan.summer.zhiflow.api.ai.*` is already imported via `import fan.summer.zhiflow.api.ai.*;` if present, otherwise add individually.)

- [ ] **Step 4: Run test to verify it passes**

Build test file. Expected: both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/ToolExecutor.java \
        SwissKit/src/test/java/fan/summer/ai/tools/ToolExecutorErrorJsonTest.java
git commit -m "🐛 fix(ai): ToolExecutor error output is always JSON {success:false,error:...}"
```

---

## Task 7: SwissKitJPlugin.aiTools() default method

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`
- Test: `SwissKit/src/test/java/fan/summer/api/SwissKitJPluginAiToolsDefaultTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/api/SwissKitJPluginAiToolsDefaultTest.java`:

```java
package fan.summer.zhiflow.api;

import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwissKitJPluginAiToolsDefaultTest {

    /** A plugin that overrides nothing — should still work and return empty aiTools. */
    private static final SwissKitJPlugin MINIMAL = new SwissKitJPlugin() {
        public String getId() { return "test.minimal"; }
        public String getName() { return "Minimal"; }
        public String getDescription() { return ""; }
        public ToolCategory getCategory() { return ToolCategory.OTHER; }
        public String getVersion() { return "0.0.1"; }
        public String getMdiIcon() { return "circle"; }
        public Node createView() { return null; }
    };

    @Test
    void defaultAiToolsIsEmpty() {
        assertNotNull(MINIMAL.aiTools());
        assertTrue(MINIMAL.aiTools().isEmpty());
    }

    @Test
    void defaultAiToolsIsImmutable() {
        // Default returns List.of() which is immutable; ensure no exception leak
        List<?> tools = MINIMAL.aiTools();
        assertDoesNotThrow(() -> {
            //noinspection WriteToFront
            try { tools.add(new Object()); } catch (UnsupportedOperationException ignored) {}
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Build test file. Expected: compile error — `aiTools()` method missing on `SwissKitJPlugin`.

- [ ] **Step 3: Add aiTools() default**

In `SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`, add import at top:

```java
import fan.summer.zhiflow.api.ai.AiTool;
import java.util.List;
```

Append before the closing brace of `interface SwissKitJPlugin`:

```java

    // ── AI tools ─────────────────────────────────────────

    /**
     * AI tools this plugin exposes to the AI layer.
     *
     * <p>Default is empty — plugins that don't integrate with AI don't need to do anything.
     * Tools are registered when the plugin is added to {@code PluginRegistry} and
     * unregistered when the plugin is removed.</p>
     *
     * <p>Threading: called on the JavaFX Application Thread (same thread as
     * {@code addPlugins}). Implementations must return a deterministic, idempotent
     * list — subsequent calls must return tools with the same names so unregister
     * can match them.</p>
     *
     * @return the AI tools this plugin exposes; empty list if none
     */
    default List<AiTool> aiTools() { return List.of(); }
```

- [ ] **Step 4: Build API module + run test**

Run `mvn install -f SwissKitJ-Api/pom.xml -DskipTests`. Then build test file. Expected: both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java \
        SwissKit/src/test/java/fan/summer/api/SwissKitJPluginAiToolsDefaultTest.java
git commit -m "✨ feat(api): SwissKitJPlugin.aiTools() default for plugin-owned AI tools"
```

---

## Task 8: PluginRegistry manages AI tool lifecycle

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/plugin/PluginRegistry.java`
- Test: `SwissKit/src/test/java/fan/summer/plugin/PluginRegistryAiToolsTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/plugin/PluginRegistryAiToolsTest.java`:

```java
package fan.summer.plugin;

import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import javafx.scene.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryAiToolsTest {

    private PluginRegistry registry;
    private PluginLoader loader;

    private static AiTool tool(String name) {
        return new AiTool() {
            public String getName() { return name; }
            public String getDescription() { return name; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    private static SwissKitJPlugin plugin(String id, List<AiTool> tools) {
        return new SwissKitJPlugin() {
            public String getId() { return id; }
            public String getName() { return id; }
            public String getDescription() { return ""; }
            public ToolCategory getCategory() { return ToolCategory.OTHER; }
            public String getVersion() { return "0.0.1"; }
            public String getMdiIcon() { return "circle"; }
            public Node createView() { return null; }
            public ToolType getType() { return ToolType.PLUGIN; }
            public List<AiTool> aiTools() { return tools; }
        };
    }

    @BeforeEach
    void setup() {
        AiServiceProvider.clearTools();
        loader = new PluginLoader(null);
        registry = new PluginRegistry(loader);
        PluginRegistry.setInstanceForTest(registry);
    }

    @AfterEach
    void teardown() {
        AiServiceProvider.clearTools();
        PluginRegistry.setInstanceForTest(null);
    }

    @Test
    void addPluginsRegistersAiTools() {
        SwissKitJPlugin p = plugin("p1", List.of(tool("t1"), tool("t2")));
        registry.addPlugins(List.of(p));

        assertNotNull(AiServiceProvider.getTool("t1"));
        assertNotNull(AiServiceProvider.getTool("t2"));
    }

    @Test
    void removePluginUnregistersAiTools() {
        SwissKitJPlugin p = plugin("p1", List.of(tool("t1")));
        registry.addPlugins(List.of(p));
        assertNotNull(AiServiceProvider.getTool("t1"));

        registry.removePlugin(p);
        assertNull(AiServiceProvider.getTool("t1"));
    }

    @Test
    void pluginWithEmptyAiToolsIsSafe() {
        SwissKitJPlugin p = plugin("p1", List.of());
        registry.addPlugins(List.of(p));
        assertTrue(AiServiceProvider.getTools().isEmpty());
    }

    @Test
    void pluginThrowingInAiToolsDoesNotCrash() {
        SwissKitJPlugin bad = new SwissKitJPlugin() {
            public String getId() { return "bad"; }
            public String getName() { return "bad"; }
            public String getDescription() { return ""; }
            public ToolCategory getCategory() { return ToolCategory.OTHER; }
            public String getVersion() { return "0.0.1"; }
            public String getMdiIcon() { return "circle"; }
            public Node createView() { return null; }
            public List<AiTool> aiTools() { throw new RuntimeException("oops"); }
        };
        assertDoesNotThrow(() -> registry.addPlugins(List.of(bad)));
        assertTrue(AiServiceProvider.getTools().isEmpty());
    }
}
```

**Note on test seam:** the test calls `PluginRegistry.setInstanceForTest(...)` and constructs `new PluginLoader(null)`. Both need to exist; we'll add the test seam + null-tolerant constructor in this task. Also `PluginRegistry(PluginLoader)` sets `INSTANCE = this`, so `setInstanceForTest` is only needed to clear it after.

- [ ] **Step 2: Run test to verify it fails**

Build test file. Expected: compile error — `addPlugins` is package-private (test in same package `fan.summer.plugin` so should actually work), `setInstanceForTest` does not exist, `new PluginLoader(null)` may not accept null.

Check `PluginLoader` constructor signature first — if it requires a Path, allow null in test.

- [ ] **Step 3: Add the test seam + lifecycle methods to PluginRegistry**

In `SwissKit/src/main/java/fan/summer/plugin/PluginRegistry.java`:

Add imports:

```java
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiTool;
import java.util.HashMap;
```

Add field after `private final Set<SwissKitJPlugin> backgroundPlugins = ...`:

```java
    private final Map<SwissKitJPlugin, List<String>> toolsByPlugin = new HashMap<>();
```

Replace `addPlugins` (lines 97-100):

```java
    /**
     * Adds a collection of plugins to the registry and registers their AI tools
     * with {@link AiServiceProvider}.
     *
     * <p>Called by {@link PluginLoader} on the JavaFX Application Thread when a
     * new JAR is loaded, and by {@code BuiltinToolRegistrar} at startup.</p>
     *
     * @param toAdd the plugins to add; may be empty but not {@code null}
     * @since 1.0
     */
    public void addPlugins(List<SwissKitJPlugin> toAdd) {
        log.debug("Adding {} plugin(s) to registry", toAdd.size());
        plugins.addAll(toAdd);
        for (SwissKitJPlugin p : toAdd) registerPluginTools(p);
    }
```

In `removePlugin` (around line 115-128), add `unregisterPluginTools(plugin);` as the first statement inside the method body:

```java
    void removePlugin(SwissKitJPlugin plugin) {
        log.debug("Removing plugin from registry: id={}", plugin.getId());
        unregisterPluginTools(plugin);
        backgroundPlugins.remove(plugin);
        if (activePlugin == plugin) {
            try {
                PluginContext.runWith(plugin, plugin::onDeactivate);
            } catch (Exception e) {
                log.warn("Plugin {} threw on onDeactivate(): {}", plugin.getId(), e.getMessage(), e);
            }
            activePlugin = null;
        }
        plugins.remove(plugin);
    }
```

Append private helpers before the final closing brace:

```java

    // ── AI tool lifecycle ───────────────────────────────────

    private void registerPluginTools(SwissKitJPlugin plugin) {
        List<AiTool> tools;
        try {
            tools = PluginContext.runWith(plugin, plugin::aiTools);
        } catch (Exception e) {
            log.warn("Plugin {} threw on aiTools(): {}", plugin.getId(), e.getMessage(), e);
            return;
        }
        if (tools == null || tools.isEmpty()) return;

        List<String> names = new java.util.ArrayList<>();
        for (AiTool t : tools) {
            AiTool existing = AiServiceProvider.getTool(t.getName());
            if (existing != null) {
                log.warn("Tool name '{}' from plugin {} overwrites an existing registration",
                        t.getName(), plugin.getId());
            }
            AiServiceProvider.registerTool(t);
            names.add(t.getName());
        }
        toolsByPlugin.put(plugin, names);
        log.info("Registered {} AI tool(s) from plugin {}", names.size(), plugin.getId());
    }

    private void unregisterPluginTools(SwissKitJPlugin plugin) {
        List<String> names = toolsByPlugin.remove(plugin);
        if (names == null) return;
        for (String name : names) AiServiceProvider.unregisterTool(name);
        log.info("Unregistered {} AI tool(s) from plugin {}", names.size(), plugin.getId());
    }

    /** Test seam — allows tests to inject/clear the singleton without using reflection. */
    static void setInstanceForTest(PluginRegistry instance) {
        INSTANCE = instance;
    }
```

Also check `PluginLoader`'s constructor — if it requires non-null `Path`, modify to accept null. Read `SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java` and adjust if needed (most likely the constructor accepts a `Path pluginsDir` that can be null in tests).

- [ ] **Step 4: Build module + run test**

Build `SwissKit` module via IDEA Maven. Then build test file. Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/plugin/PluginRegistry.java \
        SwissKit/src/test/java/fan/summer/plugin/PluginRegistryAiToolsTest.java \
        SwissKit/src/main/java/fan/summer/plugin/PluginLoader.java
git commit -m "$(cat <<'EOF'
✨ feat(plugin): PluginRegistry auto-registers plugin AI tools on add/remove

addPlugins becomes public and now invokes each plugin's aiTools() to
register tools with AiServiceProvider; removePlugin symmetrically
unregisters. A name conflict logs a WARN. Failures in aiTools() are
contained — a misbehaving plugin cannot break the registry.
EOF
)"
```

---

# Commit 2: Tool Migration Into Plugins

**Goal:** Each plugin owns its AI tools. BuiltinAiToolRegistrar is deleted. New BrowserAutomatePlugin is added as UI-less host for BrowserAutomateTool.

---

## Task 9: Create BrowserAutomatePlugin (UI-less host)

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserAutomatePlugin.java`

- [ ] **Step 1: Create the plugin class**

Create `SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserAutomatePlugin.java`:

```java
package fan.summer.buildintool.browser;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.buildintool.browser.ai.BrowserAutomateTool;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * UI-less host plugin for {@link BrowserAutomateTool}.
 *
 * <p>Browser automation has no standalone UI — it's invoked through the AI chat.
 * This plugin exists so the tool has a natural owner in the
 * plugin registry, and so the sidebar shows users that the capability exists.
 * The {@link #createView()} returns a short explanation page.</p>
 */
public class BrowserAutomatePlugin implements SwissKitJPlugin {

    @Override
    public String getId() { return "fan.summer.buildin.browser-automate"; }

    @Override
    public String getName() { return "Browser Automate"; }

    @Override
    public String getDescription() {
        return "Provides browser automation capability to the AI chat. "
             + "Invoke by asking the AI to perform web tasks.";
    }

    @Override
    public ToolCategory getCategory() { return ToolCategory.DEV; }

    @Override
    public String getVersion() { return "1.0.0"; }

    @Override
    public String getMdiIcon() { return "web"; }

    @Override
    public IconStyle getIconStyle() { return IconStyle.TEAL; }

    @Override
    public ToolType getType() { return ToolType.BUILTIN; }

    @Override
    public Node createView() {
        VBox box = new VBox(8);
        box.setStyle("-fx-padding: 24;");
        Label title = new Label("Browser Automation");
        title.setStyle("-fx-font-size: 16; -fx-font-weight: bold;");
        Label body = new Label(
            "This plugin provides browser automation to the AI chat. "
          + "Open the AI chat and describe what you want, e.g. "
          + "\"Open github.com and search for 'playwright java'\"."
        );
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().addAll(title, body);
        return box;
    }

    @Override
    public List<AiTool> aiTools() {
        return List.of(new BrowserAutomateTool());
    }
}
```

- [ ] **Step 2: Verify it compiles**

Build module via IDEA Maven. Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserAutomatePlugin.java
git commit -m "✨ feat(browser): BrowserAutomatePlugin UI-less host for BrowserAutomateTool"
```

---

## Task 10: Add aiTools() overrides to the 4 simple plugins

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/dev/Base64Plugin.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/dev/HashCalculatorPlugin.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/dev/JsonFormatterPlugin.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/image/ColorConverterPlugin.java`

For each of these 4 plugins, add an `aiTools()` override. The pattern is identical; only the tool class differs.

- [ ] **Step 1: Add aiTools() to Base64Plugin**

Open `SwissKit/src/main/java/fan/summer/buildintool/dev/Base64Plugin.java`. Add imports:

```java
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.ai.tools.BuiltinBase64Tool;
import java.util.List;
```

Append inside the class:

```java
    @Override
    public List<AiTool> aiTools() {
        return List.of(new BuiltinBase64Tool());
    }
```

- [ ] **Step 2: Add aiTools() to HashCalculatorPlugin**

Open `SwissKit/src/main/java/fan/summer/buildintool/dev/HashCalculatorPlugin.java`. Add imports:

```java
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.ai.tools.BuiltinHashTool;
import java.util.List;
```

Append inside the class:

```java
    @Override
    public List<AiTool> aiTools() {
        return List.of(new BuiltinHashTool());
    }
```

- [ ] **Step 3: Add aiTools() to JsonFormatterPlugin**

Open `SwissKit/src/main/java/fan/summer/buildintool/dev/JsonFormatterPlugin.java`. Add imports:

```java
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.ai.tools.BuiltinJsonFormatTool;
import java.util.List;
```

Append inside the class:

```java
    @Override
    public List<AiTool> aiTools() {
        return List.of(new BuiltinJsonFormatTool());
    }
```

- [ ] **Step 4: Add aiTools() to ColorConverterPlugin**

Open `SwissKit/src/main/java/fan/summer/buildintool/image/ColorConverterPlugin.java`. Add imports:

```java
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.ai.tools.BuiltinColorConvertTool;
import java.util.List;
```

Append inside the class:

```java
    @Override
    public List<AiTool> aiTools() {
        return List.of(new BuiltinColorConvertTool());
    }
```

- [ ] **Step 5: Build module to verify all 4 compile**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/dev/Base64Plugin.java \
        SwissKit/src/main/java/fan/summer/buildintool/dev/HashCalculatorPlugin.java \
        SwissKit/src/main/java/fan/summer/buildintool/dev/JsonFormatterPlugin.java \
        SwissKit/src/main/java/fan/summer/buildintool/image/ColorConverterPlugin.java
git commit -m "♻️ refactor(dev/image): simple plugins self-declare their AI tools"
```

---

## Task 11: ExcelSplitterPlugin.aiTools()

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/excelsplitter/ExcelSplitterPlugin.java`

- [ ] **Step 1: Add aiTools() to ExcelSplitterPlugin**

Open `SwissKit/src/main/java/fan/summer/buildintool/excelsplitter/ExcelSplitterPlugin.java`. Add imports:

```java
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.buildintool.ai.ExcelAnalyzeTool;
import fan.summer.buildintool.ai.ExcelCancelTool;
import fan.summer.buildintool.ai.ExcelComplexConfigTool;
import fan.summer.buildintool.ai.ExcelConfigureTool;
import fan.summer.buildintool.ai.ExcelExecuteTool;
import fan.summer.buildintool.ai.ExcelQueryTool;
import java.util.List;
```

Append inside the class:

```java
    @Override
    public List<AiTool> aiTools() {
        return List.of(
            new ExcelAnalyzeTool(this),
            new ExcelConfigureTool(this),
            new ExcelComplexConfigTool(this),
            new ExcelExecuteTool(this),
            new ExcelQueryTool(this),
            new ExcelCancelTool()
        );
    }
```

- [ ] **Step 2: Build module**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/excelsplitter/ExcelSplitterPlugin.java
git commit -m "♻️ refactor(excel): ExcelSplitterPlugin self-declares its 6 AI tools"
```

---

## Task 12: EmailArchivePlugin.aiTools()

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchivePlugin.java`

- [ ] **Step 1: Add aiTools() to EmailArchivePlugin**

Open `SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchivePlugin.java`. Add imports:

```java
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.buildintool.ai.EmailArchiveFetchTool;
import fan.summer.buildintool.ai.EmailArchiveQueryTool;
import java.util.List;
```

Append inside the class:

```java
    @Override
    public List<AiTool> aiTools() {
        return List.of(
            new EmailArchiveFetchTool(this),
            new EmailArchiveQueryTool()
        );
    }
```

- [ ] **Step 2: Build module**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/emailarchive/EmailArchivePlugin.java
git commit -m "♻️ refactor(email-archive): EmailArchivePlugin self-declares its 2 AI tools"
```

---

## Task 13: PdfToolPlugin.aiTools()

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/pdftool/PdfToolPlugin.java`

- [ ] **Step 1: Add aiTools() to PdfToolPlugin**

Open `SwissKit/src/main/java/fan/summer/buildintool/pdftool/PdfToolPlugin.java`. Add imports:

```java
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.buildintool.pdftool.ai.PdfMergeAiTool;
import fan.summer.buildintool.pdftool.ai.PdfSplitAiTool;
import fan.summer.buildintool.pdftool.ai.PdfToDocxAiTool;
import java.util.List;
```

Append inside the class:

```java
    @Override
    public List<AiTool> aiTools() {
        return List.of(
            new PdfSplitAiTool(),
            new PdfMergeAiTool(),
            new PdfToDocxAiTool()
        );
    }
```

- [ ] **Step 2: Build module**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/pdftool/PdfToolPlugin.java
git commit -m "♻️ refactor(pdf): PdfToolPlugin self-declares its 3 AI tools"
```

---

## Task 14: Update BuiltinToolRegistrar

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java`

- [ ] **Step 1: Switch to addPlugins and add BrowserAutomatePlugin**

Open `SwissKit/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java`. Add import:

```java
import fan.summer.buildintool.browser.BrowserAutomatePlugin;
```

Replace the `builtins` list (lines 64-75):

```java
        List<SwissKitJPlugin> builtins = List.of(
            new AiChatPlugin(),
            new JsonFormatterPlugin(),
            new Base64Plugin(),
            new HashCalculatorPlugin(),
            new ExcelSplitterPlugin(),
            new ColorConverterPlugin(),
            new MarkdownEditorPlugin(),
            new EmailPlugin(),
            new EmailArchivePlugin(),
            new PdfToolPlugin(),
            new BrowserAutomatePlugin()
        );
```

Replace the registration call (line 76):

```java
        registry.addPlugins(builtins);
```

- [ ] **Step 2: Build module**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java
git commit -m "♻️ refactor(registrar): BuiltinToolRegistrar uses registry.addPlugins + adds BrowserAutomatePlugin"
```

---

## Task 15: Delete BuiltinAiToolRegistrar + remove startup call

**Files:**
- Delete: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java`
- Modify: `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` (find the call site)

- [ ] **Step 1: Locate the call site**

Grep for `BuiltinAiToolRegistrar`:

```
mcp__idea__search_in_files_by_text("BuiltinAiToolRegistrar")
```

Expected: one match in `SwissKitJApp.java` (the call site) and the class definition itself.

- [ ] **Step 2: Remove the call from SwissKitJApp**

Open `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java`. Find the line:

```java
BuiltinAiToolRegistrar.register();
```

Delete it. Also remove the now-unused import:

```java
import fan.summer.ai.tools.BuiltinAiToolRegistrar;
```

- [ ] **Step 3: Delete the class file**

```bash
rm SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java
```

- [ ] **Step 4: Build module + verify startup**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Expected: BUILD SUCCESS.

Then run the existing app smoke (manual): `java -jar SwissKit/target/SwissKitJ-3.1.0.jar` (or via IDEA run config). Verify the app starts without exceptions and the AI chat works (try "base64 encode hello" in local mode).

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java
git rm SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java
git commit -m "$(cat <<'EOF'
🔥 chore(ai): remove BuiltinAiToolRegistrar — plugins now self-register tools

Tools are owned by their host plugins via SwissKitJPlugin.aiTools().
PluginRegistry.registerPluginTools() handles registration at plugin-add
time; BuiltinAiToolRegistrar is dead code.
EOF
)"
```

---

# Commit 3: Schema / Description / Return Contract Standardization

**Goal:** Apply the unified schema (enums, `string[]`), dual descriptions (cloud/local), and JSON return shape (`{success, summary, ...}`) to all 16 tools.

---

## Task 16: Fix enums in BuiltinBase64Tool, BuiltinHashTool, BuiltinColorConvertTool

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64Tool.java`
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinHashTool.java`
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinColorConvertTool.java`

- [ ] **Step 1: Add enum to BuiltinBase64Tool.mode**

In `BuiltinBase64Tool.java`, change the `getParameters()` method (line 36-41):

```java
    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("text", "string", "Input text to encode or decode", true),
            AiToolParam.of("mode", "string",
                "Direction of the conversion: encode or decode", true,
                List.of("encode", "decode"))
        );
    }
```

- [ ] **Step 2: Add enum to BuiltinHashTool.algorithm**

In `BuiltinHashTool.java`, change `getParameters()`:

```java
    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("text", "string", "Input text to hash", true),
            AiToolParam.of("algorithm", "string",
                "Hash algorithm", true,
                List.of("MD5", "SHA-1", "SHA-256", "SHA-512"))
        );
    }
```

- [ ] **Step 3: Add enums to BuiltinColorConvertTool.from and .to**

In `BuiltinColorConvertTool.java`, change `getParameters()`:

```java
    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("color", "string", "Color value to convert", true),
            AiToolParam.of("from", "string", "Source format", true,
                List.of("HEX", "RGB", "HSL")),
            AiToolParam.of("to", "string", "Target format", true,
                List.of("HEX", "RGB", "HSL"))
        );
    }
```

- [ ] **Step 4: Build module + verify existing tests still pass**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Then run the `AiToolToToolSpecificationTest` (existing) to make sure enum rendering still works.

Expected: BUILD SUCCESS; existing tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64Tool.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinHashTool.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinColorConvertTool.java
git commit -m "🐛 fix(ai): declare enums for base64.mode / hash.algorithm / color_convert.from,to"
```

---

## Task 17: Fix pdf_merge filePaths array type

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfMergeAiTool.java:31`

- [ ] **Step 1: Change type from "array" to "string[]"**

In `PdfMergeAiTool.java`, change `getParameters()` (line 30-35):

```java
    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("filePaths", "string[]", "Ordered list of PDF file paths to merge", true),
            AiToolParam.of("outputPath", "string", "Output file path for merged PDF", true)
        );
    }
```

- [ ] **Step 2: Build module**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfMergeAiTool.java
git commit -m "🐛 fix(pdf): pdf_merge.filePaths declared as string[] not array (schema now valid)"
```

---

## Task 18: Rewrite all 16 tool descriptions (cloud + local)

This task rewrites `getDescription()` and adds `getLocalDescription()` for each of the 16 tools. 14 tools need both (the 2 cloud-only tools, `email_archive_fetch` and `browser_automate`, only need cloud description — local fallback is fine).

The pattern for each tool: open the file, replace the existing `getDescription()` body, then add a `getLocalDescription()` override immediately after.

For each file, also keep `supportsLocal()` / `supportsCloud()` overrides as specified in §5.4 of the spec.

- [ ] **Step 1: BuiltinBase64Tool — description + local**

In `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64Tool.java`, replace `getDescription()`:

```java
    @Override public String getDescription() {
        return "Encode text to Base64 or decode Base64 back to text.\n"
             + "Args: text (string, required) — input text to transform;\n"
             + "      mode (string, required, enum: encode|decode) — direction of the conversion.\n"
             + "Example: base64{\"text\":\"hello\",\"mode\":\"encode\"}.";
    }

    @Override public String getLocalDescription() {
        return "Base64 encode or decode. Args: text (string), mode (encode|decode).\n"
             + "Example: base64{\"text\":\"hello\",\"mode\":\"encode\"}.";
    }
```

- [ ] **Step 2: BuiltinHashTool — description + local**

```java
    @Override public String getDescription() {
        return "Calculate cryptographic hash digest of input text.\n"
             + "Args: text (string, required) — input text;\n"
             + "      algorithm (string, required, enum: MD5|SHA-1|SHA-256|SHA-512).\n"
             + "Example: hash_calculate{\"text\":\"abc\",\"algorithm\":\"SHA-256\"}.";
    }

    @Override public String getLocalDescription() {
        return "Hash digest. Args: text (string), algorithm (MD5|SHA-1|SHA-256|SHA-512).\n"
             + "Example: hash_calculate{\"text\":\"abc\",\"algorithm\":\"MD5\"}.";
    }
```

- [ ] **Step 3: BuiltinJsonFormatTool — description + local**

```java
    @Override public String getDescription() {
        return "Format or minify a JSON string.\n"
             + "Args: json (string, required) — the JSON string;\n"
             + "      minify (boolean, optional, default false) — true for compact, false for pretty.\n"
             + "Example: json_format{\"json\":\"{\\\"a\\\":1}\",\"minify\":false}.";
    }

    @Override public String getLocalDescription() {
        return "Format or minify JSON. Args: json (string), minify (boolean, default false).\n"
             + "Example: json_format{\"json\":\"{\\\"a\\\":1}\"}.";
    }
```

- [ ] **Step 4: BuiltinColorConvertTool — description + local**

```java
    @Override public String getDescription() {
        return "Convert a color between HEX, RGB, and HSL formats.\n"
             + "Args: color (string, required) — the color value (e.g. \"#5b8cf7\" or \"91,140,247\");\n"
             + "      from (string, required, enum: HEX|RGB|HSL) — source format;\n"
             + "      to   (string, required, enum: HEX|RGB|HSL) — target format.\n"
             + "Example: color_convert{\"color\":\"#5b8cf7\",\"from\":\"HEX\",\"to\":\"RGB\"}.";
    }

    @Override public String getLocalDescription() {
        return "Convert color. Args: color (string), from (HEX|RGB|HSL), to (HEX|RGB|HSL).\n"
             + "Example: color_convert{\"color\":\"#fff\",\"from\":\"HEX\",\"to\":\"RGB\"}.";
    }
```

- [ ] **Step 5: ExcelAnalyzeTool — description + local**

```java
    @Override public String getDescription() {
        return "Analyze an Excel .xlsx/.xls file: returns sheet names, row counts, and column headers.\n"
             + "Args: filePath (string, required) — absolute path to the Excel file.\n"
             + "Example: excel_analyze{\"filePath\":\"/path/file.xlsx\"}.";
    }

    @Override public String getLocalDescription() {
        return "Read Excel structure (sheets, headers). Args: filePath (string).\n"
             + "Example: excel_analyze{\"filePath\":\"/tmp/a.xlsx\"}.";
    }
```

- [ ] **Step 6: ExcelConfigureTool — description + local**

```java
    @Override public String getDescription() {
        return "Configure how to split the Excel file. Must be called after excel_analyze.\n"
             + "Args: mode (string, required, enum: BY_SHEET|BY_COLUMN|COMPLEX);\n"
             + "      sheets (string[], optional, BY_SHEET) — sheet names to export;\n"
             + "      splitSheet (string, optional, BY_COLUMN) — sheet to split;\n"
             + "      splitColumn (string, optional, BY_COLUMN) — column header to split by;\n"
             + "      taskId (string, optional, COMPLEX) — task ID from excel_complex_config.\n"
             + "Example: excel_configure{\"mode\":\"BY_COLUMN\",\"splitSheet\":\"Sheet1\",\"splitColumn\":\"部门\"}.";
    }

    @Override public String getLocalDescription() {
        return "Set split mode. Args: mode (BY_SHEET|BY_COLUMN|COMPLEX), plus mode-specific.\n"
             + "Example: excel_configure{\"mode\":\"BY_SHEET\"}.";
    }
```

- [ ] **Step 7: ExcelComplexConfigTool — description + local**

```java
    @Override public String getDescription() {
        return "Manage database-backed complex split configs used by COMPLEX mode.\n"
             + "Args: action (string, required, enum: add|list|clear) — operation;\n"
             + "      taskId (string, optional) — task ID (auto-generated on 'add' if omitted);\n"
             + "      sheetName (string, add) — sheet name;\n"
             + "      headerIndex (integer, add) — 1-based header row; -1 = copy all;\n"
             + "      columnIndex (integer, add) — 1-based column to split by; -1 = copy to all.\n"
             + "Example: excel_complex_config{\"action\":\"add\",\"sheetName\":\"Sheet1\",\"headerIndex\":1,\"columnIndex\":2}.";
    }

    @Override public String getLocalDescription() {
        return "Manage complex split configs. Args: action (add|list|clear), plus action-specific.\n"
             + "Example: excel_complex_config{\"action\":\"list\",\"taskId\":\"t1\"}.";
    }
```

- [ ] **Step 8: ExcelExecuteTool — description + local**

```java
    @Override public String getDescription() {
        return "Execute the configured Excel split and write output files. Must be called after excel_analyze and excel_configure.\n"
             + "Args: outputDir (string, required) — absolute path to output directory;\n"
             + "      filePrefix (string, optional) — prefix for output filenames.\n"
             + "Example: excel_execute{\"outputDir\":\"/out\",\"filePrefix\":\"result_\"}.";
    }

    @Override public String getLocalDescription() {
        return "Run configured split. Args: outputDir (string), filePrefix (string, optional).\n"
             + "Example: excel_execute{\"outputDir\":\"/tmp/out\"}.";
    }
```

- [ ] **Step 9: ExcelQueryTool — description + local**

```java
    @Override public String getDescription() {
        return "Query the current Excel split configuration state: source file, mode, selected sheets/columns, output directory.\n"
             + "Call to inspect progress before or between operations. No arguments.\n"
             + "Example: excel_query{}.";
    }

    @Override public String getLocalDescription() {
        return "Query current Excel split state. No args.\n"
             + "Example: excel_query{}.";
    }
```

- [ ] **Step 10: ExcelCancelTool — description + local**

```java
    @Override public String getDescription() {
        return "Cancel the Excel split operation currently running. Safe to call even when nothing is running.\n"
             + "No arguments.\n"
             + "Example: excel_cancel{}.";
    }

    @Override public String getLocalDescription() {
        return "Cancel running split. No args.\n"
             + "Example: excel_cancel{}.";
    }
```

- [ ] **Step 11: EmailArchiveFetchTool — description only (cloud-only) + supportsLocal=false**

```java
    @Override public String getDescription() {
        return "Connect to IMAP server and archive emails to local .eml files.\n"
             + "Args: accountEmail (string, required) — the configured email account;\n"
             + "      days (integer, optional, default 30) — fetch emails from last N days;\n"
             + "      folder (string, optional, default INBOX) — IMAP folder;\n"
             + "      outputDir (string, optional) — local directory for .eml files.\n"
             + "Example: email_archive_fetch{\"accountEmail\":\"a@b.com\",\"days\":7}.";
    }

    @Override public boolean supportsLocal() { return false; }
```

(No `getLocalDescription` override — fallback to cloud description is fine since it's never shown in local mode.)

- [ ] **Step 12: EmailArchiveQueryTool — description + local parameters**

```java
    @Override public String getDescription() {
        return "Search archived emails in the local database. All parameters are optional.\n"
             + "Args: accountEmail (string) — filter by account;\n"
             + "      fromAddress (string) — filter by sender (partial match);\n"
             + "      subject (string) — filter by subject (partial match);\n"
             + "      startDate (string) — ISO date like 2026-01-01;\n"
             + "      endDate (string) — ISO date like 2026-05-28;\n"
             + "      limit (integer, default 20) — max results.\n"
             + "Example: email_archive_query{\"subject\":\"invoice\",\"limit\":10}.";
    }

    @Override public String getLocalDescription() {
        return "Search archived emails. Args: subject (string), fromAddress (string), "
             + "startDate (ISO), endDate (ISO), limit (integer).\n"
             + "Example: email_archive_query{\"subject\":\"invoice\"}.";
    }

    @Override public List<AiToolParam> getLocalParameters() {
        // Qwen3-friendly subset: drop accountEmail filtering (rarely useful for the 4B model)
        return List.of(
            AiToolParam.of("subject",     "string",  "Filter by subject (partial match)", false),
            AiToolParam.of("fromAddress", "string",  "Filter by sender (partial match)",  false),
            AiToolParam.of("startDate",   "string",  "ISO date (e.g. 2026-01-01)",        false),
            AiToolParam.of("endDate",     "string",  "ISO date (e.g. 2026-05-28)",        false),
            AiToolParam.of("limit",       "integer", "Max results (default 20)",          false)
        );
    }
```

- [ ] **Step 13: PdfSplitAiTool — description + local**

```java
    @Override public String getDescription() {
        return "Split a PDF file into multiple files by page ranges.\n"
             + "Args: filePath (string, required) — absolute path to PDF;\n"
             + "      ranges (string, required) — page ranges like '1-3,5,8-10';\n"
             + "      outputDir (string, required) — output directory.\n"
             + "Example: pdf_split{\"filePath\":\"/a.pdf\",\"ranges\":\"1-3,5\",\"outputDir\":\"/out\"}.";
    }

    @Override public String getLocalDescription() {
        return "Split PDF by page ranges. Args: filePath (string), ranges (e.g. '1-3,5,8-10'), outputDir (string).\n"
             + "Example: pdf_split{\"filePath\":\"/a.pdf\",\"ranges\":\"1-3\",\"outputDir\":\"/out\"}.";
    }
```

- [ ] **Step 14: PdfMergeAiTool — description + local**

```java
    @Override public String getDescription() {
        return "Merge multiple PDF files into one.\n"
             + "Args: filePaths (string[], required) — ordered list of PDF file paths;\n"
             + "      outputPath (string, required) — output file path for merged PDF.\n"
             + "Example: pdf_merge{\"filePaths\":[\"/a.pdf\",\"/b.pdf\"],\"outputPath\":\"/out.pdf\"}.";
    }

    @Override public String getLocalDescription() {
        return "Merge PDFs. Args: filePaths (string[]), outputPath (string).\n"
             + "Example: pdf_merge{\"filePaths\":[\"/a.pdf\",\"/b.pdf\"],\"outputPath\":\"/o.pdf\"}.";
    }
```

- [ ] **Step 15: PdfToDocxAiTool — description + local**

```java
    @Override public String getDescription() {
        return "Convert a PDF file to DOCX format.\n"
             + "Args: filePath (string, required) — absolute path to PDF;\n"
             + "      outputDir (string, required) — output directory for the DOCX file.\n"
             + "Example: pdf_to_docx{\"filePath\":\"/a.pdf\",\"outputDir\":\"/out\"}.";
    }

    @Override public String getLocalDescription() {
        return "PDF to DOCX. Args: filePath (string), outputDir (string).\n"
             + "Example: pdf_to_docx{\"filePath\":\"/a.pdf\",\"outputDir\":\"/out\"}.";
    }
```

- [ ] **Step 16: BrowserAutomateTool — description only (cloud-only) + supportsLocal=false**

```java
    @Override
    public String getDescription() {
        return "Automate a web browser using natural language.\n"
             + "Opens the system Chrome/Edge/Chromium and performs navigation, clicking, typing, "
             + "form filling, data extraction. No driver install needed.\n"
             + "Args: instruction (string, required) — natural language task description.\n"
             + "Example: browser_automate{\"instruction\":\"Open github.com and search for 'playwright java'\"}.";
    }

    @Override
    public boolean supportsLocal() { return false; }
```

- [ ] **Step 17: Build module**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Expected: BUILD SUCCESS.

- [ ] **Step 18: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64Tool.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinHashTool.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinJsonFormatTool.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinColorConvertTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelAnalyzeTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelConfigureTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelComplexConfigTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelExecuteTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelQueryTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelCancelTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/EmailArchiveFetchTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/EmailArchiveQueryTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfSplitAiTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfMergeAiTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfToDocxAiTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/browser/ai/BrowserAutomateTool.java
git commit -m "$(cat <<'EOF'
📝 feat(ai): rewrite tool descriptions with cloud/local dual templates

Each tool now has a rich getDescription() for cloud and a concise
getLocalDescription() for Qwen3-4B. cloud-only tools
(email_archive_fetch, browser_automate) override supportsLocal()=false.
email_archive_query also exposes a local parameter subset.
EOF
)"
```

---

## Task 19: Standardize return JSON to `{success, summary, ...}`

**Files:** (high-impact only — full list below)
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfSplitAiTool.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfMergeAiTool.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfToDocxAiTool.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelCancelTool.java`

For all other tools, add a `summary` field to the existing success JSON. The success-payload shape is unchanged beyond that.

- [ ] **Step 1: PdfSplitAiTool — return JSON**

In `PdfSplitAiTool.java`, replace the success path (around line 64-67):

```java
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("summary", "Split into " + outputs.size() + " file(s)");
            result.put("outputFiles", outputs.stream().map(p -> p.getFileName().toString()).toList());
            log.info("AI pdf_split success: {} -> {} files", filePath, outputs.size());
            return AiToolResult.success(JsonHelper.toJson(result));
```

Add the imports:

```java
import fan.summer.ai.util.JsonHelper;
import java.util.LinkedHashMap;
import java.util.Map;
```

Also wrap error paths to JSON. Replace each `return AiToolResult.error("...");` with:

```java
return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "<original message>")));
```

- [ ] **Step 2: PdfMergeAiTool — return JSON**

In `PdfMergeAiTool.java`, replace the success path (around line 72-73):

```java
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("summary", "Merged " + paths.size() + " PDFs into " + result.getFileName());  // careful: var name clash
            // ... wait, 'result' is the merged file Path; rename
```

**Naming clash:** the local variable `result` is used twice. Rename the worker's return to `mergedPath`:

```java
            Path mergedPath = CompletableFuture.supplyAsync(() -> {
                try { return worker.call(); }
                catch (Exception e) { throw new CompletionException(e); }
            }).join();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("summary", "Merged " + paths.size() + " PDFs into " + mergedPath.getFileName());
            out.put("outputPath", mergedPath.toString());
            log.info("AI pdf_merge success: {} files -> {}", paths.size(), mergedPath.getFileName());
            return AiToolResult.success(JsonHelper.toJson(out));
```

Also wrap error paths to JSON as in Step 1.

- [ ] **Step 3: PdfToDocxAiTool — return JSON**

In `PdfToDocxAiTool.java`, replace the success path (around line 56-57):

```java
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("summary", "Converted to " + outputs.get(0).getFileName());
            out.put("outputPath", outputs.get(0).toString());
            log.info("AI pdf_to_docx success: {} -> {}", filePath, outputs.get(0).getFileName());
            return AiToolResult.success(JsonHelper.toJson(out));
```

Also wrap error paths to JSON as in Step 1.

- [ ] **Step 4: ExcelCancelTool — add `success` field**

In `ExcelCancelTool.java`, replace the return (line 36):

```java
        ExcelSplitterPlugin.cancel();
        log.info("excel_cancel: split operation cancelled");
        return AiToolResult.success("{\"success\":true,\"summary\":\"Split operation has been cancelled\"}");
```

- [ ] **Step 5: Add `summary` field to remaining tools**

For each of these tools, add a `summary` entry to the success JSON. They already build a `Map<String, Object> result` / `out`; just insert one extra entry before the existing fields.

| Tool | Insert `summary` value |
|---|---|
| `BuiltinBase64Tool` | `mode + " ok (input length " + text.length() + ")"` |
| `BuiltinHashTool` | `algoUpper + " digest computed"` |
| `BuiltinJsonFormatTool` | `(minify ? "Minified" : "Pretty-printed") + " JSON"` |
| `BuiltinColorConvertTool` | `from + " → " + to + " conversion ok"` |
| `ExcelAnalyzeTool` | `"Analyzed " + analysisResult.size() + " sheet(s)"` |
| `ExcelConfigureTool` | (use the existing `summary` entry — already present) |
| `ExcelComplexConfigTool` | (use the existing `summary` entry — already present) |
| `ExcelExecuteTool` | (use the existing `summary` entry — already present) |
| `ExcelQueryTool` | `"Current state: mode=" + (config.mode != null ? config.mode.name() : "unset")` |
| `EmailArchiveFetchTool` | `"Archived " + result.newArchived + " new (" + result.skippedDuplicates + " duplicates skipped)"` |
| `EmailArchiveQueryTool` | `"Found " + emails.size() + " email(s)"` |

Also wrap error paths to JSON in each of these tools. For brevity, the pattern is the same in each:

```java
// Before
return AiToolResult.error("text is required");

// After
return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "text is required")));
```

For each tool, scan its `execute` for all `AiToolResult.error(...)` calls and wrap them. Use the helper pattern from ToolExecutor (inline `JsonHelper.toJson(Map.of("success", false, "error", msg))`).

**Note:** the 4 tools modified in Steps 1-4 (pdf_split, pdf_merge, pdf_to_docx, excel_cancel) also need error-path wrapping if not already done in those steps.

- [ ] **Step 6: Build module**

Run `mvn clean package -f SwissKit/pom.xml -DskipTests`. Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfSplitAiTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfMergeAiTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/pdftool/ai/PdfToDocxAiTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelCancelTool.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64Tool.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinHashTool.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinJsonFormatTool.java \
        SwissKit/src/main/java/fan/summer/ai/tools/BuiltinColorConvertTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelAnalyzeTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelQueryTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelComplexConfigTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/EmailArchiveFetchTool.java \
        SwissKit/src/main/java/fan/summer/buildintool/ai/EmailArchiveQueryTool.java
git commit -m "$(cat <<'EOF'
🐛 fix(ai): tool return JSON standardized to {success, summary, ...}

All 16 tools now return JSON with success/summary/error fields. PDF
tools (split/merge/to_docx) and excel_cancel switched from plain text
or non-standard shapes. Error paths also wrapped in JSON so the model
sees consistent shape on failure.
EOF
)"
```

---

## Task 20: Update CLAUDE.md, plugin-dev skill, CHANGELOG

**Files:**
- Modify: `CLAUDE.md`
- Modify: `.claude/skills/plugin-dev/SKILL.md` (or wherever it lives — use `Glob` to find it)
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update CLAUDE.md**

In `CLAUDE.md`, find the "## Plugin Development" section. After the existing "Plugin logging" subsection, add:

```markdown
## Plugin AI Tools (v3.1.0+)

Plugins can expose AI tools by overriding the default `aiTools()` method:

```java
public class MyPlugin implements SwissKitJPlugin {
    @Override
    public List<AiTool> aiTools() {
        return List.of(new MyAiTool(this));
    }
}
```

Tools are auto-registered with `AiServiceProvider` when the plugin is added to the registry, and auto-unregistered on plugin removal (including JAR hot-reload). No manual registration needed.

### Cloud / local capability declaration

Each `AiTool` can declare its visibility per backend mode:

```java
public class MyAiTool implements AiTool {
    // ... getName, getDescription, getParameters, execute ...

    @Override public boolean supportsLocal() { return false; }  // hide from local (Qwen3-4B)
    @Override public boolean supportsCloud() { return true; }   // visible in cloud (default)

    @Override public String getLocalDescription() {
        return "Short Qwen3-friendly description with enum: a|b|c.";
    }

    @Override public List<AiToolParam> getLocalParameters() {
        // Simplified schema for local mode
        return List.of(AiToolParam.of("x", "string", "X"));
    }
}
```

Filter rules:
- Local mode (Qwen3-4B): tools with `supportsLocal()==true` only.
- Cloud mode (OpenAI/Anthropic): tools with `supportsCloud()==true` only.
- Switching mode takes effect on the next chat call — no re-registration.

### Tool return JSON contract

All tools return JSON via `AiToolResult.success(jsonString)`:

```json
{ "success": true, "summary": "<one-line summary>", ...payload }
```

On error, use `AiToolResult.error(jsonString)`:

```json
{ "success": false, "error": "<message>" }
```

The `summary` field is what the model primarily reads; payload fields are read on demand. This keeps small models like Qwen3-4B from drowning in detail.
```

Also update the "## AI tools" reference near the top of CLAUDE.md if it mentions the old `BuiltinAiToolRegistrar` — replace with reference to `PluginRegistry`-managed registration via plugin `aiTools()`.

- [ ] **Step 2: Update plugin-dev skill**

Find the skill file:

```
Glob(".claude/skills/plugin-dev/**")
```

Open the main SKILL.md and add a section titled "### Exposing AI tools" with the same code pattern as in CLAUDE.md.

- [ ] **Step 3: Update CHANGELOG.md**

Add entry under the next version (v3.1.0 or v3.2.0 — check current pom.xml):

```markdown
## [Unreleased]

### Added
- Plugins can self-declare AI tools via `SwissKitJPlugin.aiTools()` — no more central registrar.
- `AiTool` interface declares per-mode visibility (`supportsLocal`/`supportsCloud`) and dual descriptions (`getDescription`/`getLocalDescription`).
- Plugin registry auto-registers/unregisters tools on plugin add/remove (including hot-reload).

### Changed
- All 16 builtin AI tools return standardized JSON `{success, summary, ...payload}`.
- Tool descriptions follow a cloud-rich / local-concise dual template.
- `pdf_merge.filePaths` parameter type fixed from `"array"` to `"string[]"`.
- Enums declared for `base64.mode`, `hash_calculate.algorithm`, `color_convert.from/to`.

### Removed
- `BuiltinAiToolRegistrar` class — superseded by plugin-owned `aiTools()`.

### Fixed
- `pdf_merge` schema no longer renders as `string` (was `array` without `[]` suffix).
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md CHANGELOG.md .claude/skills/plugin-dev/
git commit -m "📝 docs: document plugin-owned AI tools and cloud/local capability"
```

---

## Self-Review

After all 20 tasks, run a final manual smoke pass per spec §8.2:

- [ ] Local mode: 13 tools visible; base64/hash/excel_analyze work; browser_automate hidden.
- [ ] OpenAI mode: 16 tools visible; email_archive_fetch and browser_automate work.
- [ ] Mode switch: tool count changes on next message.
- [ ] Hot reload: external plugin JAR register/unregister tools automatically.
- [ ] Failure path: invalid mode/algorithm returns JSON error.

---

## Notes for the Implementer

- **Use IDEA Maven for all builds**, not shell `mvn`. Either the Maven tool window or `mcp__idea__build_project` / `mcp__idea__execute_terminal_command`. See `CLAUDE.md` "Build & Run".
- **JUnit 5 only.** No JUnit 4, no TestNG.
- **`AiServiceProvider` has static state.** Every test that touches it must clear tools + reset mode in `@BeforeEach`/`@AfterEach`.
- **JavaFX in tests:** The new tests we add don't touch the scene graph. The `SwissKitJPluginAiToolsDefaultTest` does implement `createView() { return null; }` which is fine because we never call it.
- **`PluginLoader(null)`:** Check the constructor. If it NPEs on null path, either pass `Path.of(System.getProperty("java.io.tmpdir"))` or add a null-tolerant constructor for tests.
- **When in doubt, grep.** `mcp__idea__search_in_files_by_text` and `mcp__idea__search_in_files_by_regex` are fast and accurate.

---
