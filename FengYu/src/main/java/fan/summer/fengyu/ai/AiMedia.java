package fan.summer.fengyu.ai;

import java.util.Objects;

/** Inline multimodal content carried with a FengYu conversation message. */
public record AiMedia(String mimeType, String base64Data, String name) {
    public AiMedia {
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        Objects.requireNonNull(base64Data, "base64Data must not be null");
        if (!mimeType.startsWith("image/")) {
            throw new IllegalArgumentException("only image media is supported");
        }
        if (name == null || name.isBlank()) name = "image";
    }
}
