package fan.summer.ai.model;

import fan.summer.api.ai.AiChatMessage;

import java.util.List;

/**
 * Builds chat prompts from message history using model-specific templates.
 * Detects template format from GGUF metadata.
 */
public class ChatTemplate {

    public enum TemplateType { LLAMA3, CHATML, MISTRAL, GEMMA, GENERIC }

    private final TemplateType type;
    private final String eosToken;
    private final String bosToken;

    public ChatTemplate(GGUFModel model) {
        this(model.getMetaString("tokenizer.chat_template", ""));
    }

    public ChatTemplate(String rawTemplate) {
        if (rawTemplate == null) rawTemplate = "";

        if (rawTemplate.contains("begin_of_text") || rawTemplate.contains("start_header_id")) {
            type = TemplateType.LLAMA3;
            eosToken = "<|eot_id|>";
            bosToken = "<|begin_of_text|>";
        } else if (rawTemplate.contains("im_start")) {
            type = TemplateType.CHATML;
            eosToken = "<|im_end|>";
            bosToken = "";
        } else if (rawTemplate.contains("[/INST]")) {
            type = TemplateType.MISTRAL;
            eosToken = "</s>";
            bosToken = "<s>";
        } else if (rawTemplate.contains("<start_of_turn>")) {
            type = TemplateType.GEMMA;
            eosToken = "<end_of_turn>";
            bosToken = "";
        } else {
            // Empty / unrecognized template → default to ChatML, which is the
            // most common modern format and matches the native backend's prior
            // hard-coded behaviour.
            type = TemplateType.CHATML;
            eosToken = "<|im_end|>";
            bosToken = "";
        }
    }

    public TemplateType getType() { return type; }

    public String getEosToken() { return eosToken; }
    public String getBosToken() { return bosToken; }

    /**
     * Build the full prompt string from chat history.
     */
    public String buildPrompt(List<AiChatMessage> history, String systemPrompt) {
        return switch (type) {
            case LLAMA3 -> buildLlama3(history, systemPrompt);
            case CHATML -> buildChatML(history, systemPrompt);
            case MISTRAL -> buildMistral(history, systemPrompt);
            case GEMMA -> buildGemma(history, systemPrompt);
            case GENERIC -> buildGeneric(history);
        };
    }

    private String buildLlama3(List<AiChatMessage> history, String system) {
        var sb = new StringBuilder(bosToken);
        if (system != null && !system.isEmpty()) {
            sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
              .append(system).append(eosToken);
        }
        for (var msg : history) {
            sb.append("<|start_header_id|>").append(roleStr(msg)).append("<|end_header_id|>\n\n")
              .append(msg.content()).append(eosToken);
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n");
        return sb.toString();
    }

    private String buildChatML(List<AiChatMessage> history, String system) {
        var sb = new StringBuilder();
        if (system != null && !system.isEmpty()) {
            sb.append("<|im_start|>system\n").append(system).append(eosToken).append("\n");
        }
        for (var msg : history) {
            if (msg.role() == AiChatMessage.Role.TOOL) {
                sb.append("<|im_start|>tool\n").append(msg.content()).append(eosToken).append("\n");
            } else if (msg.role() == AiChatMessage.Role.ASSISTANT && msg.hasToolCalls()) {
                sb.append("<|im_start|>assistant\n");
                if (msg.content() != null && !msg.content().isEmpty()) {
                    sb.append(msg.content()).append("\n");
                }
                sb.append(eosToken).append("\n");
            } else {
                sb.append("<|im_start|>").append(roleStr(msg)).append("\n")
                  .append(msg.content()).append(eosToken).append("\n");
            }
        }
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    private String buildMistral(List<AiChatMessage> history, String system) {
        var sb = new StringBuilder(bosToken);
        if (system != null && !system.isEmpty()) {
            sb.append("[INST] ").append(system).append(" [/INST] ");
        }
        boolean inInst = false;
        for (var msg : history) {
            if (msg.role() == AiChatMessage.Role.USER) {
                if (!inInst) sb.append("[INST] ");
                sb.append(msg.content()).append(" [/INST] ");
                inInst = true;
            } else if (msg.role() == AiChatMessage.Role.ASSISTANT) {
                sb.append(msg.content()).append(eosToken);
                inInst = false;
            }
        }
        return sb.toString();
    }

    private String buildGemma(List<AiChatMessage> history, String system) {
        // Gemma protocol rules that bit us before:
        //  • assistant role is rendered as "model" (NOT "assistant")
        //  • Gemma 3 supports a dedicated `<start_of_turn>system` block; merging
        //    the system prompt into the user turn made the model treat the system
        //    text as the user's first sentence and parrot it back as its answer.
        var sb = new StringBuilder();

        if (system != null && !system.isEmpty()) {
            sb.append("<start_of_turn>system\n")
              .append(system).append(eosToken).append("\n");
        }

        for (var msg : history) {
            if (msg.role() == AiChatMessage.Role.SYSTEM) {
                if (msg.content() != null && !msg.content().isEmpty()) {
                    sb.append("<start_of_turn>system\n")
                      .append(msg.content()).append(eosToken).append("\n");
                }
                continue;
            }
            String role = (msg.role() == AiChatMessage.Role.ASSISTANT) ? "model" : "user";
            sb.append("<start_of_turn>").append(role).append("\n")
              .append(msg.content() == null ? "" : msg.content())
              .append(eosToken).append("\n");
        }

        sb.append("<start_of_turn>model\n");
        return sb.toString();
    }

    private String buildGeneric(List<AiChatMessage> history) {
        var sb = new StringBuilder();
        for (var msg : history) {
            sb.append(roleStr(msg)).append(": ").append(msg.content()).append("\n");
        }
        sb.append("assistant: ");
        return sb.toString();
    }

    private String roleStr(AiChatMessage msg) {
        return switch (msg.role()) {
            case TOOL -> "tool";
            default -> msg.role().name().toLowerCase();
        };
    }
}
