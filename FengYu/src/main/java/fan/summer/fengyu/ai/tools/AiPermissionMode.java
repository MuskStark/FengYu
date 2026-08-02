package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Codex-aligned local permission profiles for one AI chat turn. */
public enum AiPermissionMode {
    ASK_FOR_APPROVAL("ask-for-approval"),
    APPROVE_FOR_ME("approve-for-me"),
    FULL_ACCESS("full-access");

    private final String id;

    AiPermissionMode(String id) { this.id = id; }
    @JsonValue public String id() { return id; }

    @JsonCreator
    public static AiPermissionMode from(String value) {
        if (value != null) {
            for (AiPermissionMode mode : values()) if (mode.id.equals(value)) return mode;
        }
        return ASK_FOR_APPROVAL;
    }
}
