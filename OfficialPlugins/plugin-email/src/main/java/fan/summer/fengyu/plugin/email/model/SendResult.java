package fan.summer.fengyu.plugin.email.model;

/** Immutable, credential-free result of an SMTP operation. */
public record SendResult(boolean success, String messageId, String errorMessage) {
    public static SendResult success(String messageId) {
        return new SendResult(true, messageId, null);
    }

    public static SendResult failure(String errorMessage) {
        return new SendResult(false, null, errorMessage);
    }
}
