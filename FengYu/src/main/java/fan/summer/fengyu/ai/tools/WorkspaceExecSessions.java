package fan.summer.fengyu.ai.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Interactive-process sessions behind {@code workspace_exec interactive=true} +
 * {@code write_stdin}: long-running commands (dev servers, watchers, REPLs) keep running
 * past the tool call while the model polls output and feeds stdin by session id.
 *
 * <p>Lifecycle has two layers. Every entry point sweeps first (an interactive start or a
 * {@code write_stdin} cleans up opportunistically), AND a daemon sweeper thread rechecks
 * on a fixed period — a session the conversation never touches again must still die at the
 * ten-minute idle mark; without the background pass the "killed after 10 idle minutes"
 * contract the tool result promises would depend on future exec activity. Sessions alive
 * but untouched for {@link #IDLE_KILL_MILLIS} are killed forcibly (a forgotten
 * {@code --watch} must not leak forever); exited sessions linger
 * {@link #DEAD_RETENTION_MILLIS} so the model can poll the final output, then vanish.
 * Per-session pending output is bounded head+tail exactly like a one-shot exec result.
 * Sessions are private to the conversation that started them and jailed to its workspace
 * root.</p>
 */
final class WorkspaceExecSessions {

    static final int MAX_SESSIONS = 16;
    static final int MAX_PENDING_CHARS = 64_000;
    private static final int HEAD_KEEP_CHARS = 8_000;
    static final long IDLE_KILL_MILLIS = 10 * 60 * 1000L;
    static final long DEAD_RETENTION_MILLIS = 5 * 60 * 1000L;
    /** Default background sweep period; comfortably finer than the 10-minute idle window. */
    static final long SWEEP_PERIOD_MILLIS = 60_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** Injectable time source so idle-kill tests are deterministic. */
    private final LongSupplier clock;
    private final ScheduledExecutorService sweeper;

    WorkspaceExecSessions() {
        this(System::currentTimeMillis, SWEEP_PERIOD_MILLIS);
    }

    /** Test seam: a controllable clock and sweep period. */
    WorkspaceExecSessions(LongSupplier clock, long sweepPeriodMillis) {
        this.clock = clock;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fengyu-exec-session-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        this.sweeper.scheduleWithFixedDelay(this::sweepQuietly,
                sweepPeriodMillis, sweepPeriodMillis, TimeUnit.MILLISECONDS);
    }

    /** The periodic sweep must never throw — one failure would cancel the schedule. */
    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException ignored) {
            // keep the sweeper alive whatever a single pass hits
        }
    }

    static final class Session {
        final String id;
        final Long conversationId;
        final Path root;
        final Process process;
        final long startedAt;
        final StringBuilder pending = new StringBuilder(); // synchronized on itself
        volatile long lastAccess;
        volatile boolean exited;
        volatile long exitedAt;
        volatile Integer exitCode;

        Session(String id, Long conversationId, Path root, Process process, long now) {
            this.id = id;
            this.conversationId = conversationId;
            this.root = root;
            this.process = process;
            this.startedAt = now;
            this.lastAccess = now;
        }
    }

    /** Starts tracking a live process: drains its merged output on a reader thread and
     *  records exit on a waiter thread. The caller must NOT have closed the process stdin. */
    Session start(Long conversationId, Path root, Process process) {
        sweep();
        while (sessions.size() >= MAX_SESSIONS) {
            // Prefer evicting the CALLING conversation's oldest session: the pool cap must
            // hold, but a second conversation's live dev server should not pay for it.
            Session oldest = sessions.values().stream()
                    .min(java.util.Comparator
                            .comparing((Session session) -> Objects.equals(
                                    session.conversationId, conversationId) ? 0 : 1)
                            .thenComparingLong(session -> session.lastAccess))
                    .orElse(null);
            if (oldest == null) break;
            kill(oldest);
            sessions.remove(oldest.id);
        }
        String id = newId();
        Session session = new Session(id, conversationId, root, process, clock.getAsLong());
        sessions.put(id, session);
        Thread.ofVirtual().start(() -> drain(process.getInputStream(), session));
        Thread.ofVirtual().start(() -> {
            try {
                int code = process.waitFor();
                session.exitCode = code;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.exitCode = -1;
            } finally {
                session.exited = true;
                session.exitedAt = clock.getAsLong();
            }
        });
        return session;
    }

    Session get(String sessionId, Long expectedConversationId, Path expectedRoot) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) return null;
        if (expectedRoot != null
                && !session.root.toAbsolutePath().normalize()
                        .equals(expectedRoot.toAbsolutePath().normalize())) {
            return null; // a session is jailed to the workspace that started it
        }
        if (session.conversationId != null
                && !session.conversationId.equals(expectedConversationId)) {
            return null; // ...and private to the conversation that started it
        }
        // Liveness only counts for callers that passed the jail: refreshing it before the
        // checks would let a cross-conversation probe keep the victim session alive.
        session.lastAccess = clock.getAsLong();
        return session;
    }

    /** Kills idle-living sessions and forgets long-dead ones. Every entry point calls this,
     *  and the background sweeper calls it on its period. */
    void sweep() {
        long now = clock.getAsLong();
        for (Session session : sessions.values()) {
            if (session.exited) {
                if (now - session.exitedAt > DEAD_RETENTION_MILLIS) {
                    sessions.remove(session.id);
                }
                continue;
            }
            if (now - session.lastAccess > IDLE_KILL_MILLIS) {
                kill(session);
                sessions.remove(session.id);
            }
        }
    }

    /** Writes UTF-8 text to the process stdin (a trailing newline is the caller's choice). */
    void write(Session session, String chars) throws IOException {
        if (chars == null || chars.isEmpty()) return;
        OutputStream stdin = session.process.getOutputStream();
        stdin.write(chars.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** True once the process terminated and its exit code is recorded. */
    boolean awaitExit(Session session, long millis) throws InterruptedException {
        long deadline = clock.getAsLong() + millis;
        while (!session.exited && clock.getAsLong() < deadline) {
            Thread.sleep(25);
        }
        return session.exited;
    }

    /** Returns and clears the output accumulated since the previous call (bounded head+tail). */
    String takePending(Session session) {
        synchronized (session.pending) {
            String output = session.pending.toString();
            session.pending.setLength(0);
            return output;
        }
    }

    void kill(Session session) {
        killTree(session.process);
    }

    /**
     * Kill a command process AND every descendant it backgrounded. {@code destroyForcibly()}
     * on the Java handle only signals the direct child (the {@code /bin/sh -c} wrapper —
     * or bwrap/sandbox-exec when fenced); on macOS there is no PID namespace to reap the
     * rest, so {@code nohup ... &} survivors would outlive the session unfenced and
     * untracked, defeating the idle-kill contract. Descendants are signalled before the
     * root, in reverse-PID order — a heuristic for newest-spawned-first that in practice
     * kills grandchildren ahead of their parents (uniform on Linux).
     */
    static void killTree(Process process) {
        process.descendants()
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * Decodes the merged process output as UTF-8 with an incremental reader, so a multibyte
     * character split across pipe reads never degrades into replacement characters.
     */
    static void drain(InputStream input, Session session) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) appendPending(session, new String(buffer, 0, read));
            }
        } catch (IOException ignored) {
            // process death closes the pipe; the waiter thread records the exit
        }
    }

    private static void appendPending(Session session, String chunk) {
        synchronized (session.pending) {
            session.pending.append(chunk);
            if (session.pending.length() <= MAX_PENDING_CHARS) return;
            int tailKeep = MAX_PENDING_CHARS - HEAD_KEEP_CHARS;
            String head = session.pending.substring(0, HEAD_KEEP_CHARS);
            String tail = session.pending.substring(session.pending.length() - tailKeep);
            int dropped = session.pending.length() - MAX_PENDING_CHARS;
            session.pending.setLength(0);
            session.pending.append(head)
                    .append("\n…[").append(dropped).append(" characters of output dropped]\n")
                    .append(tail);
        }
    }

    private static String newId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder id = new StringBuilder(12);
        for (byte b : bytes) id.append(String.format("%02x", b));
        return id.toString();
    }
}
