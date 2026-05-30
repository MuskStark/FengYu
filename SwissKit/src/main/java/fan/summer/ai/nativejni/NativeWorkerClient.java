package fan.summer.ai.nativejni;

import fan.summer.ai.util.JsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private Process childProcess;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean running;

    private final AtomicInteger genIdCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, PendingGenerate> pendingGenerates = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> loadFuture;

    private int consecutiveCrashes;
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
        String separator = System.getProperty("path.separator");

        String cleanCp = cleanClasspath(classpath, separator);

        ProcessBuilder pb = new ProcessBuilder(
            javaHome + "/bin/java", "-cp", cleanCp,
            "-Dfile.encoding=UTF-8",
            "fan.summer.ai.nativejni.NativeWorkerMain"
        );
        pb.redirectErrorStream(false);
        childProcess = pb.start();

        writer = new BufferedWriter(new OutputStreamWriter(childProcess.getOutputStream()));
        running = true;

        readerThread = new Thread(this::readLoop, "ai-worker-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        log.info("AI worker process spawned, pid={}", childProcess.pid());
    }

    /**
     * Sends a load command and waits for the child to confirm.
     *
     * @param params model parameters
     * @throws Exception if loading fails or times out (120 s)
     */
    public void loadModel(ModelParams params) throws Exception {
        lastModelParams = params;

        if (!isAlive()) spawn();

        loadFuture = new CompletableFuture<>();
        sendCommand(Map.of(
            "cmd", "load",
            "modelPath", params.getModelPath(),
            "ctxLength", params.getCtxLength(),
            "threads", params.getThreads(),
            "gpuLayers", params.getGpuLayers(),
            "flashAttention", params.isFlashAttention()
        ));

        loadFuture.get(120, TimeUnit.SECONDS);
    }

    /**
     * Sends a generate command. Responses are dispatched asynchronously
     * to the provided callback on the reader thread.
     */
    public void generate(String prompt, GenerateParams params, GenerateCallback callback) {
        String id = "gen-" + genIdCounter.incrementAndGet();
        pendingGenerates.put(id, new PendingGenerate(callback));
        sendCommand(Map.of(
            "cmd", "generate",
            "id", id,
            "prompt", prompt,
            "maxTokens", params.getMaxNewTokens(),
            "temperature", params.getTemperature(),
            "topP", params.getTopP(),
            "repeatPenalty", params.getRepeatPenalty(),
            "seed", params.getSeed()
        ));
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
        return consecutiveCrashes >= MAX_CONSECUTIVE_CRASHES;
    }

    @Override
    public void close() {
        running = false;
        try { unload(); } catch (Exception ignored) {}
        try { writer.close(); } catch (Exception ignored) {}
        if (childProcess != null) {
            childProcess.destroyForcibly();
        }
        if (readerThread != null) {
            readerThread.interrupt();
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
                    int tokenCount = intVal(resp, "tokenCount", 0);
                    double tokPerSec = doubleVal(resp, "tokPerSec", 0.0);
                    pg.callback.onDone(fullText, tokenCount, tokPerSec);
                }
                consecutiveCrashes = 0;
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
            case "pong" -> {}
        }
    }

    private void handleChildExit() {
        if (!running) return;

        log.warn("AI worker process exited (exit={})",
            childProcess != null ? childProcess.exitValue() : "unknown");

        for (Map.Entry<String, PendingGenerate> entry : pendingGenerates.entrySet()) {
            entry.getValue().callback.onError("AI worker process crashed");
        }
        pendingGenerates.clear();

        if (loadFuture != null && !loadFuture.isDone()) {
            loadFuture.completeExceptionally(
                new RuntimeException("AI worker process crashed during load"));
        }

        consecutiveCrashes++;
        running = false;

        if (consecutiveCrashes >= MAX_CONSECUTIVE_CRASHES) {
            log.error("AI worker crashed {} consecutive times — giving up, should fall back to Java backend",
                consecutiveCrashes);
            return;
        }

        if (lastModelParams != null) {
            log.info("Auto-restarting AI worker (attempt {}/{})", consecutiveCrashes, MAX_CONSECUTIVE_CRASHES);
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

    private void sendCommand(Map<String, Object> cmd) {
        try {
            writer.write(JsonHelper.toJson(cmd));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to send command to AI worker: {}", e.getMessage());
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

    private static int intVal(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static double doubleVal(Map<String, Object> map, String key, double def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static class PendingGenerate {
        final GenerateCallback callback;
        PendingGenerate(GenerateCallback callback) { this.callback = callback; }
    }
}
