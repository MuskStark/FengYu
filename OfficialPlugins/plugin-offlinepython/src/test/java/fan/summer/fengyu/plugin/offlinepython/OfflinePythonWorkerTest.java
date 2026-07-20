package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the worker speaks JSON-RPC 2.0 through the official SDK loop and registers every
 * method declared in {@code manifest.json}. Mirrors the excel plugin's contract test.
 */
class OfflinePythonWorkerTest {

    @Test
    void workerAnswersDoctorOverJsonRpc() throws Exception {
        OfflinePythonSessionStore sessions = new OfflinePythonSessionStore();
        OfflinePythonRpcHandlers handlers = new OfflinePythonRpcHandlers(sessions, new Jobs());
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"doctor\",\"params\":{}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JsonRpcWorker().on("doctor", handlers.safe(handlers::doctor))
            .run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), out);
        String response = out.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"jsonrpc\":\"2.0\""), response);
        assertTrue(response.contains("\"id\":\"1\""), response);
        assertTrue(response.contains("checks"), response);
    }

    @Test
    void workerRegistersAllDeclaredMethods() {
        // Reuses the same wiring as OfflinePythonWorkerMain without launching the stdio loop.
        String source = workerMainSource();
        for (String method : new String[]{
            "init", "config.get", "config.save", "requirements.get", "requirements.save",
            "python.detect", "deps.latest", "deps.search", "verify", "package", "doctor",
            "build.start", "build.status", "build.cancel",
            "deploy.start", "deploy.status", "deploy.cancel",
            "offlinepython_doctor", "offlinepython_search_deps", "offlinepython_init_project",
            "offlinepython_verify", "offlinepython_build_start", "offlinepython_build_status"}) {
            assertTrue(source.contains("\"" + method + "\""), "worker must register method: " + method);
        }
    }

    @Test
    void workerUsesOnlyOfficialSdkProtocolTypes() {
        String source = workerMainSource();
        assertFalse(source.matches("(?s).*\\b(?:class|record|interface)\\s+(?:JsonRpcWorker|PluginHandler)\\b.*"),
            "offlinepython must not shadow official SDK types");
        assertTrue(source.contains("import fan.summer.fengyu.sdk.JsonRpcWorker;"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelRpcInvokesTheRunningJobCancellationHook() throws Exception {
        Jobs jobs = new Jobs();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Jobs.Job job = jobs.start("BUILD", handle -> {
            handle.onCancel(cancelled::countDown);
            running.countDown();
            while (!handle.isCancelled()) Thread.onSpinWait();
            throw new Jobs.CancellationException();
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        OfflinePythonRpcHandlers handlers = new OfflinePythonRpcHandlers(new OfflinePythonSessionStore(), jobs);

        Map<String, Object> result = (Map<String, Object>) handlers.buildCancel(Map.of("jobId", job.id));

        assertEquals(true, result.get("success"));
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
    }

    private static String workerMainSource() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/fan/summer/fengyu/plugin/offlinepython/OfflinePythonWorkerMain.java"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to read OfflinePythonWorkerMain.java", e);
        }
    }
}
