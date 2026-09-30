package fan.summer.fengyu.notification;

/**
 * Wire shape of a host notification, shared by the REST responses, the live SSE
 * {@code notification} events, and the agent/plugin producers. Timestamps are ISO-8601
 * local-date-time strings (the same convention as the other controllers) so the frontend
 * types stay plain strings.
 */
public record NotificationView(
        Long id,
        String source,
        String level,
        String title,
        String body,
        String link,
        boolean read,
        String createdAt,
        String readAt) {}
