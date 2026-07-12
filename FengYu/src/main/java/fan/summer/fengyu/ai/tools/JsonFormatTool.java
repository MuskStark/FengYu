package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Replaces the deleted {@code BuiltinJsonFormatTool} as a Spring AI-native tool.
 *
 * <p>Annotated with {@link Tool} so Spring AI's {@code ToolCallbacks.from(Object...)} discovers the
 * method and exposes it as a {@link org.springframework.ai.tool.ToolCallback}. The bean is a plain
 * {@link Component} so it loads in the Spring context; the tool name is set explicitly to
 * {@code json_format} (Spring AI otherwise derives the name from the method identifier, which would
 * register as {@code jsonFormat}).
 *
 * <p>This is the template for all future AI tools: declare a {@code @Component} with one or more
 * {@code @Tool} methods and {@code implements FengYuTool}; {@link AiToolDiscoveryConfig} then
 * aggregates every {@code FengYuTool} bean via {@code ToolCallbacks.from(...)} with no further
 * config edit.
 */
@Component
public class JsonFormatTool implements FengYuTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Pretty-print a JSON string.
     *
     * @param json raw JSON text to format
     * @return pretty-printed JSON, or an "Invalid JSON: ..." message on a parse failure
     */
    @Tool(name = "json_format",
          description = "Pretty-print a JSON string. Returns formatted JSON.")
    public String jsonFormat(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return "Invalid JSON: " + e.getMessage();
        }
    }
}
