package fan.summer.fengyu.ai.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interactive-process sessions behind {@code workspace_exec interactive=true} +
 * {@code write_stdin}: long-running commands (dev servers, watchers, REPLs) keep running
 * past the tool call while the model polls output and feeds stdin by session id.
 *
 * <p>Lifecycle is deliberately lazy — no background reaper thread: every entry point sweeps
 * first. Sessions alive but untouched for {@link #IDLE_KILL_MILLIS} are killed forcibly
 * (a forgotten {@code --watch} must not leak forever); exited sessions linger
 * {@link #DEAD_RETENTION_MILLIS} so the model can poll the final output, then vanish.
 * Per-session pending output is bounded head+tail exactly like a one-shot exec result.</p>
 */
final class WorkspaceExecSessions {

    static final int MAX_SESSIONS = 16;
    static final int MAX_PENDING_CHARS = 64_000;
    private static final int HEAD_KEEP_CHARS = 8_000;
    static final long IDLE_KILL_MILLIS = 10 * 60 * 1000L;
    static final long DEAD_RETENTION_MILLIS = 5 * 60 * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    static final class Session {
        final String id;
        final Path root;
        final Process process;
        final long startedAt = System.currentTimeMillis();
        final StringBuilder pending = new StringBuilder(); // synchronized on itself
        volatile long lastAccess = startedAt;
        volatile boolean exited;
        volatile long exitedAt;
        volatile Integer exitCode;

        Session(String id, Path root, Process process) {
            this.id = id;
            this.root = root;
            this.process = process;
        }
    }

    /** Starts tracking a live process: drains its merged output on a reader thread and
     *  records exit on a waiter thread. The caller must NOT have closed the process stdin. */
    Session start(Path root, Process process) {
        sweep();
        while (sessions.size() >= MAX_SESSIONS) {
            Session oldest = sessions.values().stream()
                    .reduce((a, b) -> a.lastAccess <= b.lastAccess ? a : b).orElse(null);
            if (oldest == null) break;
            kill(oldest);
            sessions.remove(oldest.id);
        }
        String id = newId();
        Session session = new Session(id, root, process);
        sessions.put(id, session);
        Thread.ofVirtual().start(() -> {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = process.getInputStream().read(buffer)) >= 0) {
                    if (read == 0) continue;
                    appendPending(session, new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // process death closes the pipe; the waiter thread records the exit
            }
        });
        Thread.ofVirtual().start(() -> {
            try {
                int code = process.waitFor();
                session.exitCode = code;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.exitCode = -1;
            } finally {
                session.exited = true;
                session.exitedAt = System.currentTimeMillis();
            }
        });
        return session;
    }

    Session get(String sessionId, Path expectedRoot) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) return null;
        session.lastAccess = System.currentTimeMillis();
        if (expectedRoot != null
                && !session.root.toAbsolutePath().normalize()
                        .equals(expectedRoot.toAbsolutePath().normalize())) {
            return null; // a session is jailed to the workspace that started it
        }
        return session;
    }

    /** Kills idle-living sessions and forgets long-dead ones. Every entry point calls this. */
    void sweep() {
        long now = System.currentTimeMillis();
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
        long deadline = System.currentTimeMillis() + millis;
        while (!session.exited && System.currentTimeMillis() < deadline) {
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
     * untracked, defeating the idle-kill contract. Deepest-first so a dying parent cannot
     * reparent live children out of the handle set mid-kill (P1 fix; uniform on Linux).
     */
    static void killTree(Process process) {
        process.descendants()
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
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
