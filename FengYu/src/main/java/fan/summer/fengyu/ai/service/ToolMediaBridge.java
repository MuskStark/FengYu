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
                .text("Visual output attached from the preceding tool result.")
                .media(parts)
                .build();
    }

    private static boolean hasPngSignature(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xff) == 0x89 && data[1] == 0x50 && data[2] == 0x4e
                && data[3] == 0x47 && data[4] == 0x0d && data[5] == 0x0a
                && data[6] == 0x1a && data[7] == 0x0a;
    }

    // ── Endpoint compatibility: media-free fallback ─────────────────────────

    /**
     * True when any message carries media parts. OpenAI-compatible endpoints serialize such
     * user messages with array-form {@code content} (text + image_url parts) — required for
     * vision, but strict gateways only accept string content.
     */
    static boolean containsMedia(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof UserMessage user
                    && user.getMedia() != null && !user.getMedia().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copy of {@code messages} with media attachments removed: each media-bearing
     * {@link UserMessage} becomes a plain text message (its explanatory text is kept).
     * Used after an endpoint rejected multimodal content so the chat can continue text-only.
     */
    static List<Message> withoutMedia(List<Message> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message instanceof UserMessage user
                    && user.getMedia() != null && !user.getMedia().isEmpty()) {
                String text = user.getText() == null || user.getText().isBlank()
                        ? "[image attachment omitted for this endpoint]"
                        : user.getText();
                out.add(new UserMessage(text));
            } else {
                out.add(message);
            }
        }
        return out;
    }

    /**
     * Heuristic for a provider 400 caused by array-form message content: observed from
     * Go-based OpenAI-compatible gateways as
     * {@code json: cannot unmarshal array into Go struct field ChatMessage.messages.content of type string}.
     * Matching is deliberately narrow (array-vs-string content mismatch) so unrelated bad
     * requests still surface; callers retry once without media and propagate the original
     * error when the retry also fails.
     */
    static boolean isMediaContentRejection(Throwable error) {
        for (Throwable c = error; c != null; c = c.getCause()) {
            String message = c.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("content")) continue;
            if (lower.contains("cannot unmarshal array") && lower.contains("string")) return true;
            if (lower.contains("content should be a string")) return true;
            if (lower.contains("content must be a string")) return true;
            if (lower.contains("expected string") && lower.contains("messages")) return true;
        }
        return false;
    }

    private static record Extracted(String text, List<AiMedia> media) {}
}
