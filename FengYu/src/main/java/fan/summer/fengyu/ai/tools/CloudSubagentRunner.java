package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.service.SpringAiCloudBackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The production sub-loop behind FengYu's subagent tools ({@code delegate_task},
 * {@code review}): runs the nested model conversation on a FRESH cloud backend — the
 * ACTIVE backend is mid-generation and its single-slot {@code generating} guard would
 * reject a nested call — cached per provider-config fingerprint so config changes rebuild
 * it on the next dispatch. {@link #cancel()} stops the in-flight generation.
 *
 * <p>The sink is silent: subagent steps never reach the outer transcript; only the final
 * report (or failure) crosses back. Shared by every subagent tool so the caching, provider
 * switch, and cancel semantics stay identical wherever a nested loop runs.</p>
 */
final class CloudSubagentRunner implements DelegateTaskTool.SubagentRunner {

    private volatile SpringAiCloudBackend backend;
    private volatile String backendFingerprint;

    @Override
    public Result run(Spec spec) throws Exception {
        SpringAiCloudBackend nested = backend();
        if (nested == null) {
            throw new IllegalStateException("the subagent needs a cloud provider "
                    + "(OpenAI/Anthropic/DeepSeek); local mode does not support it");
        }
        nested.setToolCallbacks(spec.tools());
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
        nested.chat(history,
                fan.summer.fengyu.ai.AiConfigService.getAiTemperature(),
                fan.summer.fengyu.ai.AiConfigService.getAiTopP(),
                fan.summer.fengyu.ai.AiConfigService.getAiMaxTokens(),
                List.of(), sink);
        done.await();
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
            retryDone.await();
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
        SpringAiCloudBackend nested = backend;
        if (nested != null) nested.cancelGeneration();
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
