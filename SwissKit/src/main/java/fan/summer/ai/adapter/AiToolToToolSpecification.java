package fan.summer.ai.adapter;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import fan.summer.ai.tools.AiToolDescriptions;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;

import java.util.ArrayList;
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

    private static JsonSchemaElement buildSchema(AiToolParam param) {
        if (param.enumValues() != null && !param.enumValues().isEmpty()) {
            return JsonEnumSchema.builder()
                .enumValues(param.enumValues())
                .description(param.description())
                .build();
        }
        String type = param.type() == null ? "string" : param.type();
        if (type.endsWith("[]")) {
            String elementType = type.substring(0, type.length() - 2);
            return JsonArraySchema.builder()
                .description(param.description())
                .items(primitiveSchema(elementType, param.description()))
                .build();
        }
        return primitiveSchema(type, param.description());
    }

    private static JsonSchemaElement primitiveSchema(String type, String description) {
        return switch (type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number"  -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            default        -> JsonStringSchema.builder().description(description).build();
        };
    }
}
