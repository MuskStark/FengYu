package fan.summer.fengyu.notification;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.notification.NotificationEntity;
import fan.summer.fengyu.database.repository.notification.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the single write path of host notifications: payload validation,
 * persistence + live fan-out, read-state transitions, and the retention trim.
 */
class NotificationServiceTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationBroadcaster broadcaster = new NotificationBroadcaster();
    private final Clock fixed = Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneOffset.UTC);

    private NotificationService service() {
        return new NotificationService(repository, broadcaster, fixed);
    }

    private static NotificationEntity entity(long id, String title, LocalDateTime created) {
        NotificationEntity e = new NotificationEntity();
        e.setId(id);
        e.setSource("host");
        e.setLevel("info");
        e.setTitle(title);
        e.setBody("body " + id);
        e.setCreatedAt(created);
        e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        return e;
    }

    // ── create ────────────────────────────────────────────────────────

    @Test
    void createRejectsInvalidLevelAndBlankTitle() {
        NotificationService service = service();
        assertThrows(IllegalArgumentException.class,
                () -> service.create("host", "loud", "t", "b", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("host", "info", "  ", "b", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(" ", "info", "t", "b", null));
    }

    @Test
    void createPersistsAndBroadcastsToLiveSubscribers() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> {
            NotificationEntity saved = inv.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        when(repository.countByUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID)).thenReturn(1L);

        List<NotificationView> received = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        service().subscribe(view -> {
            received.add(view);
            delivered.countDown();
        });

        NotificationView view = service().create("plugin:demo", "success", "Hello", "World", "/tools");

        assertEquals(7L, view.id());
        assertEquals("plugin:demo", view.source());
        assertEquals("Hello", view.title());
        assertTrue(delivered.await(2, TimeUnit.SECONDS),
                "the broadcaster's virtual-thread drainer must deliver without the caller blocking");
        assertEquals(1, received.size());
        assertEquals(view, received.getFirst());
    }

    // ── read state ────────────────────────────────────────────────────

    @Test
    void markReadIsIdempotentAndStampedOnce() {
        NotificationEntity unread = entity(1L, "t", LocalDateTime.now(fixed));
        when(repository.findByIdAndUserId(1L, SecurityConstants.LOCAL_VIRTUAL_USER_ID))
                .thenReturn(Optional.of(unread));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationService service = service();
        NotificationView first = service.markRead(1L).orElseThrow();
        assertTrue(first.read());
        assertEquals("2026-08-19T10:00", first.readAt().substring(0, 16));

        // Second call must not re-stamp: save only happened for the first flip.
        service.markRead(1L);
        verify(repository).save(unread);
    }

    @Test
    void markAllReadFlipsEveryUnreadRow() {
        List<NotificationEntity> unread = List.of(entity(1L, "a", LocalDateTime.now(fixed)),
                entity(2L, "b", LocalDateTime.now(fixed)));
        when(repository.findByUserIdAndReadAtIsNullOrderByCreatedAtAsc(
                SecurityConstants.LOCAL_VIRTUAL_USER_ID)).thenReturn(unread);

        assertEquals(2, service().markAllRead());
        // LocalDateTime.toString() drops zero seconds: 10:00:00 renders as "10:00".
        unread.forEach(e -> assertEquals("2026-08-19T10:00", e.getReadAt().toString()));
    }

    // ── retention ─────────────────────────────────────────────────────

    @Test
    void retentionTrimsOnlyBeyondTheLimit() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.countByUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID))
                .thenReturn((long) (NotificationService.RETENTION_LIMIT + 3));
        List<NotificationEntity> overflow = List.of(entity(201L, "old1", LocalDateTime.now(fixed)),
                entity(202L, "old2", LocalDateTime.now(fixed)),
                entity(203L, "old3", LocalDateTime.now(fixed)));
        // Page 1 of a newest-first RETENTION_LIMIT window is exactly the overflow set.
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(
                ArgumentMatchers.eq(SecurityConstants.LOCAL_VIRTUAL_USER_ID),
                ArgumentMatchers.eq(PageRequest.of(1, NotificationService.RETENTION_LIMIT))))
                .thenReturn(overflow);

        service().create("host", "info", "new", "b", null);

        verify(repository).deleteAllById(List.of(201L, 202L, 203L));
    }

    @Test
    void retentionIsFreeBelowTheLimit() {
        when(repository.countByUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID))
                .thenReturn((long) NotificationService.RETENTION_LIMIT);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().create("host", "info", "new", "b", null);

        verify(repository, never()).deleteAllById(any());
        verify(repository, never()).findByUserIdOrderByCreatedAtDescIdDesc(
                any(), any(Pageable.class));
    }

    // ── view mapping ──────────────────────────────────────────────────

    @Test
    void longBodiesAreTruncatedNotRejected() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.countByUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID)).thenReturn(0L);

        String longBody = "x".repeat(5_000);
        NotificationView view = service().create("host", "warning", "t", longBody, null);

        assertEquals(2_001, view.body().length());
        assertTrue(view.body().endsWith("…"));
        assertNull(view.link());
        assertFalse(view.read());
    }
}
