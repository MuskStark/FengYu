package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 regression: the code-mode description wrapper must PRESERVE the
 * {@link AuditedToolCallback} contract — the approval policy keys on it, so a plain
 * wrapper silently dropped exec's COMMAND approval tier.
 */
class AiToolRegistryCodeModeWrapperTest {

    private static ToolCallback auditedCommand(String name) {
        return new AuditedToolCallback() {
            @Override public ToolEffect effect() { return ToolEffect.COMMAND; }
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name)
                        .description("original").inputSchema("{}").build();
            }
            @Override public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    @Test
    void wrapperPreservesTheAuditedContractAndReplacesTheDescription() {
        ToolCallback wrapped = AiToolRegistry.withDefinition(
                auditedCommand("exec"), "the dynamic description");
        assertInstanceOf(AuditedToolCallback.class, wrapped,
                "the approval policy must still see the effect tier");
        assertEquals(ToolEffect.COMMAND, ((AuditedToolCallback) wrapped).effect());
        assertEquals("the dynamic description", wrapped.getToolDefinition().description());
        assertEquals("exec", wrapped.getToolDefinition().name());
        assertEquals("ok", wrapped.call("{}"));
    }

    @Test
    void plainCallbacksWrapWithoutTheContract() {
        ToolCallback plain = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("x")
                        .description("original").inputSchema("{}").build();
            }
            @Override public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
        ToolCallback wrapped = AiToolRegistry.withDefinition(plain, "replaced");
        assertTrue(!(wrapped instanceof AuditedToolCallback));
        assertEquals("replaced", wrapped.getToolDefinition().description());
    }
}
