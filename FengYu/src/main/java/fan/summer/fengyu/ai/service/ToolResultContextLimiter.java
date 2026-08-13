package fan.summer.fengyu.ai.service;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

/** Keeps tool output useful without allowing one response to consume the remaining context. */
final class ToolResultContextLimiter {

    static final int MAX_RESPONSE_CHARS = 64 * 1024;
    private static final String MARKER = "\n\n... [FengYu omitted %d middle characters] ...\n\n";

    private ToolResultContextLimiter() {
    }

    static List<Message> limit(List<Message> conversation) {
        if (conversation == null || conversation.isEmpty()) return List.of();
        List<Message> limited = new ArrayList<>(conversation.size());
        for (Message message : conversation) {
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                limited.add(message);
                continue;
            }
            List<ToolResponseMessage.ToolResponse> responses = toolMessage.getResponses().stream()
                    .map(response -> new ToolResponseMessage.ToolResponse(
                            response.id(), response.name(), limit(response.responseData())))
                    .toList();
            limited.add(ToolResponseMessage.builder()
                    .responses(responses)
                    .metadata(toolMessage.getMetadata())
                    .build());
        }
        return List.copyOf(limited);
    }

    static String limit(String value) {
        if (value == null || value.length() <= MAX_RESPONSE_CHARS) return value;
        int payloadBudget = MAX_RESPONSE_CHARS - 128;
        int head = payloadBudget * 3 / 4;
        int tail = payloadBudget - head;
        int omitted = value.length() - head - tail;
        return value.substring(0, head) + MARKER.formatted(omitted)
                + value.substring(value.length() - tail);
    }
}
