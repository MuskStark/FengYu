package fan.summer.ai.nativejni;

import fan.summer.ai.inference.StopDetector;
import fan.summer.ai.util.JsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Child JVM process for isolated native llama.cpp inference.
 *
 * <p>Reads JSON commands from stdin, delegates to {@link LlamaContext},
 * and writes JSON responses to stdout — one JSON object per line.
 * Exits when stdin closes (parent process died).
 *
 * <p>Commands: {@code load}, {@code generate}, {@code unload}, {@code ping}
 * <br>Responses: {@code loaded}, {@code token}, {@code done}, {@code error}, {@code unloaded}, {@code pong}
 */
public class NativeWorkerMain {

    private static final Logger log = LoggerFactory.getLogger(NativeWorkerMain.class);

    public static void main(String[] args) {
        log.info("AI worker process starting (pid={})", ProcessHandle.current().pid());

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out));

        AtomicReference<LlamaContext> contextRef = new AtomicReference<>();

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> cmd = JsonHelper.parseObject(line);
                    if (cmd.isEmpty()) continue;
                    String cmdType = (String) cmd.get("cmd");
                    if (cmdType == null) continue;

                    switch (cmdType) {
                        case "load" -> handleLoad(cmd, contextRef, writer);
                        case "generate" -> handleGenerate(cmd, contextRef, writer);
                        case "unload" -> handleUnload(contextRef, writer);
                        case "ping" -> writeResponse(writer, Map.of("type", "pong"));
                        default -> writeResponse(writer, Map.of(
                            "type", "error",
                            "message", "Unknown command: " + cmdType
                        ));
                    }
                } catch (Exception e) {
                    log.error("Error processing command: {}", e.getMessage());
                    writeResponse(writer, Map.of(
                        "type", "error",
                        "message", "Command processing error: " + e.getMessage()
                    ));
                }
            }
        } catch (IOException e) {
            log.info("AI worker stdin closed, shutting down");
        } finally {
            LlamaContext ctx = contextRef.getAndSet(null);
            if (ctx != null) ctx.close();
            log.info("AI worker process exiting");
        }
    }

    private static void handleLoad(Map<String, Object> cmd,
                                   AtomicReference<LlamaContext> ref,
                                   BufferedWriter writer) {
        String modelPath = (String) cmd.get("modelPath");
        try {
            LlamaContext old = ref.getAndSet(null);
            if (old != null) old.close();

            ModelParams params = new ModelParams()
                .modelPath(modelPath)
                .ctxLength(intVal(cmd, "ctxLength", 4096))
                .threads(intVal(cmd, "threads", Runtime.getRuntime().availableProcessors()))
                .gpuLayers(intVal(cmd, "gpuLayers", 0))
                .flashAttention(Boolean.TRUE.equals(cmd.get("flashAttention")));

            LlamaContext ctx = new LlamaContext(params);
            ref.set(ctx);
            log.info("Model loaded: {}", modelPath);
            writeResponse(writer, Map.of("type", "loaded", "modelPath", modelPath));
        } catch (Exception e) {
            log.error("Failed to load model: {}", e.getMessage());
            writeResponse(writer, Map.of(
                "type", "error",
                "message", "Load failed: " + e.getMessage()
            ));
        }
    }

    private static void handleGenerate(Map<String, Object> cmd,
                                       AtomicReference<LlamaContext> ref,
                                       BufferedWriter writer) {
        String id = (String) cmd.get("id");
        String prompt = (String) cmd.get("prompt");
        LlamaContext ctx = ref.get();

        if (ctx == null) {
            writeResponse(writer, Map.of(
                "type", "error", "id", id, "message", "No model loaded"
            ));
            return;
        }

        GenerateParams params = new GenerateParams()
            .maxTokens(intVal(cmd, "maxTokens", 512))
            .temperature(floatVal(cmd, "temperature", 0.7f))
            .topP(floatVal(cmd, "topP", 0.9f))
            .repeatPenalty(floatVal(cmd, "repeatPenalty", 1.1f))
            .seed(longVal(cmd, "seed", -1));

        StringBuilder response = new StringBuilder();
        AtomicInteger tokenCount = new AtomicInteger(0);
        long[] firstTokenNanos = {0L};
        long genStartNanos = System.nanoTime();
        AtomicBoolean stopped = new AtomicBoolean(false);

        try {
            ctx.generate(prompt, params, new GenerateCallback() {
                @Override
                public boolean onToken(String tokenText) {
                    if (stopped.get()) return false;

                    if (firstTokenNanos[0] == 0L) firstTokenNanos[0] = System.nanoTime();
                    tokenCount.incrementAndGet();

                    int prevLen = response.length();
                    response.append(tokenText);
                    int stopIdx = StopDetector.findStop(response);
                    if (stopIdx >= 0) {
                        response.setLength(stopIdx);
                        int safeLen = stopIdx - prevLen;
                        if (safeLen > 0) {
                            String safe = tokenText.substring(0, Math.min(tokenText.length(), safeLen));
                            writeResponse(writer, Map.of("type", "token", "id", id, "text", safe));
                        }
                        stopped.set(true);
                        return false;
                    }
                    writeResponse(writer, Map.of("type", "token", "id", id, "text", tokenText));
                    return true;
                }

                @Override
                public void onDone(String fullText) {
                    String output = response.toString();
                    int n = tokenCount.get();
                    long base = firstTokenNanos[0] != 0L ? firstTokenNanos[0] : genStartNanos;
                    long elapsedMs = (System.nanoTime() - base) / 1_000_000;
                    double tokPerSec = (n > 0 && elapsedMs > 0) ? n * 1000.0 / elapsedMs : 0;
                    writeResponse(writer, Map.of(
                        "type", "done",
                        "id", id,
                        "fullText", output,
                        "tokenCount", n,
                        "tokPerSec", tokPerSec
                    ));
                }

                @Override
                public void onError(String message) {
                    writeResponse(writer, Map.of("type", "error", "id", id, "message", message));
                }
            });
        } catch (Exception e) {
            log.error("Generation error: {}", e.getMessage());
            writeResponse(writer, Map.of("type", "error", "id", id, "message", e.getMessage()));
        }
    }

    private static void handleUnload(AtomicReference<LlamaContext> ref,
                                     BufferedWriter writer) {
        LlamaContext ctx = ref.getAndSet(null);
        if (ctx != null) ctx.close();
        writeResponse(writer, Map.of("type", "unloaded"));
    }

    private static void writeResponse(BufferedWriter writer, Map<String, Object> response) {
        try {
            writer.write(JsonHelper.toJson(response));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // parent process closed — will exit on next stdin read
        }
    }

    private static int intVal(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static float floatVal(Map<String, Object> map, String key, float def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.floatValue() : def;
    }

    private static long longVal(Map<String, Object> map, String key, long def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }
}
