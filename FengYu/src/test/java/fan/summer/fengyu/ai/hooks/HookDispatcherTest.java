package fan.summer.fengyu.ai.hooks;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import fan.summer.fengyu.ai.hooks.HookDispatcher.HookDefinition;
import fan.summer.fengyu.ai.hooks.HookDispatcher.HookEvent;
import fan.summer.fengyu.ai.hooks.HookDispatcher.PreToolDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the command-hook contract against real shell processes — exit 0 allows,
 * exit 2 denies with stderr as the reason, gate JSON wins (except exit 2 over a JSON
 * allow), and every other outcome (unknown exit, timeout, spawn failure) fails open.
 */
class HookDispatcherTest {

    @TempDir
    Path tmp;

    private HookDispatcher dispatcher(HookDefinition... definitions) {
        HookDispatcher dispatcher = new HookDispatcher();
        dispatcher.update(List.of(definitions));
        return dispatcher;
    }

    private String script(String body) throws Exception {
        Path file = tmp.resolve("hook-" + System.nanoTime() + ".sh");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        file.toFile().setExecutable(true);
        return file.toString();
    }

    @Test
    void exitZeroAllowsAndExitTwoDeniesWithStderrReason() throws Exception {
        HookDispatcher allow = dispatcher(HookDefinition.command("ok", HookEvent.PRE_TOOL_USE,
                null, script("exit 0"), 5));
        assertTrue(allow.preToolUse("web_fetch", Map.of(), null).allowed());

        HookDispatcher deny = dispatcher(HookDefinition.command("no", HookEvent.PRE_TOOL_USE,
                null, script("echo 'blocked by policy' >&2; exit 2"), 5));
        PreToolDecision decision = deny.preToolUse("web_fetch", Map.of(), null);
        assertFalse(decision.allowed());
        assertEquals("blocked by policy", decision.denyReason());
    }

    @Test
    void gateJsonDenyWinsOnAnyExitCodeAndJsonAllowOnExitTwoIsIgnored() throws Exception {
        HookDispatcher jsonDeny = dispatcher(HookDefinition.command("jd", HookEvent.PRE_TOOL_USE,
                null, script("echo '{\"decision\":\"deny\",\"reason\":\"nope\"}'; exit 0"), 5));
        PreToolDecision denied = jsonDeny.preToolUse("excel_execute", Map.of(), null);
        assertFalse(denied.allowed());
        assertEquals("nope", denied.denyReason());

        // Exit 2 wins over a JSON allow — stdout is not processed on exit 2.
        HookDispatcher jsonAllow = dispatcher(HookDefinition.command("ja", HookEvent.PRE_TOOL_USE,
                null, script("echo '{\"decision\":\"allow\"}'; exit 2"), 5));
        assertFalse(jsonAllow.preToolUse("excel_execute", Map.of(), null).allowed());
    }

    @Test
    void unknownExitCodeAndTimeoutFailOpen() throws Exception {
        HookDispatcher crashed = dispatcher(HookDefinition.command("crash", HookEvent.PRE_TOOL_USE,
                null, script("exit 7"), 5));
        assertTrue(crashed.preToolUse("web_fetch", Map.of(), null).allowed());

        HookDispatcher slow = dispatcher(HookDefinition.command("slow", HookEvent.PRE_TOOL_USE,
                null, script("sleep 3"), 1));
        assertTrue(slow.preToolUse("web_fetch", Map.of(), null).allowed());
    }

