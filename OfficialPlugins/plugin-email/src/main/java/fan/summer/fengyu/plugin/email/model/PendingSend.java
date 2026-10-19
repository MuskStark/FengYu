package fan.summer.fengyu.plugin.email.model;

import java.time.LocalDateTime;

/** Immutable persisted confirmation operation. {@code sentCount}/{@code totalCount} track delivery progress. */
public record PendingSend(long id, String confirmationId, long accountId, String mode,
        String snapshotJson, String status, int sentCount, int totalCount,
        LocalDateTime expiresAt, LocalDateTime updatedAt) { }
