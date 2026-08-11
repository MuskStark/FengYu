package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.offlinepython.generated.BuildCancelInput;
import fan.summer.fengyu.sdk.CancellationToken;
import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the worker speaks JSON-RPC 2.0 through the official SDK loop, registers every method
 * declared in {@code manifest.json} via the typed {@code worker.method(...)} API, and that a domain
 * {@code buildCancel} fires the running job's cancellation hook (which the build wires to
 * {@code ProcessRunner::cancel} to reap the pip subprocess tree — see ProcessTreeCancelTest).
 */
class OfflinePythonWorkerTest {

    /**
     * Network-free round-trip: configGet returns the default BuildConfig without touching the
     * network or the filesystem. (doctor is avoided here because DoctorService pings PyPI.)
     */
    @Test
    void workerAnswersConfigGetOverJsonRpc() throws Exception {
        OfflinePythonSessionStore sessions = new OfflinePythonSessionStore();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"configGet\",\"params\":{}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OfflinePythonWorkerMain.worker(new OfflinePythonRpcHandlers(sessions, new Jobs()))
            .run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), out);
        String response = out.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"jsonrpc\":\"2.0\""), response);
        assertTrue(response.contains("\"id\":\"1\""), response);
        assertTrue(response.contains("\"success\":true"), response);
        assertTrue(response.contains("\"config\""), response);
    }

    /**
     * The generated PluginMethods name class is the single source of worker method names, so every
     * rpc.methods key in the manifest MUST appear there as a string literal. Also assert the worker
     * main wires exactly one typed registration per method.
     */
    @Test
    void workerRegistersAllDeclaredMethods() {
        String methodsSource = pluginMethodsSource();
        String workerSource = workerMainSource();
        for (String method : ALL_METHODS) {
            assertTrue(methodsSource.contains("\"" + method + "\""),
                    "PluginMethods.java must declare method: " + method);
        }
        // One typed `.method(` registration per declared method, no v1 `.on(` string handlers left.
        assertEquals(ALL_METHODS.length, countOccurrences(workerSource, ".method("),
                "worker must register one typed handler per method");
        assertFalse(workerSource.contains(".on(\""), "worker must not register v1 string handlers");
    }

    @Test
    void workerUsesOnlyOfficialSdkProtocolTypes() {
        String source = workerMainSource();
        assertFalse(source.matches("(?s).*\\b(?:class|record|interface)\\s+(?:JsonRpcWorker|PluginHandler)\\b.*"),
            "offlinepython must not shadow official SDK types");
        assertTrue(source.contains("import fan.summer.fengyu.sdk.JsonRpcWorker;"));
    }

    @Test
    void buildCancelInvokesTheRunningJobCancellationHook() throws Exception {
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
        RpcContext ctx = new RpcContext("test", null, null, null, new CancellationToken(), null);

        // Domain cancel of a STARTED job: terminates the job (and its subprocess tree, in production).
        var result = handlers.buildCancel(new BuildCancelInput(job.id), ctx);

        assertEquals(true, result.success(), "buildCancel must report success");
        assertEquals(job.id, result.jobId(), "buildCancel must echo the jobId");
        assertTrue(cancelled.await(2, TimeUnit.SECONDS), "the job's cancel hook must fire");
    }

    /** All 23 rpc.methods keys (camelCase — schema v2 forbids dots). */
    private static final String[] ALL_METHODS = {
        "init", "configGet", "configSave", "requirementsGet", "requirementsSave",
        "pythonDetect", "depsLatest", "depsSearch", "verify", "package", "doctor",
        "buildStart", "buildStatus", "buildCancel",
        "deployStart", "deployStatus", "deployCancel",
        "offlinepythonDoctor", "offlinepythonSearchDeps", "offlinepythonInitProject",
        "offlinepythonVerify", "offlinepythonBuildStart", "offlinepythonBuildStatus",
    };

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int idx = haystack.indexOf(needle); idx >= 0; idx = haystack.indexOf(needle, idx + needle.length())) {
            count++;
        }
        return count;
    }

    private static String workerMainSource() {
        return read("src/main/java/fan/summer/fengyu/plugin/offlinepython/OfflinePythonWorkerMain.java");
    }

    private static String pluginMethodsSource() {
        return read("src/main/java/fan/summer/offlinepython/generated/PluginMethods.java");
    }

    private static String read(String relative) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(relative));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to read " + relative, e);
        }
    }
}
