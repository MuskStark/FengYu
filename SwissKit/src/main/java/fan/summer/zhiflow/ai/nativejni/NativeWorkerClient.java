package fan.summer.zhiflow.ai.nativejni;

import fan.summer.zhiflow.ai.util.JsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages a child JVM process for isolated native AI inference.
 *
 * <p>Spawns {@link NativeWorkerMain} in a separate {@code java} process and
 * communicates via line-delimited JSON over stdin/stdout. If the child crashes
 * (SIGILL, SIGSEGV, etc.), pending callbacks receive an error and the child is
 * automatically restarted. After {@value MAX_CONSECUTIVE_CRASHES} consecutive
 * crashes, {@link #shouldFallback()} returns {@code true}.
 */
public class NativeWorkerClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NativeWorkerClient.class);
    private static final int MAX_CONSECUTIVE_CRASHES = 3;
    /** Minimum time window (ms) between crash resets to prevent restart loops. */
    private static final long CRASH_WINDOW_MS = TimeUnit.MINUTES.toMillis(5);

    private Process childProcess;
    private BufferedWriter writer;
    private Thread readerThread;
    private Thread errorReaderThread;
    private volatile boolean running;

    private final AtomicInteger genIdCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, PendingGenerate> pendingGenerates = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> loadFuture;

    private final AtomicInteger consecutiveCrashes = new AtomicInteger(0);
    private final AtomicLong firstCrashTime = new AtomicLong(0);
    private volatile ModelParams lastModelParams;

    /**
     * Spawns the child JVM process. No-op if already running.
     *
     * @throws IOException if the process cannot be started
     */
    public synchronized void spawn() throws IOException {
        if (running && childProcess != null && childProcess.isAlive()) return;

        String javaHome = System.getProperty("java.home");
        String classpath = System.getProperty("java.class.path");
        String separator = File.pathSeparator;

        String cleanCp = cleanClasspath(classpath, separator);

        // Build the worker command. The worker's stdout is the JSON IPC channel back
        // to this host, so the default logback.xml (ConsoleAppender → System.out) can't
        // be used — every worker log.info(...) would corrupt the protocol. Pin a
        // worker-only config with no console appender (see logback-worker.xml). Also
        // forward the host's absolute swisskit.log.dir so the worker's file appender
        // writes to the *same* log as the host, independent of the child's CWD.
        List<String> cmd = new ArrayList<>(List.of(
            javaHome + "/bin/java", "-cp", cleanCp,
            "-Dfile.encoding=UTF-8",
            "-Dlogback.configurationFile=logback-worker.xml"
        ));
        String logDir = System.getProperty("swisskit.log.dir");
        if (logDir != null && !logDir.isBlank()) {
            cmd.add("-Dswisskit.log.dir=" + logDir);
        }
        cmd.add("fan.summer.zhiflow.ai.nativejni.NativeWorkerMain");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        childProcess = pb.start();

        writer = new BufferedWriter(new OutputStreamWriter(childProcess.getOutputStream()));
        running = true;

        readerThread = new Thread(this::readLoop, "ai-worker-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // Drain stderr on its own thread: an undrained pipe (~16 KB on macOS) can
        // fill and stall the worker, and llama.cpp writes its crash/diagnostic
        // output here — capturing it turns an opaque worker death into a logged one.
        errorReaderThread = new Thread(this::readErrorLoop, "ai-worker-stderr-reader");
        errorReaderThread.setDaemon(true);
        errorReaderThread.start();

        log.info("AI worker process spawned, pid={}", childProcess.pid());
    }

    /**
     * Sends a load command and waits for the child to confirm.
     *
     * @param params model parameters
     * @throws Exception if loading fails or times out (120 s)
     */
    public synchronized void loadModel(ModelParams params) throws Exception {
        lastModelParams = params;

        if (!isAlive()) spawn();

        loadFuture = new CompletableFuture<>();
        boolean sent = sendCommand(Map.of(
            "cmd", "load",
            "modelPath", params.getModelPath(),
            "ctxLength", params.getCtxLength(),
            "threads", params.getThreads(),
            "gpuLayers", params.getGpuLayers(),
            "flashAttention", params.isFlashAttention()
        ));
        if (!sent) {
            loadFuture = null;
            throw new RuntimeException("Failed to send load command to AI worker");
        }

        loadFuture.get(120, TimeUnit.SECONDS);
        loadFuture = null;
    }

    /**
     * Sends a generate command. Responses are dispatched asynchronously
     * to the provided callback on the reader thread.
     */
    public void generate(String prompt, GenerateParams params, GenerateCallback callback) {
        String id = "gen-" + genIdCounter.incrementAndGet();
        pendingGenerates.put(id, new PendingGenerate(callback));
        boolean sent = sendCommand(Map.of(
            "cmd", "generate",
            "id", id,
            "prompt", prompt,
            "maxTokens", params.getMaxNewTokens(),
            "temperature", params.getTemperature(),
            "topP", params.getTopP(),
            "repeatPenalty", params.getRepeatPenalty(),
            "seed", params.getSeed()
        ));
        if (!sent) {
            pendingGenerates.remove(id);
            callback.onError("Failed to send generate command to AI worker");
        }
    }

    /** Sends an unload command. */
    public void unload() {
        sendCommand(Map.of("cmd", "unload"));
    }

    /** @return true if the child process is alive */
    public boolean isAlive() {
        return running && childProcess != null && childProcess.isAlive();
    }

    /** @return true if the child has crashed too many times and Java fallback should be used */
    public boolean shouldFallback() {
        return consecutiveCrashes.get() >= MAX_CONSECUTIVE_CRASHES;
    }

    @Override
    public void close() {
        running = false;
        try { unload(); } catch (Exception ignored) {}
        try { writer.close(); } catch (Exception ignored) {}   // EOF → child exits gracefully
        if (childProcess != null) {
            // Give the child a moment to exit on stdin EOF (after it runs unload +
            // ctx.close()), so we don't SIGKILL it mid-graceful-shutdown. Force-kill
            // only as a fallback if it doesn't exit in time.
            try {
                if (!childProcess.waitFor(2, TimeUnit.SECONDS)) {
                    childProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                childProcess.destroyForcibly();
            }
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (errorReaderThread != null) {
            errorReaderThread.interrupt();
        }
    }

    // ── Reader thread ─────────────────────────────────────────

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(childProcess.getInputStream()))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> resp = JsonHelper.parseObject(line);
                    handleResponse(resp);
                } catch (Exception e) {
                    log.warn("Failed to parse worker response: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) log.error("AI worker reader error: {}", e.getMessage());
        } finally {
            handleChildExit();
        }
    }

    /**
     * Drains the child's stderr line by line into the host log at DEBUG (so it
     * lands in {@code .swisskit/logs/swisskit.log} without flooding the console).
     * EOF ends the thread naturally when the worker exits; only the stdout reader
     * is authoritative for crash detection — this thread never calls
     * {@code handleChildExit()}.
     */
    private void readErrorLoop() {
        try (BufferedReader errReader = new BufferedReader(
                new InputStreamReader(childProcess.getErrorStream()))) {
            String line;
            while ((line = errReader.readLine()) != null) {
                if (line.isBlank()) continue;
                log.debug("[worker-stderr] {}", line);
            }
        } catch (IOException e) {
            if (running) log.debug("AI worker stderr reader closed: {}", e.getMessage());
        }
    }

    private void handleResponse(Map<String, Object> resp) {
        String type = (String) resp.get("type");
        if (type == null) return;

        switch (type) {
            case "loaded" -> {
                log.info("AI worker loaded model: {}", resp.get("modelPath"));
                if (loadFuture != null) loadFuture.complete(null);
            }
            case "token" -> {
                String id = (String) resp.get("id");
                String text = (String) resp.get("text");
                PendingGenerate pg = id != null ? pendingGenerates.get(id) : null;
                if (pg != null && text != null) {
                    pg.callback.onToken(text);
                }
            }
            case "done" -> {
                String id = (String) resp.get("id");
                PendingGenerate pg = id != null ? pendingGenerates.remove(id) : null;
                if (pg != null) {
                    String fullText = (String) resp.getOrDefault("fullText", "");
                    int tokenCount = resp.get("tokenCount") instanceof Number n ? n.intValue() : 0;
                    double tokPerSec = resp.get("tokPerSec") instanceof Number n ? n.doubleValue() : 0.0;
                    pg.callback.onDone(fullText, tokenCount, tokPerSec);
                }
                // Only reset crash counter if enough time has passed since first crash
                long firstCrash = firstCrashTime.get();
                if (firstCrash > 0 && (System.currentTimeMillis() - firstCrash) > CRASH_WINDOW_MS) {
                    // Enough time elapsed — safe to reset
                    consecutiveCrashes.set(0);
                    firstCrashTime.set(0);
                }
            }
            case "error" -> {
                String id = (String) resp.get("id");
                String message = (String) resp.getOrDefault("message", "Unknown error");
                if (id != null) {
                    PendingGenerate pg = pendingGenerates.remove(id);
                    if (pg != null) pg.callback.onError(message);
                }
                if (loadFuture != null && !loadFuture.isDone()) {
                    loadFuture.completeExceptionally(new RuntimeException(message));
                }
            }
            case "unloaded" -> {
                if (loadFuture != null && !loadFuture.isDone()) {
                    loadFuture.complete(null);
                }
            }
            case "pong" -> { /* heartbeat, no action needed */ }
        }
    }

    private void handleChildExit() {
        if (!running) return;

        // The reader hit EOF on the child's stdout, but the OS may not have reaped
        // the process yet. Calling exitValue() now throws IllegalThreadStateException
        // ("process hasn't exited"), which used to escape this finally block, kill the
        // reader thread, and skip every recovery step below (pending callbacks were
        // left hanging and auto-restart never ran). Wait briefly for a real exit code;
        // force-kill only if it lingers so spawn() below doesn't leak the old process.
        int exitCode = -1;
        if (childProcess != null) {
            try {
                if (!childProcess.waitFor(2, TimeUnit.SECONDS)) {
                    childProcess.destroyForcibly();
                    childProcess.waitFor(1, TimeUnit.SECONDS);
                }
                exitCode = childProcess.exitValue();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (IllegalThreadStateException ignored) {
                // still alive even after the force-kill window — proceed as abnormal exit
            }
        }

        log.warn("AI worker process exited (exit={})", exitCode);

        for (Map.Entry<String, PendingGenerate> entry : pendingGenerates.entrySet()) {
            entry.getValue().callback.onError("AI worker process crashed");
        }
        pendingGenerates.clear();

        if (loadFuture != null && !loadFuture.isDone()) {
            loadFuture.completeExceptionally(
                new RuntimeException("AI worker process crashed during load"));
        }

        int crashes = consecutiveCrashes.incrementAndGet();
        // Record the time of the first crash in this window
        firstCrashTime.compareAndSet(0, System.currentTimeMillis());
        running = false;

        // Check if crash rate exceeds limit
        boolean exceededRate = false;
        long firstCrash = firstCrashTime.get();
        if (firstCrash > 0) {
            long elapsed = System.currentTimeMillis() - firstCrash;
            // If we've had MAX crashes within the window, give up
            if (crashes >= MAX_CONSECUTIVE_CRASHES && elapsed < CRASH_WINDOW_MS) {
                exceededRate = true;
            }
            // If the window has passed, reset counters and allow retry
            if (elapsed >= CRASH_WINDOW_MS) {
                consecutiveCrashes.set(1);
                firstCrashTime.set(System.currentTimeMillis());
            }
        }

        if (exceededRate) {
            log.error("AI worker crashed {} times within {}s — giving up, should fall back to Java backend",
                crashes, CRASH_WINDOW_MS / 1000);
            return;
        }

        if (lastModelParams != null) {
            log.info("Auto-restarting AI worker (attempt {}/{})", crashes, MAX_CONSECUTIVE_CRASHES);
            try {
                spawn();
                loadModel(lastModelParams);
                log.info("AI worker restarted successfully");
            } catch (Exception e) {
                log.error("AI worker restart failed: {}", e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private synchronized boolean sendCommand(Map<String, Object> cmd) {
        try {
            writer.write(JsonHelper.toJson(cmd));
            writer.newLine();
            writer.flush();
            return true;
        } catch (IOException e) {
            log.error("Failed to send command to AI worker: {}", e.getMessage());
            return false;
        }
    }

    private String cleanClasspath(String cp, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String entry : cp.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.contains("backup")) continue;
            if (!sb.isEmpty()) sb.append(separator);
            sb.append(entry);
        }
        return sb.toString();
    }


    private static class PendingGenerate {
        final GenerateCallback callback;
        PendingGenerate(GenerateCallback callback) { this.callback = callback; }
    }
}