    @Test
    void matcherFiltersByToolNameAndFirstDenyStopsTheChain() throws Exception {
        HookDispatcher dispatcher = dispatcher(
                HookDefinition.command("first", HookEvent.PRE_TOOL_USE, "excel_.*",
                        script("echo '{\"decision\":\"deny\",\"reason\":\"excel denied\"}'"), 5),
                HookDefinition.command("second", HookEvent.PRE_TOOL_USE, null,
                        script("echo second-executed >&2; exit 0"), 5));
        // Non-matching tool: neither hook runs for the first (matcher miss), second allows.
        assertTrue(dispatcher.preToolUse("web_fetch", Map.of(), null).allowed());
        // Matching tool: the first hook's deny wins and the chain stops there.
        PreToolDecision decision = dispatcher.preToolUse("excel_execute", Map.of(), null);
        assertFalse(decision.allowed());
        assertEquals("excel denied", decision.denyReason());
        assertEquals(List.of("first"), decision.executedHooks());
    }

    @Test
    void httpHookDeniesViaJsonBodyAndFailsOpenOnError() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            hits.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String envelope = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            boolean wantsDeny = envelope.contains("excel_execute");
            byte[] response = wantsDeny
                    ? "{\"decision\":\"deny\",\"reason\":\"http says no\"}".getBytes()
                    : "{\"decision\":\"allow\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HookDispatcher dispatcher = dispatcher(HookDefinition.http("audit", HookEvent.PRE_TOOL_USE,
                    null, "http://127.0.0.1:" + port + "/hook", 5));
            assertFalse(dispatcher.preToolUse("excel_execute", Map.of(), null).allowed());
            assertTrue(dispatcher.preToolUse("web_fetch", Map.of(), null).allowed());
            assertEquals(2, hits.get());

            HookDispatcher unreachable = dispatcher(HookDefinition.http("dead", HookEvent.PRE_TOOL_USE,
                    null, "http://127.0.0.1:1/hook", 1));
            assertTrue(unreachable.preToolUse("web_fetch", Map.of(), null).allowed());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void observeEventsFireWithoutAffectingFlow() throws Exception {
        Path marker = tmp.resolve("marker.txt");
        HookDispatcher dispatcher = dispatcher(HookDefinition.command("audit", HookEvent.POST_TOOL_USE,
                null, script("cat > " + marker), 5));
        dispatcher.observe(HookEvent.POST_TOOL_USE, "web_fetch",
                Map.of("url", "https://example.com"), "run-1", "{\"success\":true}");
        String captured = Files.readString(marker);
        assertTrue(captured.contains("\"hookEventName\":\"post_tool_use\""));
        assertTrue(captured.contains("\"toolName\":\"web_fetch\""));
        assertTrue(captured.contains("run-1"));
    }
    /** M-1: hook commands do not inherit this JVM's credentials; explicit hook env wins. */
    @Test
    void commandHookChildrenDoNotInheritHostCredentials() {
        java.util.Map<String, String> childEnv = new java.util.HashMap<>(java.util.Map.of(
                "PATH", "/bin",
                "FENGYU_AUTH_TOKEN", "zf-primary-secret",
                "FENGYU_BROWSER_BRIDGE_TOKEN", "bridge-secret",
                "ANTHROPIC_API_KEY", "sk-inherited"));
        // Simulate what runCommand does after the strip: hook env keys ride along.
        childEnv.put("PLUGIN_SERVICE_TOKEN", "explicit");
        java.util.Map<String, String> configured = java.util.Map.of(
                "PLUGIN_SERVICE_TOKEN", "explicit");

        HookDispatcher.stripInheritedSecretsExceptConfigured(childEnv, configured);

        assertFalse(childEnv.containsKey("FENGYU_AUTH_TOKEN"),
                "the primary API token must never reach a plugin hook command");
        assertFalse(childEnv.containsKey("FENGYU_BROWSER_BRIDGE_TOKEN"));
        assertFalse(childEnv.containsKey("ANTHROPIC_API_KEY"));
        assertEquals("explicit", childEnv.get("PLUGIN_SERVICE_TOKEN"),
                "keys the hook itself sets are not stripped");
        assertEquals("/bin", childEnv.get("PATH"),
                "ordinary environment survives the scrub");
        // FENGYU_HOOK_EVENT / FENGYU_RUN_ID are set AFTER the strip and never match it.
    }

}
