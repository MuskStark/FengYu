package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.FengYuApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13 spike-as-test: proves a Spring AI {@code @Tool}-annotated method on a Spring bean is
 * discoverable and reports the expected tool name ({@code json_format}).
 *
 * <p><b>Why {@code ToolCallbacks.from(...)} and not {@code @Autowired Collection<ToolCallback>}:</b>
 * FengYu drives Spring AI through <em>manual {@code @Bean} configuration</em> (the non-starter
 * artifacts {@code spring-ai-openai/-anthropic/-ollama} + {@code spring-ai-client-chat}); none of
 * those jars ships a {@code spring.boot.autoconfigure.AutoConfiguration.imports} entry. Spring AI's
 * starter auto-config is what normally turns {@code @Tool}-annotated beans into {@link ToolCallback}
 * beans — without it, an {@code @Autowired Collection<ToolCallback>} finds zero beans regardless of
 * how many {@code @Tool} methods exist. The canonical manual-discovery path is
 * {@link ToolCallbacks#from(Object...)}, which walks an arbitrary bean's {@code @Tool} methods and
 * returns a {@link ToolCallback}[]; that is what Tasks 15 (AgentRunner) and 16
 * (AgentController {@code /api/agent/tools}) will aggregate.
 *
 * <p>The test still boots the full {@link FengYuApplication} context (matching the project's established
 * {@code @SpringBootTest} pattern — {@code HeadlessIntegrationTest}, {@code VirtualUserInitializerTest})
 * so it genuinely proves the {@code @Component} {@code JsonFormatTool} loads as a Spring bean; then it
 * runs that bean through {@code ToolCallbacks.from(...)} to prove the {@code @Tool} annotation yields
 * a discoverable callback whose {@link ToolCallback#getToolDefinition()} reports the name
 * {@code json_format}.
 */
@SpringBootTest(classes = FengYuApplication.class)
@ActiveProfiles("test")
class ToolDiscoveryTest {

    @Autowired
    JsonFormatTool jsonFormatTool;

    /** The aggregation bean from {@link AiToolDiscoveryConfig} — the single source of truth Tasks 15/16 inject. */
    @Autowired
    ToolCallback[] aiToolCallbacks;

    @Test
    void jsonFormatToolIsDiscoverableAsToolCallbackNamedJsonFormat() {
        ToolCallback[] callbacks = ToolCallbacks.from(jsonFormatTool);

        assertTrue(callbacks.length >= 1,
            "@Tool-annotated method should produce at least one ToolCallback, got " + callbacks.length);

        boolean found = java.util.Arrays.stream(callbacks)
            .map(tc -> tc.getToolDefinition().name())
            .anyMatch("json_format"::equals);
        assertTrue(found,
            "@Tool-annotated jsonFormat should be discovered with name 'json_format'");

        // Bonus: the discovered callback actually executes (proves it is wired to the real method,
        // not just registered by name — a tautology guard). Spring AI's ToolCallback.call(String)
        // takes a JSON object mapping the method's parameter name ("json") to its value, mirroring
        // how an LLM invokes the tool. writerWithDefaultPrettyPrinter preserves insertion order,
        // so the value is passed already-ordered for a deterministic expectation.
        ToolCallback jsonFormatCb = java.util.Arrays.stream(callbacks)
            .filter(tc -> tc.getToolDefinition().name().equals("json_format"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("json_format callback not found"));
        String result = jsonFormatCb.call("{\"json\":\"{\\\"a\\\":1,\\\"b\\\":2}\"}");
        assertEquals("{\n  \"a\" : 1,\n  \"b\" : 2\n}", result,
            "json_format tool should pretty-print the input JSON");
    }

    /**
     * Proves the aggregation bean ({@link AiToolDiscoveryConfig#aiToolCallbacks}) — the spec §3.2.3
     * single source of truth Tasks 15 (AgentRunner) and 16 (AgentController {@code /api/agent/tools})
     * inject — exposes the {@code json_format} callback and can resolve it by name. This is the
     * end-to-end path ({@code @Tool} bean → {@code @Configuration} aggregation → name-resolvable
     * callback), distinct from the direct {@code ToolCallbacks.from(...)} check above.
     */
    @Test
    void aggregatedToolCallbacksBeanExposesJsonFormatByName() {
        assertTrue(java.util.Arrays.stream(aiToolCallbacks)
                .map(tc -> tc.getToolDefinition().name())
                .anyMatch("json_format"::equals),
            "AiToolDiscoveryConfig.aiToolCallbacks bean should expose json_format for name-based resolution");
    }

    @Test
    void aggregatedCommandToolIsMarkedAsApprovalRequired() {
        ToolCallback command = java.util.Arrays.stream(aiToolCallbacks)
                .filter(tc -> tc.getToolDefinition().name().equals("execute_command"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("execute_command callback not found"));

        assertTrue(command instanceof ApprovalRequiredToolCallback,
                "execute_command must retain its mandatory-approval marker after discovery");
    }

    @Test
    void aggregatedWebToolsAreDiscoveredAsReadOnly() {
        for (String name : java.util.List.of("web_search", "web_fetch")) {
            ToolCallback callback = java.util.Arrays.stream(aiToolCallbacks)
                    .filter(tc -> tc.getToolDefinition().name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(name + " callback not found"));
            assertTrue(callback instanceof AuditedToolCallback,
                    name + " must retain its per-callback effect wrapper");
            assertEquals(ToolEffect.READ, ((AuditedToolCallback) callback).effect());
        }
    }
}
