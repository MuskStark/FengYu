package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the chat-file hand-off into the MCP tool callbacks (batch 3): the opaque
 * {@code (pluginId, FileRef id)} pair an active reference carries becomes the host path a
 * LOCAL stdio server's file parameter accepts. The grant service stays the only component
 * able to resolve references — the runtime manager receives a function, not the registry.
 */
@Configuration
public class McpFileHandoffConfig {

    @Bean
    public McpFileHandoffBinding mcpFileHandoffBinding(McpRuntimeManager runtime,
            PluginFileGrantService files) {
        runtime.setFileRefResolver((pluginId, refId) -> {
            try {
                return files.resolve(pluginId, refId);
            } catch (RuntimeException unknownRef) {
                return null; // a revoked grant degrades to "no hand-off", never a failed call
            }
        });
        return new McpFileHandoffBinding();
    }

    /** Marker bean: existence is the wiring contract, nothing depends on its state. */
    public static final class McpFileHandoffBinding {}
}
