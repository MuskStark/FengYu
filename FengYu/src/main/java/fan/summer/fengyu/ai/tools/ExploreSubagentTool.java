package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.service.SpringAiCloudBackend;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@code explore} subagent: dispatches a READ-ONLY research task (search, read,
 * inspect) to a nested model loop with only {@code read_file}/{@code grep}/{@code glob}
 * attached, and returns its final report. Keeps broad investigation out of the main
 * conversation's context — the outer turn sees only the conclusions (terminal
 * coding-agent practice).
 *
 * <p>The sub-loop runs on a PRIVATE cloud backend instance (never the host's active
 * one), cached per provider config fingerprint; reuse is refused while a run may be
 * in flight so concurrent explores never cross-wire each other's tool list.
 * Read-only tools never hit the approval gate, and subagent tool traffic is silent —
 * its steps never pollute the outer transcript.</p>
 */
@Component
public class ExploreSubagentTool implements FengYuTool, ToolEffectProvider {

    private static final Logger log = LoggerFactory.getLogger(ExploreSubagentTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final long MAX_WALL_SECONDS = 180;

    static final String EXPLORE_PERSONA = """
            You are a read-only research subagent inside a coding workspace. Investigate the
            assigned task using ONLY the read_file, grep, and glob tools — you cannot write,
            run commands, or reach the network. Be systematic: locate candidates with glob
            and grep, then read the relevant regions (offset/limit for big files).
            Finish with a concise plain-text report:
            - direct answer to the task first,
            - the key evidence as path:line references,
            - anything you could not establish and why.
            Do not ask questions; state findings and stop.""";

    private final WorkspaceFileTools fileTools;

    /** Cached subagent backend + the config fingerprint it was built from. */
    private volatile SpringAiCloudBackend subagentBackend;
    private volatile String subagentFingerprint;
    /** True while a run may be between resolve and its chat() registration — blocks cache
     *  reuse so two concurrent explores never cross-wire setToolCallbacks on one instance. */
    private volatile boolean subagentInUse;

    public ExploreSubagentTool(WorkspaceFileTools fileTools) {
        this.fileTools = fileTools;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return "explore".equals(toolName) ? ToolEffect.READ : null;
    }

    @Tool(name = "explore",
          description = "Dispatch a READ-ONLY research subagent to investigate the workspace "
                  + "and report back (answers, key findings, path:line references). Use it for "
                  + "broad multi-file investigation — 'where is X implemented', 'list the usages "
                  + "of Y', 'how does Z work' — so the main conversation keeps only the "
                  + "conclusions, not the search traffic. The subagent cannot modify anything.")
    public String explore(
            @ToolParam(description = "The research question or task, specific and self-contained.") String task,
            @ToolParam(required = false,
                       description = "Optional hint narrowing where to look (a subdirectory or file pattern).")
            String focus) {
        SpringAiCloudBackend backend = null; // visible to the interrupt handler below
        try {
            if (WorkspaceContext.current() == null) {
                return error("This conversation has no workspace attached");
            }
            if (task == null || task.isBlank()) return error("task must not be blank");
            backend = subagent();
            if (backend == null) {
                return error("The explore subagent needs a cloud provider (OpenAI/Anthropic/"
                        + "DeepSeek); local mode does not support it");
            }

            List<ToolCallback> readonlyTools = new ArrayList<>();
            for (ToolCallback callback : ToolCallbacks.from(fileTools)) {
                String name = callback.getToolDefinition().name();
                if ("read_file".equals(name) || "grep".equals(name) || "glob".equals(name)) {
                    readonlyTools.add(callback);
                }
            }
            backend.setToolCallbacks(readonlyTools);

            String prompt = task.strip()
                    + (focus == null || focus.isBlank() ? "" : "\n\nFocus area: " + focus.strip());
            List<AiChatMessage> history = new ArrayList<>(List.of(
                    AiChatMessage.system(EXPLORE_PERSONA),
                    AiChatMessage.user(prompt)));

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> report = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            // Silent callback: subagent steps never reach the outer transcript; only the
            // final report (or failure) crosses back.
            AiStreamCallback sink = new AiStreamCallback() {
                @Override public void onToken(String fragment) {}
                @Override public void onToolCall(fan.summer.fengyu.ai.AiToolCall toolCall) {}
                @Override public void onToolResult(String id, AiToolResult result) {}
                @Override public void onComplete(String fullResponse, int tokens, double tps) {
                    report.set(fullResponse == null ? "" : fullResponse);
                    done.countDown();
                }
                @Override public void onError(Throwable error) {
                    failure.set(error);
                    done.countDown();
                }
            };
            backend.chat(history,
                    fan.summer.fengyu.ai.AiConfigService.getAiTemperature(),
                    fan.summer.fengyu.ai.AiConfigService.getAiTopP(),
                    fan.summer.fengyu.ai.AiConfigService.getAiMaxTokens(),
                    List.of(), sink);
            if (!done.await(MAX_WALL_SECONDS, TimeUnit.SECONDS)) {
                backend.cancelGeneration();
                return error("explore timed out after " + MAX_WALL_SECONDS + "s");
            }
            Throwable error = failure.get();
            if (error != null) {
                return error("explore failed: "
                        + (error.getMessage() == null ? error.toString() : error.getMessage()));
            }
            String summary = report.get() == null ? "" : report.get().strip();
            if (summary.isEmpty()) {
                summary = nudgeForReport(backend, history);
            }
            if (summary.isEmpty()) return error("explore produced no report");
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("success", true);
            result.put("report", summary);
            return toJson(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The outer turn was stopped: cancel THIS call's sub-loop — the cached
            // subagentBackend may already have been swapped for an unrelated instance,
            // and the loop actually running on the provider's bill is the local one.
            if (backend != null) backend.cancelGeneration();
            return error("explore interrupted");
        } catch (fan.summer.fengyu.ai.AiServiceException | RuntimeException e) {
            log.debug("explore subagent failed: {}", e.toString());
            return error("explore failed: " + e.getMessage());
        } finally {
            subagentInUse = false;
        }
    }

    /** Wall budget for the blank-report nudge round, on top of the first attempt. */
    static final long NUDGE_WALL_SECONDS = 120;

    /**
     * A blank final answer (reasoning-only finals, an empty last round) is not a report —
     * re-asking the SAME context deterministically returns blank again. One nudge round
     * with the transcript now in history breaks the pattern (same rationale as
     * {@link CloudSubagentRunner}); a failed or still-blank nudge keeps the honest
     * "produced no report" error.
     */
    private static String nudgeForReport(SpringAiCloudBackend backend,
            List<AiChatMessage> history) {
        try {
            CloudSubagentRunner.awaitIdle(backend);
            history.add(AiChatMessage.user(
                    "Your previous reply contained no report. Produce the final report "
                            + "NOW as plain text in your answer — do not call any tools."));
            CountDownLatch retryDone = new CountDownLatch(1);
            AtomicReference<String> nudged = new AtomicReference<>();
            AiStreamCallback retrySink = new AiStreamCallback() {
                @Override public void onToken(String fragment) {}
                @Override public void onToolCall(fan.summer.fengyu.ai.AiToolCall toolCall) {}
                @Override public void onToolResult(String id, AiToolResult result) {}
                @Override public void onComplete(String fullResponse, int tokens, double tps) {
                    nudged.set(fullResponse == null ? "" : fullResponse);
                    retryDone.countDown();
                }
                @Override public void onError(Throwable error) {
                    retryDone.countDown();
                }
            };
            backend.chat(history,
                    fan.summer.fengyu.ai.AiConfigService.getAiTemperature(),
                    fan.summer.fengyu.ai.AiConfigService.getAiTopP(),
                    fan.summer.fengyu.ai.AiConfigService.getAiMaxTokens(),
                    List.of(), retrySink);
            if (!retryDone.await(NUDGE_WALL_SECONDS, TimeUnit.SECONDS)) {
                backend.cancelGeneration();
                return "";
            }
            return nudged.get() == null ? "" : nudged.get().strip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            backend.cancelGeneration();
            return "";
        } catch (fan.summer.fengyu.ai.AiServiceException | RuntimeException e) {
            // The first (blank) answer already completed — a failed nudge degrades to the
            // honest "produced no report" error, never a reported failure.
            return "";
        }
    }

    /**
     * A ready cloud backend for the sub-loop, rebuilt whenever the active provider config
     * changes; null when the active mode is local (Ollama) or unconfigured.
     */
    private synchronized SpringAiCloudBackend subagent() { // fingerprint+instance swap stays atomic
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
        SpringAiCloudBackend cached = subagentBackend;
        // Refuse reuse while a run may still be between resolve and its chat()
        // registration (isGenerating can't see it yet): two concurrent explores sharing
        // one instance would cross-wire setToolCallbacks. The claim is released in
        // explore()'s finally.
        if (cached != null && Objects.equals(fingerprint, subagentFingerprint)
                && !subagentInUse && !cached.isGenerating()) {
            subagentInUse = true;
            return cached;
        }
        subagentInUse = true;
        SpringAiCloudBackend fresh = switch (mode) {
            case "anthropic" -> SpringAiCloudBackend.anthropic(endpoint, apiKey, model);
            case "deepseek" -> SpringAiCloudBackend.deepSeek(endpoint, apiKey, model);
            default -> SpringAiCloudBackend.openAi(endpoint, apiKey, model);
        };
        if (!fresh.isReady()) return null;
        subagentBackend = fresh;
        subagentFingerprint = fingerprint;
        return fresh;
    }

    private static String error(String message) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message == null ? "explore failed" : message);
        return toJson(result);
    }

    private static String toJson(Map<String, Object> result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"tool result serialization failed\"}";
        }
    }
}
