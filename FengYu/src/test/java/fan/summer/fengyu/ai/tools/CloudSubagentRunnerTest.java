package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.provider.ProviderDefinition;
import fan.summer.fengyu.ai.provider.ProviderRegistryService;
import fan.summer.fengyu.ai.provider.Protocol;
import fan.summer.fengyu.ai.service.SpringAiCloudBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CloudSubagentRunner} — the sub-loop wiring the P1 review pinned: the nested
 * backend must run behind the SAME approval gate as the outer turn (previously it only
 * got {@code setToolCallbacks}, so the nested {@code ToolLoopDriver} saw no gate and
 * executed every requested tool — unverifiable {@code workspace_exec} commands included
 * — with zero human approval; the gate-gated loop itself is proven in
 * {@code ChatClientToolLoopTest.sensitiveToolWaitsForChatApprovalBeforeInvocation}).
 * Plus the concurrency contract: a cancelled/interrupted/hung run stops exactly its own
 * backend, and {@code cancel()} stops every in-flight one. Provider resolution is
 * registry-first (the same dispatch the main chat backend resolves with), with the
 * legacy flat-key switch as the fallback.
 */
class CloudSubagentRunnerTest {

    private final DelegateTaskTool.SubagentRunner.Spec spec =
            new DelegateTaskTool.SubagentRunner.Spec("system", "user", List.of(), null);

    @AfterEach
    void resetSeams() {
        ToolApprovalContext.clear();
        CloudSubagentRunner.hardRunTimeoutSeconds = CloudSubagentRunner.HARD_RUN_TIMEOUT_SECONDS;
    }

