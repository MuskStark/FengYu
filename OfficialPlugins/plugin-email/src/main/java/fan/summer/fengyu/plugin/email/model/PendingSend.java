package fan.summer.fengyu.plugin.email.model;

import java.time.LocalDateTime;

/** Immutable persisted confirmation operation. */
public record PendingSend(long id, String confirmationId, long accountId, String mode,
        String snapshotJson, String status, LocalDateTime expiresAt, LocalDateTime updatedAt) { }
