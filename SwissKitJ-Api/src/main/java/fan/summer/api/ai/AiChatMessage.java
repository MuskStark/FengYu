package fan.summer.api.ai;

/**
 * A single message in an AI chat conversation.
 */
public record AiChatMessage(Role role, String content) {

    public enum Role {
        SYSTEM, USER, ASSISTANT
    }

    public static AiChatMessage system(String content) {
        return new AiChatMessage(Role.SYSTEM, content);
    }

    public static AiChatMessage user(String content) {
        return new AiChatMessage(Role.USER, content);
    }

    public static AiChatMessage assistant(String content) {
        return new AiChatMessage(Role.ASSISTANT, content);
    }
}