    /** A mocked cloud backend whose chat() fires the scripted sink events and returns. */
    private static SpringAiCloudBackend backendSpeaking(SinkScript script) {
        SpringAiCloudBackend nested = mock(SpringAiCloudBackend.class);
        when(nested.isReady()).thenReturn(true);
        try {
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                AiStreamCallback sink = (AiStreamCallback) invocation.getArgument(5);
                script.run(sink);
                return null;
            }).when(nested).chat(anyList(), anyFloat(), anyFloat(), anyInt(), anyList(), any());
        } catch (Exception stubbing) {
            throw new IllegalStateException(stubbing);
        }
        return nested;
    }

    @FunctionalInterface
    private interface SinkScript {
        void run(AiStreamCallback sink) throws Exception;
    }

    /** The recorded events of a fake OUTER stream (what the user's SSE would receive). */
    private static final class RecordingStream implements AiStreamCallback {
        final List<AiToolCall> approvalCards = new CopyOnWriteArrayList<>();

        @Override public void onToken(String fragment) {}
        @Override public void onToolApprovalRequired(String approvalId, AiToolCall toolCall,
                Instant expiresAt) {
            approvalCards.add(toolCall);
        }
    }

    @Test
    void nestedBackendRunsBehindTheOuterApprovalGateAndForwardsCards() throws Exception {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        RecordingStream outerStream = new RecordingStream();
        ToolApprovalContext.set(gate, outerStream);
        SpringAiCloudBackend nested = backendSpeaking(sink -> {
            // The nested loop asks for approval (an unverifiable command card) before it
            // would execute anything, then finishes — exactly the order ToolLoopDriver
            // produces with a gate installed.
            sink.onToolApprovalRequired("approval-1",
                    AiToolCall.of("approval-1", "workspace_exec",
                            java.util.Map.of("command", "echo $(date)")),
                    Instant.now().plusSeconds(60));
            sink.onComplete("the report", 12, 1.0);
        });

        DelegateTaskTool.SubagentRunner.Result result;
        try (var config = org.mockito.Mockito.mockStatic(AiConfigService.class)) {
            config.when(AiConfigService::getAiTemperature).thenReturn(0.7f);
            config.when(AiConfigService::getAiTopP).thenReturn(0.9f);
            config.when(AiConfigService::getAiMaxTokens).thenReturn(4096);
            result = new CloudSubagentRunner().run(nested, spec);
        }

        assertEquals("the report", result.report());
        // THE fix: the nested backend carries the outer turn's gate (the same instance
        // the bridge holds), not the gate-less default the loop treats as "just execute".
        verify(nested).setToolApprovalGate(same(gate));
        // The card crossed the sub-loop boundary to the user's stream.
        assertEquals(1, outerStream.approvalCards.size());
        assertEquals("workspace_exec", outerStream.approvalCards.getFirst().name());
        assertTrue(outerStream.approvalCards.getFirst().arguments().toString()
                .contains("echo $(date)"), "the unverifiable command reached the card");
    }

    @Test
    void unwiredContextFallsBackToTheHostWiredGate() throws Exception {
        // No inherited bridge (e.g. a direct invocation outside a tool batch): the
        // Spring-wired singleton still gates the nested loop; it only stays ungated when
        // the outer turn itself is ungated (both null).
        ChatToolApprovalGate hostGate = new ChatToolApprovalGate();
        SpringAiCloudBackend nested = backendSpeaking(sink -> sink.onComplete("r", 1, 1.0));
        ToolApprovalContext.clear();

        try (var config = org.mockito.Mockito.mockStatic(AiConfigService.class)) {
            config.when(AiConfigService::getAiTemperature).thenReturn(0.7f);
            config.when(AiConfigService::getAiTopP).thenReturn(0.9f);
            config.when(AiConfigService::getAiMaxTokens).thenReturn(4096);
            new CloudSubagentRunner(hostGate).run(nested, spec);
        }
        verify(nested).setToolApprovalGate(same(hostGate));
    }

    @Test
    void interruptedRunCancelsOnlyItsOwnBackend() throws Exception {
        // The concurrency bug: a second run could swap the cached backend field, and a
        // cancel aimed at the first run cancelled the field (the OTHER run's backend)
        // while the first kept generating. The local-instance pattern pins: an
        // interrupted run cancels ITS backend and nobody else's.
        CloudSubagentRunner runner = new CloudSubagentRunner();
        CountDownLatch aStarted = new CountDownLatch(1);
        // A's chat returns without a terminal callback: the run parks in the await —
        // where the interrupt below lands.
        SpringAiCloudBackend a = backendSpeaking(sink -> aStarted.countDown());
        SpringAiCloudBackend b = backendSpeaking(sink -> sink.onComplete("b's report", 2, 1.0));

        AtomicReference<Throwable> aFailure = new AtomicReference<>();
        Thread threadA = Thread.ofVirtual().start(() -> {
            try (var config = org.mockito.Mockito.mockStatic(AiConfigService.class)) {
                config.when(AiConfigService::getAiTemperature).thenReturn(0.7f);
                config.when(AiConfigService::getAiTopP).thenReturn(0.9f);
                config.when(AiConfigService::getAiMaxTokens).thenReturn(4096);
                runner.run(a, spec);
            } catch (Throwable t) {
                aFailure.set(t);
            }
        });
        assertTrue(aStarted.await(5, TimeUnit.SECONDS));

        DelegateTaskTool.SubagentRunner.Result bResult;
        try (var config = org.mockito.Mockito.mockStatic(AiConfigService.class)) {
            config.when(AiConfigService::getAiTemperature).thenReturn(0.7f);
            config.when(AiConfigService::getAiTopP).thenReturn(0.9f);
            config.when(AiConfigService::getAiMaxTokens).thenReturn(4096);
            bResult = runner.run(b, spec);
        }

        threadA.interrupt();
        threadA.join(10_000);

        assertTrue(aFailure.get() instanceof InterruptedException,
                "the interrupted run fails as an interruption: " + aFailure.get());
        verify(a).cancelGeneration();
        verify(b, never()).cancelGeneration();
        assertEquals("b's report", bResult.report(), "the sibling run is unaffected");
    }

    @Test
    void aStreamThatNeverTerminatesHitsTheHardBudgetAndIsCancelled() throws Exception {
        CloudSubagentRunner.hardRunTimeoutSeconds = 1;   // test seam, restored in @AfterEach
        SpringAiCloudBackend nested = backendSpeaking(sink -> { /* never completes */ });

        IllegalStateException failure;
        try (var config = org.mockito.Mockito.mockStatic(AiConfigService.class)) {
            config.when(AiConfigService::getAiTemperature).thenReturn(0.7f);
            config.when(AiConfigService::getAiTopP).thenReturn(0.9f);
            config.when(AiConfigService::getAiMaxTokens).thenReturn(4096);
            failure = assertThrows(IllegalStateException.class,
                    () -> new CloudSubagentRunner().run(nested, spec));
        }
        assertTrue(failure.getMessage().contains("hard budget"), failure.getMessage());
        verify(nested, atLeastOnce()).cancelGeneration();
    }

    @Test
    void cancelStopsEveryInFlightRunNotJustTheLastSwappedInstance() throws Exception {
        CloudSubagentRunner runner = new CloudSubagentRunner();
        CloudSubagentRunner.hardRunTimeoutSeconds = 60;  // long enough that cancel() decides
        CountDownLatch bothAwaiting = new CountDownLatch(2);
        SpringAiCloudBackend first = backendSpeaking(sink -> bothAwaiting.countDown());
        SpringAiCloudBackend second = backendSpeaking(sink -> bothAwaiting.countDown());

        List<Thread> runs = new java.util.ArrayList<>();
        for (SpringAiCloudBackend nested : List.of(first, second)) {
            runs.add(Thread.ofVirtual().start(() -> {
                try (var config = org.mockito.Mockito.mockStatic(AiConfigService.class)) {
                    config.when(AiConfigService::getAiTemperature).thenReturn(0.7f);
                    config.when(AiConfigService::getAiTopP).thenReturn(0.9f);
                    config.when(AiConfigService::getAiMaxTokens).thenReturn(4096);
                    runner.run(nested, spec);
                } catch (Exception expectedTimeoutOrInterrupt) {
                    // both runs end here after cancel(): an unblockable await reports failure
                }
            }));
        }
        assertTrue(bothAwaiting.await(5, TimeUnit.SECONDS), "both runs must be in flight");

        runner.cancel();   // e.g. the outer turn was stopped

        verify(first, atLeastOnce()).cancelGeneration();
        verify(second, atLeastOnce()).cancelGeneration();
        for (Thread run : runs) {   // don't leave the parked awaiters behind
            run.interrupt();
            run.join(10_000);
        }
    }

    // ── registry-first provider resolution (the 4.1.0 multi-provider fix) ──────

    /** The sampling statics the offline ChatModel build reads while a config mock is up. */
    private static void stubSamplingStatics(org.mockito.MockedStatic<AiConfigService> config) {
        config.when(AiConfigService::getAiTemperature).thenReturn(0.7f);
        config.when(AiConfigService::getAiTopP).thenReturn(0.9f);
        config.when(() -> AiConfigService.effectiveMaxOutputTokens(anyString())).thenReturn(4096);
    }

    @Test
    void registryActiveProviderWinsOverTheLegacyFlatKeys() {
        // The 4.1.0 headline case: the active provider is a USER-CREATED registry
        // instance the legacy flat keys cannot even name. Legacy mode points at a
        // different provider on purpose — if resolution wrongly took the flat path the
        // built backend would be the anthropic one.
        ProviderRegistryService registry = mock(ProviderRegistryService.class);
        ProviderDefinition custom = new ProviderDefinition("my-zhipu", "Zhipu",
                Protocol.OPENAI_CHAT, "https://open.bigmodel.cn/api/paas/v4", "glm-4.7",
                "ENC(cipher)", Map.of(), false, 4);
        when(registry.activeProvider()).thenReturn(Optional.of(custom));
        when(registry.resolveApiKey("my-zhipu")).thenReturn("zp-key");

        CloudSubagentRunner.ResolvedProvider resolved;
        SpringAiCloudBackend backend;
        try (var config = org.mockito.Mockito.mockStatic(AiConfigService.class)) {
            config.when(AiConfigService::getAiMode).thenReturn("anthropic");
            config.when(AiConfigService::getAiAnthropicEndpoint).thenReturn("https://flat-anthropic.example");
            config.when(AiConfigService::getAiAnthropicApiKey).thenReturn("sk-flat-ant");
            config.when(AiConfigService::getAiAnthropicModel).thenReturn("flat-claude");
            stubSamplingStatics(config);
            resolved = CloudSubagentRunner.resolveProvider(registry);
            backend = resolved.build().get(); // inside the scope: stubbed sampling statics
        }

        assertNotNull(resolved, "the registry instance resolves even though legacy keys point elsewhere");
        assertTrue(resolved.fingerprint().startsWith("my-zhipu|https://open.bigmodel.cn/api/paas/v4|glm-4.7|"),
                resolved.fingerprint());
        assertTrue(backend.isReady());
        assertEquals("https://open.bigmodel.cn/api/paas/v4", backend.getEndpoint());
        assertEquals("glm-4.7", backend.getModelNameInternal());
        assertEquals("Zhipu", backend.providerLabel(), "registry display name labels the backend");
        assertEquals(SpringAiCloudBackend.Provider.OPENAI, backend.provider());
        // The key came through the registry's own resolution path, never re-derived here.
        verify(registry).resolveApiKey("my-zhipu");
    }

    @Test
    void legacyFlatKeysServeWhenNoRegistryProviderIsActive() {
        // No registry wired at all (focused tests), and a registry whose active id
        // resolves to nothing — both fall back to the legacy flat-key switch, exactly
        // like BackendReactivator.reactivate().
        ProviderRegistryService registry = mock(ProviderRegistryService.class);
        when(registry.activeProvider()).thenReturn(Optional.empty());

        try (var config = org.mockito.Mockito.mockStatic(AiConfigService.class)) {
            config.when(AiConfigService::getAiMode).thenReturn("openai");
            config.when(AiConfigService::getAiOpenAiEndpoint).thenReturn("https://flat-openai.example/v1");
            config.when(AiConfigService::getAiOpenAiApiKey).thenReturn("sk-flat");
            config.when(AiConfigService::getAiOpenAiModel).thenReturn("flat-gpt");
            stubSamplingStatics(config);

            CloudSubagentRunner.ResolvedProvider withoutRegistry =
                    CloudSubagentRunner.resolveProvider(null);
            CloudSubagentRunner.ResolvedProvider withEmptyRegistry =
                    CloudSubagentRunner.resolveProvider(registry);

            assertNotNull(withoutRegistry);
            String expected = "openai|https://flat-openai.example/v1|flat-gpt|" + "sk-flat".hashCode();
            assertEquals(expected, withoutRegistry.fingerprint());
            assertEquals(expected, withEmptyRegistry.fingerprint(),
                    "an unresolvable active id falls back to the legacy switch");
            SpringAiCloudBackend backend = withoutRegistry.build().get();
            assertTrue(backend.isReady());
            assertEquals("https://flat-openai.example/v1", backend.getEndpoint());
            assertEquals("flat-gpt", backend.getModelNameInternal());
        }
    }

    @Test
    void localModeAndMissingKeyKeepTheNullEnvelope() {
        ProviderRegistryService registry = mock(ProviderRegistryService.class);
        // Active provider is local (Ollama) — the subagent has no local mode.
        ProviderDefinition ollama = new ProviderDefinition("ollama", "Ollama (local)",
                Protocol.OLLAMA, "http://localhost:11434", "llama3", "", Map.of(), true, 3);
        when(registry.activeProvider()).thenReturn(Optional.of(ollama));
        assertNull(CloudSubagentRunner.resolveProvider(registry));
        // run(Spec) keeps its honest error envelope for it.
        IllegalStateException local = assertThrows(IllegalStateException.class,
                () -> new CloudSubagentRunner(null, registry).run(spec));
        assertTrue(local.getMessage().contains("needs a cloud provider"), local.getMessage());

        // A cloud definition whose key never resolved (unset / damaged cipher → "").
        ProviderDefinition keyless = new ProviderDefinition("openai", "OpenAI",
                Protocol.OPENAI_CHAT, "https://api.openai.com", "gpt-4o",
                "", Map.of(), true, 0);
        when(registry.activeProvider()).thenReturn(Optional.of(keyless));
        when(registry.resolveApiKey("openai")).thenReturn("");
        assertNull(CloudSubagentRunner.resolveProvider(registry),
                "a missing API key keeps the null envelope, not a half-built backend");
    }
}
