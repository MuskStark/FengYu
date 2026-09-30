package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Effect-grouped parallel tool-call execution — the read/write-lock scheduling of terminal
 * coding agents, grafted onto Spring AI's {@link ToolCallingManager}.
 *
 * <p>A model round may request several tool calls at once; executing them strictly in order
 * wastes wall-clock time when they are independent inspections. This executor partitions the
 * round's calls by {@link ToolEffect} with barrier semantics: a consecutive run of READ
 * calls executes concurrently on virtual threads, and every WRITE / COMMAND / EXTERNAL call
 * executes alone, flushing the concurrent run before it and gating the one after — a write
 * excludes reads exactly like an exclusive lock would. Tool-call results are re-assembled in
 * the ORIGINAL call order, so the conversation history the model sees is identical to a
 * plain sequential round.</p>
 *
 * <p>Each individual call still runs THROUGH the manager (synthesized as a single-call
 * response), preserving all of its per-call behavior: callback resolution against the
 * prompt's attached tools, tool-call limits, observability, and exception processing. The
 * approval gate is untouched — the backends run it before this executor, on the worker
 * thread, so approvals are always requested in tool-call order regardless of execution
 * parallelism. Virtual threads inherit the inheritable thread-local contexts
 * (workspace binding, permission mode, conversation id, file grants) from the worker.</p>
 */
final class ToolBatchExecutor {

    private ToolBatchExecutor() {}

    /**
     * Executes all tool calls of {@code assistantMessage} under read/write scheduling and
     * returns a merged {@link ToolExecutionResult} whose conversation history is
     * prompt instructions + the full assistant message + one combined tool response.
     */
    static ToolExecutionResult executeToolCalls(ToolCallingManager manager, Prompt prompt,
            AssistantMessage assistantMessage, List<ToolCallback> attachedTools) {
        List<AssistantMessage.ToolCall> calls = assistantMessage.getToolCalls();
        if (calls.size() <= 1) {
            return manager.executeToolCalls(prompt, responseOf(assistantMessage));
        }

        Map<String, ToolEffect> effects = effectsByName(attachedTools);
        ToolResponseMessage.ToolResponse[] ordered =
                new ToolResponseMessage.ToolResponse[calls.size()];
        RuntimeException failure = null;

        // The conversation history the manager builds replays the SYNTHESIZED messages,
        // so each single-call wrapper must carry the original assistant message's
        // metadata (the reasoning_content a thinking endpoint DEMANDS on replay —
        // dropping it here was the live DeepSeek 400).
        java.util.Map<String, Object> replayMetadata = assistantMessage.getMetadata();
        int index = 0;
        while (index < calls.size() && failure == null) {
            if (effectOf(effects, calls.get(index)) != ToolEffect.READ) {
                failure = executeSerial(manager, prompt, calls.get(index), ordered, index,
                        replayMetadata);
                index++;
                continue;
            }
            int start = index;
            while (index < calls.size()
                    && effectOf(effects, calls.get(index)) == ToolEffect.READ) {
                index++;
            }
            failure = executeConcurrent(manager, prompt, calls.subList(start, index),
                    ordered, start, replayMetadata);
        }
        if (failure != null) throw failure;

        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistantMessage);
        history.add(ToolResponseMessage.builder().responses(List.of(ordered)).build());
        return ToolExecutionResult.builder()
                .conversationHistory(List.copyOf(history))
                .returnDirect(false)
                .build();
    }

    // ── scheduling ───────────────────────────────────────────────────────────────────────

    private static RuntimeException executeSerial(ToolCallingManager manager, Prompt prompt,
            AssistantMessage.ToolCall call, ToolResponseMessage.ToolResponse[] ordered,
            int position, java.util.Map<String, Object> replayMetadata) {
        try {
            ordered[position] = executeSingle(manager, prompt, call, replayMetadata);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** One concurrent READ run: every call gets its own virtual thread; join before returning. */
    private static RuntimeException executeConcurrent(ToolCallingManager manager, Prompt prompt,
            List<AssistantMessage.ToolCall> calls, ToolResponseMessage.ToolResponse[] ordered,
            int offset, java.util.Map<String, Object> replayMetadata) {
        CountDownLatch done = new CountDownLatch(calls.size());
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        for (int i = 0; i < calls.size(); i++) {
            AssistantMessage.ToolCall call = calls.get(i);
            int position = offset + i;
            Thread.ofVirtual().start(() -> {
                try {
                    ordered[position] = executeSingle(manager, prompt, call, replayMetadata);
                } catch (RuntimeException e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new IllegalStateException("interrupted while executing read tools", e);
        }
        return failure.get();
    }

    // ── per-call bridge into the manager ────────────────────────────────────────────────

    /**
     * Runs one tool call through the manager by wrapping it as a single-call ChatResponse —
     * resolution, limits, observability, and exception processing all stay the manager's.
     */
    private static ToolResponseMessage.ToolResponse executeSingle(ToolCallingManager manager,
            Prompt prompt, AssistantMessage.ToolCall call,
            java.util.Map<String, Object> replayMetadata) {
        AssistantMessage single = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .properties(replayMetadata == null || replayMetadata.isEmpty()
                        ? java.util.Map.of() : replayMetadata)
                .build();
        ToolExecutionResult result = manager.executeToolCalls(prompt, responseOf(single));
        List<Message> history = result.conversationHistory();
        Message last = history.isEmpty() ? null : history.get(history.size() - 1);
        if (last instanceof ToolResponseMessage response && !response.getResponses().isEmpty()) {
            return response.getResponses().getFirst();
        }
        throw new IllegalStateException("Tool execution produced no response for " + call.name());
    }

    private static ChatResponse responseOf(AssistantMessage assistantMessage) {
        return new ChatResponse(List.of(new Generation(assistantMessage)));
    }

    private static Map<String, ToolEffect> effectsByName(List<ToolCallback> attachedTools) {
        Map<String, ToolEffect> effects = new HashMap<>();
        for (ToolCallback callback : attachedTools) {
            if (callback instanceof AuditedToolCallback audited) {
                effects.put(callback.getToolDefinition().name(), audited.effect());
            }
        }
        return effects;
    }

    /** Unknown names (and non-audited callbacks) schedule as exclusive — never concurrently. */
    private static ToolEffect effectOf(Map<String, ToolEffect> effects,
            AssistantMessage.ToolCall call) {
        return effects.getOrDefault(call.name(), ToolEffect.EXTERNAL);
    }
}
