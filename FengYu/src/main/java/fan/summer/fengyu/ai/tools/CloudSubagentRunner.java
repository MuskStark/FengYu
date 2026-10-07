package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.service.SpringAiCloudBackend;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The production sub-loop behind FengYu's subagent tools ({@code delegate_task},
 * {@code review}): runs the nested model conversation on a FRESH cloud backend — the
 * ACTIVE backend is mid-generation and its single-slot {@code generating} guard would
 * reject a nested call — cached per provider-config fingerprint so config changes rebuild
 * it on the next dispatch. {@link #cancel()} stops every in-flight generation.
 *
 * <p><b>Never an ungated nested loop (P1):</b> the nested backend gets the SAME approval
 * gate the outer turn runs behind — the one reachable through the inherited
 * {@link ToolApprovalContext} bridge (the exact instance the outer {@code ToolLoopDriver}
 * installed around the tool batch that issued the delegation), falling back to the
 * host-wired singleton. Without it the nested {@code ToolLoopDriver} saw no gate and
 * executed every requested tool — including unverifiable {@code workspace_exec} commands
 * — with zero human approval. The permission MODE and conversation identity ride along
 * implicitly ({@code AiPermissionContext}/{@code ConversationContext} are inheritable
 * thread-locals captured at the delegate's spawn), so the nested approvals follow the
 * outer permission semantics; the nested sink stays silent EXCEPT that approval cards
 * are forwarded to the outer stream, where the user can actually answer them.</p>
 *
 * <p>The sink is otherwise silent: subagent steps never reach the outer transcript; only
 * the final report (or failure) crosses back. Shared by every subagent tool so the
 * caching, provider switch, and cancel semantics stay identical wherever a nested loop
 * runs.</p>
 */
final class CloudSubagentRunner implements DelegateTaskTool.SubagentRunner {

    /**
     * Hard wall budget for one nested run — the latch awaits below are bounded so a
     * stream that never calls onComplete/onError (a wedged provider) cannot park the
     * delegation's worker thread forever. Volatile static: the test seam.
     */
    static final long HARD_RUN_TIMEOUT_SECONDS = 900;
    static volatile long hardRunTimeoutSeconds = HARD_RUN_TIMEOUT_SECONDS;

    private final ChatToolApprovalGate sharedGate;
    private volatile SpringAiCloudBackend backend;
    private volatile String backendFingerprint;

    /**
     * Backends of the runs currently in flight. {@link #cancel()} must stop ALL of them:
     * the cached {@link #backend} field may have been swapped by a concurrent run's
     * rebuild, and cancelling only the field instance left the actually-running loop
     * generating on the provider's bill.
     */
    private final Set<SpringAiCloudBackend> activeRuns = ConcurrentHashMap.newKeySet();

    CloudSubagentRunner() {
        this(null);
    }

    /** Host wiring: the app's approval-gate singleton, used when no bridge is inherited. */
    CloudSubagentRunner(ChatToolApprovalGate sharedGate) {
        this.sharedGate = sharedGate;
    }

    @Override
    public Result run(Spec spec) throws Exception {
        SpringAiCloudBackend nested = backend();
        if (nested == null) {
            throw new IllegalStateException("the subagent needs a cloud provider "
                    + "(OpenAI/Anthropic/DeepSeek); local mode does not support it");
        }
        return run(nested, spec);
    }

    /**
     * The loop over an already-resolved backend (package-private: tests inject a fake
     * and observe the wiring without touching provider config).
     */
    Result run(SpringAiCloudBackend nested, Spec spec) throws Exception {
        nested.setToolCallbacks(spec.tools());
        // Same gate as the outer turn: prefer the inherited bridge (exact instance +
        // outer stream), fall back to the host-wired singleton. Null keeps the nested
        // loop ungated exactly when the outer one is — an unwired context stays uniform.
        ChatToolApprovalGate approvalGate = ToolApprovalContext.gate();
        AiStreamCallback outerStream = ToolApprovalContext.callback();
        if (approvalGate == null) approvalGate = sharedGate;
        nested.setToolApprovalGate(approvalGate);

        List<AiChatMessage> history = new ArrayList<>(List.of(
                AiChatMessage.system(spec.systemPrompt()),
                AiChatMessage.user(spec.userPrompt())));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> report = new AtomicReference<>();
        AtomicReference<Integer> tokens = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AiStreamCallback sink = new AiStreamCallback() {
            @Override public void onToken(String fragment) {}
            @Override public void onToolCall(fan.summer.fengyu.ai.AiToolCall toolCall) {}
            @Override public void onToolResult(String id, AiToolResult result) {}
            // The only thing that crosses the sub-loop boundary mid-flight: the approval
            // card. Forwarded to the OUTER stream so the user's SSE (still open around
            // the tool batch that issued the delegation) can render and resolve it.
            @Override public void onToolApprovalRequired(String approvalId, AiToolCall toolCall,
                    Instant expiresAt) {
                if (outerStream != null) {
                    outerStream.onToolApprovalRequired(approvalId, toolCall, expiresAt);
                }
            }
            @Override public void onComplete(String fullResponse, int completionTokens, double tps) {
                report.set(fullResponse == null ? "" : fullResponse);
                tokens.set(completionTokens);
                done.countDown();
            }
            @Override public void onError(Throwable error) {
                failure.set(error);
                done.countDown();
            }
        };
        activeRuns.add(nested);
        try {
            nested.chat(history,
                    fan.summer.fengyu.ai.AiConfigService.getAiTemperature(),
                    fan.summer.fengyu.ai.AiConfigService.getAiTopP(),
                    fan.summer.fengyu.ai.AiConfigService.getAiMaxTokens(),
                    List.of(), sink);
            if (!done.await(hardRunTimeoutSeconds, TimeUnit.SECONDS)) {
                // A stream that never terminates is treated as cancelled, not waited on
                // forever: stop the generation, fail the run honestly.
                nested.cancelGeneration();
                throw new IllegalStateException("the subagent exceeded its "
                        + hardRunTimeoutSeconds + "s hard budget and was cancelled");
            }
            // A blank final answer (reasoning-only finals, an empty last round) is not a report
            // — re-asking the SAME context deterministically returns blank again, which is how
            // a review ends up "finished without a report" twice in a row. One nudge round
            // with the transcript now in history breaks the pattern; if it is still blank the
            // caller's honest "(no report)" envelope stands.
            if (failure.get() == null && (report.get() == null || report.get().isBlank())) {
                awaitIdle(nested);
                CountDownLatch retryDone = new CountDownLatch(1);
                history.add(AiChatMessage.user(
                        "Your previous reply contained no final report. Produce the final "
                                + "report NOW as plain text in your answer — do not call any tools."));
                AiStreamCallback retrySink = new AiStreamCallback() {
                    @Override public void onToken(String fragment) {}
                    @Override public void onToolCall(fan.summer.fengyu.ai.AiToolCall toolCall) {}
                    @Override public void onToolResult(String id, AiToolResult result) {}
                    @Override public void onComplete(String fullResponse, int completionTokens, double tps) {
                        report.set(fullResponse == null ? "" : fullResponse);
                        tokens.set(tokens.get() == null ? completionTokens
                                : tokens.get() + completionTokens);
                        retryDone.countDown();
                    }
                    @Override public void onError(Throwable error) {
                        // The first (blank) answer still stands as the best result — a failed
                        // nudge must not turn a finished run into a reported failure.
                        retryDone.countDown();
                    }
                };
                nested.chat(history,
                        fan.summer.fengyu.ai.AiConfigService.getAiTemperature(),
                        fan.summer.fengyu.ai.AiConfigService.getAiTopP(),
                        fan.summer.fengyu.ai.AiConfigService.getAiMaxTokens(),
                        List.of(), retrySink);
                if (!retryDone.await(hardRunTimeoutSeconds, TimeUnit.SECONDS)) {
                    // Same hard budget for the nudge: stop the zombie generation, keep the
                    // honest blank result (a failed nudge never becomes a reported failure).
                    nested.cancelGeneration();
                }
            }
        } catch (InterruptedException interrupted) {
            // The outer turn was stopped (ExploreSubagentTool's local-reference pattern):
            // cancel THIS run's backend — the loop actually running on the provider's
            // bill is the local instance, not whatever the cached field points at now.
            Thread.currentThread().interrupt();
            nested.cancelGeneration();
            throw interrupted;
        } finally {
            activeRuns.remove(nested);
        }
        Throwable error = failure.get();
        if (error != null) {
            throw error instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException(error.getMessage(), error);
        }
        return new Result(report.get() == null ? "" : report.get(),
                tokens.get() == null ? 0 : tokens.get());
    }

    /**
     * The backend's single-slot {@code generating} flag clears in the worker's finally,
     * which runs AFTER onComplete fires — reusing the backend immediately can hit
     * "Generation already in progress". Bound wait, not a correctness requirement.
     * Package-private: {@link ExploreSubagentTool}'s blank-report nudge shares it.
     */
    static void awaitIdle(SpringAiCloudBackend backend) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (backend.isGenerating() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Override
    public void cancel() {
        // Every in-flight run first (concurrent delegations may sit on different
        // instances after a config-change rebuild), then the cached field — it covers
        // the resolve→register window of a run that has not added itself yet. Cancelling
        // an idle backend is harmless (the flag is re-armed on the next start).
        for (SpringAiCloudBackend nested : activeRuns) {
            nested.cancelGeneration();
        }
        SpringAiCloudBackend cached = backend;
        if (cached != null) cached.cancelGeneration();
    }

    /** A ready cloud backend, rebuilt whenever the active provider config changes. */
    private synchronized SpringAiCloudBackend backend() {
        String mode = fan.summer.fengyu.ai.AiConfigService.getAiMode();
        String endpoint;
        String apiKey;
        String model;
        switch (mode) {
            case "openai" -> {
                endpoint = fan.summer.fengyu.ai.AiConfigService.getAiOpenAiEndpoint();
                apiKey = fan.summer.fengyu.ai.AiConfigService.getAiOpenAiApiKey();
                model = fan.summer.fengyu.ai.AiConfigService.getAiOpenAiModel();
            }
            case "anthropic" -> {
                endpoint = fan.summer.fengyu.ai.AiConfigService.getAiAnthropicEndpoint();
                apiKey = fan.summer.fengyu.ai.AiConfigService.getAiAnthropicApiKey();
                model = fan.summer.fengyu.ai.AiConfigService.getAiAnthropicModel();
            }
            case "deepseek" -> {
                endpoint = fan.summer.fengyu.ai.AiConfigService.getAiDeepSeekEndpoint();
                apiKey = fan.summer.fengyu.ai.AiConfigService.getAiDeepSeekApiKey();
                model = fan.summer.fengyu.ai.AiConfigService.getAiDeepSeekModel();
            }
            default -> {
                return null;
            }
        }
        if (endpoint == null || endpoint.isBlank() || apiKey == null || apiKey.isBlank()
                || model == null || model.isBlank()) {
            return null;
        }
        String fingerprint = mode + "|" + endpoint + "|" + model + "|" + apiKey.hashCode();
        SpringAiCloudBackend cached = backend;
        if (cached != null && Objects.equals(fingerprint, backendFingerprint)
                && !cached.isGenerating()) {
            return cached;
        }
        SpringAiCloudBackend fresh = switch (mode) {
            case "anthropic" -> SpringAiCloudBackend.anthropic(endpoint, apiKey, model);
            case "deepseek" -> SpringAiCloudBackend.deepSeek(endpoint, apiKey, model);
            default -> SpringAiCloudBackend.openAi(endpoint, apiKey, model);
        };
        if (!fresh.isReady()) return null;
        backend = fresh;
        backendFingerprint = fingerprint;
        return fresh;
    }
}
