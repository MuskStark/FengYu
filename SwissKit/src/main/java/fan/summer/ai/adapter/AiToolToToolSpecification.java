package fan.summer.ai.adapter;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a SwissKitJ {@link AiTool} into a LangChain4j {@link ToolSpecification}
 * for use with cloud chat models.
 */
public final class AiToolToToolSpecification {

    private AiToolToToolSpecification() {}

    public static ToolSpecification convert(AiTool tool) {
        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();

        for (AiToolParam param : tool.getParameters()) {
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
            .description(tool.getDescription())
            .parameters(params)
            .build();
    }

    private static JsonSchemaElement buildSchema(AiToolParam param) {
        if (param.enumValues() != null && !param.enumValues().isEmpty()) {
            return JsonEnumSchema.builder()
                .enumValues(param.enumValues())
                .description(param.description())
                .build();
        }
        return switch (param.type()) {
            case "integer", "number" -> JsonIntegerSchema.builder().description(param.description()).build();
            case "boolean" -> JsonBooleanSchema.builder().description(param.description()).build();
            default -> JsonStringSchema.builder().description(param.description()).build();
        };
    }
}
