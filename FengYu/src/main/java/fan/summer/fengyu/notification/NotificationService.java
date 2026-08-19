package fan.summer.fengyu.notification;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.notification.NotificationEntity;
import fan.summer.fengyu.database.repository.notification.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The single write path for host-side notifications: persist, fan out to live SSE
 * subscribers, and keep the history bounded.
 *
 * <p>Producers never touch the repository directly — they call {@link #create}, which
 * validates the payload (invalid input surfaces as {@link IllegalArgumentException} → HTTP
 * 400 through the global handler), stores the row, broadcasts the {@link NotificationView}
 * to every connected shell, and trims retention. Known producers: the REST endpoint backing
 * the plugin {@code notify} host bridge, agent run termination
 * ({@link AgentNotificationSink}), and any future host-side capability.
 */
@Service
public class NotificationService {

    /** Ceiling kept (newest first); overflow rows are deleted after each insert. */
    public static final int RETENTION_LIMIT = 200;
    /** Server-side ceiling for the list endpoint — a caller asking for more gets this. */
    public static final int MAX_LIST_LIMIT = 100;
    /** Longest stored body; longer producer text is truncated rather than rejected. */
    private static final int MAX_BODY_LENGTH = 2_000;

    private static final Set<String> LEVELS = Set.of("info", "success", "warning", "error");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final NotificationRepository repository;
    private final NotificationBroadcaster broadcaster;
    private final Clock clock;

    /** Production constructor — the @Autowired disambiguates from the test-seam overload. */
    @org.springframework.beans.factory.annotation.Autowired
    public NotificationService(NotificationRepository repository, NotificationBroadcaster broadcaster) {
        this(repository, broadcaster, Clock.systemDefaultZone());
    }

    /** Test seam so ordering/retention tests can assert deterministically. */
    NotificationService(NotificationRepository repository, NotificationBroadcaster broadcaster,
            Clock clock) {
        this.repository = repository;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    /** Validated levels — also the contract the frontend's level union mirrors. */
    public static boolean isValidLevel(String level) {
        return level != null && LEVELS.contains(level);
    }

    public NotificationView create(String source, String level, String title, String body, String link) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Notification source is required");
        }
        if (!isValidLevel(level)) {
            throw new IllegalArgumentException("Notification level must be one of " + LEVELS);
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Notification title is required");
        }
        NotificationEntity entity = new NotificationEntity();
        entity.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        entity.setSource(source.strip());
        entity.setLevel(level);
        entity.setTitle(title.strip());
        entity.setBody(truncate(body));
        entity.setLink(link == null || link.isBlank() ? null : link.strip());
        entity.setCreatedAt(LocalDateTime.now(clock));
        entity = repository.save(entity);
        trimRetention();
        NotificationView view = view(entity);
        broadcaster.broadcast(view);
        return view;
    }

    /** Newest-first history for the notification center. */
    public List<NotificationView> list(int limit, boolean unreadOnly) {
        int effective = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_LIST_LIMIT));
        if (unreadOnly) {
            return repository.findByUserIdAndReadAtIsNullOrderByCreatedAtAsc(
                            SecurityConstants.LOCAL_VIRTUAL_USER_ID).stream()
                    .map(NotificationService::view)
                    .toList()
                    .reversed();
        }
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(
                        SecurityConstants.LOCAL_VIRTUAL_USER_ID, PageRequest.of(0, effective)).stream()
                .map(NotificationService::view)
                .toList();
    }

    public long unreadCount() {
        return repository.countByUserIdAndReadAtIsNull(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
    }

    /** Marks one notification read (idempotent). Empty when the id is unknown. */
    public Optional<NotificationView> markRead(Long id) {
        return repository.findByIdAndUserId(id, SecurityConstants.LOCAL_VIRTUAL_USER_ID)
                .map(entity -> {
                    if (entity.getReadAt() == null) {
                        entity.setReadAt(LocalDateTime.now(clock));
                        entity = repository.save(entity);
                    }
                    return view(entity);
                });
    }

    /** Marks every unread notification read; returns how many flipped. */
    public int markAllRead() {
        List<NotificationEntity> unread = repository
                .findByUserIdAndReadAtIsNullOrderByCreatedAtAsc(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        LocalDateTime now = LocalDateTime.now(clock);
        unread.forEach(entity -> entity.setReadAt(now));
        repository.saveAll(unread);
        return unread.size();
    }

    /** Deletes one notification from the center. False when the id is unknown. */
    public boolean delete(Long id) {
        if (repository.findByIdAndUserId(id, SecurityConstants.LOCAL_VIRTUAL_USER_ID).isEmpty()) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    /** Live-subscription handle used by the SSE endpoint; invoke to unregister a dead client. */
    public Runnable subscribe(Consumer<NotificationView> subscriber) {
        return broadcaster.subscribe(subscriber);
    }

    /**
     * Wraps {@code delegate} so the agent run's terminal event (complete/error) also emits a
     * host notification. The wrapper is transparent for every non-terminal event and never
     * lets a notification failure break the run's real stream.
     */
    public fan.summer.fengyu.ai.agent.AgentEventSink forAgentRun(
            fan.summer.fengyu.ai.agent.AgentRun run, fan.summer.fengyu.ai.agent.AgentEventSink delegate) {
        return new AgentNotificationSink(run, delegate, this);
    }

    /**
     * Keeps at most {@link #RETENTION_LIMIT} rows: page 1 of a newest-first window (size
     * RETENTION_LIMIT) is exactly the overflow beyond the newest {@code RETENTION_LIMIT}
     * rows. The count check above keeps this at zero cost until the ceiling is first hit.
     */
    private void trimRetention() {
        long total = repository.countByUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        if (total <= RETENTION_LIMIT) return;
        Pageable overflow = PageRequest.of(1, RETENTION_LIMIT);
        List<Long> overflowIds = repository.findByUserIdOrderByCreatedAtDescIdDesc(
                        SecurityConstants.LOCAL_VIRTUAL_USER_ID, overflow).stream()
                .map(NotificationEntity::getId)
                .toList();
        if (!overflowIds.isEmpty()) repository.deleteAllById(overflowIds);
    }

    private static String truncate(String body) {
        String safe = body == null ? "" : body;
        return safe.length() <= MAX_BODY_LENGTH ? safe : safe.substring(0, MAX_BODY_LENGTH) + "…";
    }

    private static NotificationView view(NotificationEntity entity) {
        return new NotificationView(
                entity.getId(),
                entity.getSource(),
                entity.getLevel(),
                entity.getTitle(),
                entity.getBody() == null ? "" : entity.getBody(),
                entity.getLink(),
                entity.getReadAt() != null,
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().format(ISO),
                entity.getReadAt() == null ? null : entity.getReadAt().format(ISO));
    }
}
