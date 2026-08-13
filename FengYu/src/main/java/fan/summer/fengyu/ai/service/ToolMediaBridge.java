package fan.summer.fengyu.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiMedia;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts inline image bytes from JSON tool envelopes into Spring AI multimodal messages. */
final class ToolMediaBridge {

    static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolMediaBridge() {}

    record Result(List<Message> messages, List<List<AiMedia>> lastResponseMedia) {
        Result {
            messages = List.copyOf(messages);
            lastResponseMedia = lastResponseMedia.stream().map(List::copyOf).toList();
        }
    }

    /**
     * Removes {@code imageBase64} from tool-result JSON, retains compact attachment metadata,
     * and appends a {@link UserMessage} carrying the decoded image after each affected tool
     * response. The returned media list is index-aligned with the final ToolResponseMessage so
     * FengYu history can preserve images for subsequent turns.
     */
    static Result extract(List<Message> source) {
        List<Message> out = new ArrayList<>(source.size() + 1);
        List<List<AiMedia>> lastMedia = List.of();
        for (Message message : source) {
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                out.add(message);
                continue;
            }
            List<ToolResponseMessage.ToolResponse> sanitized = new ArrayList<>();
            List<List<AiMedia>> responseMedia = new ArrayList<>();
            List<AiMedia> messageMedia = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                Extracted extracted = extract(response.responseData(), response.name());
                sanitized.add(new ToolResponseMessage.ToolResponse(
                        response.id(), response.name(), extracted.text()));
                responseMedia.add(extracted.media());
                messageMedia.addAll(extracted.media());
            }
            out.add(ToolResponseMessage.builder()
                    .responses(sanitized)
                    .metadata(toolMessage.getMetadata())
                    .build());
            if (!messageMedia.isEmpty()) out.add(mediaMessage(messageMedia));
            lastMedia = responseMedia;
        }
        return new Result(out, lastMedia);
    }

    private static Extracted extract(String text, String toolName) {
        if (text == null || text.isBlank() || text.charAt(0) != '{') {
            return new Extracted(text, List.of());
        }
        try {
            Map<String, Object> envelope = JSON.readValue(text, new TypeReference<>() {});
            Object encoded = envelope.get("imageBase64");
            if (!(encoded instanceof String base64) || base64.isBlank()) {
                return new Extracted(text, List.of());
            }
            String mimeType = envelope.get("mimeType") instanceof String mime
                    && mime.startsWith("image/") ? mime : "image/png";
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                return new Extracted(text, List.of());
            }
            if ("image/png".equals(mimeType) && !hasPngSignature(bytes)) {
                return new Extracted(text, List.of());
            }
            Map<String, Object> sanitized = new LinkedHashMap<>(envelope);
            sanitized.remove("imageBase64");
            sanitized.put("imageAttached", true);
            sanitized.put("imageBytes", bytes.length);
            AiMedia media = new AiMedia(mimeType, base64,
                    (toolName == null || toolName.isBlank() ? "browser_screenshot" : toolName) + ".png");
            return new Extracted(JSON.writeValueAsString(sanitized), List.of(media));
        } catch (Exception ignored) {
            return new Extracted(text, List.of());
        }
    }

    private static UserMessage mediaMessage(List<AiMedia> media) {
        List<Media> parts = media.stream().map(item -> Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(item.mimeType()))
                .data(new ByteArrayResource(Base64.getDecoder().decode(item.base64Data())))
                .name(item.name())
                .build()).toList();
        return UserMessage.builder()
                .text("Visual output attached from the preceding browser tool result.")
                .media(parts)
                .build();
    }

    private static boolean hasPngSignature(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xff) == 0x89 && data[1] == 0x50 && data[2] == 0x4e
                && data[3] == 0x47 && data[4] == 0x0d && data[5] == 0x0a
                && data[6] == 0x1a && data[7] == 0x0a;
    }

    private record Extracted(String text, List<AiMedia> media) {}
}
