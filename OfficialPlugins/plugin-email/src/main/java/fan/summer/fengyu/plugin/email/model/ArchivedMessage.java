package fan.summer.fengyu.plugin.email.model;

import java.time.Instant;

/** Archive metadata only; raw RFC-822 message content is never included. */
public record ArchivedMessage(long id, long accountId, String accountEmail, String folder, String messageUid,
        String subject, String fromAddress, String recipientsJson, Instant sentAt, Instant receivedAt,
        boolean hasAttachment, String bodyPreview, String emlPath, Instant archivedAt) {
}
