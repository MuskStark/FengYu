package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Outbound transcript normalization applied once per model request, after
 * compaction and before {@link AiMessageBridge} bridging (pi's transform-messages
 * layer). FengYu history stays untouched — the UI, rollout recording, and history
 * mirroring keep seeing the original messages; only the wire view is normalized.
 *
 * <p>Four rules, all keyed by the CURRENT target model so same-model flows are
 * byte-for-byte unchanged (zero-regression contract):
 *
 * <ol>
 *   <li><b>Reasoning is same-origin only.</b> An assistant message's
 *       {@code reasoningContent} is replay metadata valid for the endpoint that
 *       produced it (DeepSeek-class thinking endpoints require it on follow-up
 *       rounds). Messages with a stamped {@code origin} that differs from the
 *       current target lose their reasoning; messages without an origin (legacy
 *       persisted history, predating origin stamping) keep it — preserving the
 *       pre-existing behavior of every existing conversation.</li>
 *   <li><b>Tool-call IDs are wire-valid.</b> IDs already matching
 *       {@code [A-Za-z0-9_-]{1,64}} (every provider's native shape) pass through
 *       unchanged; anything else — e.g. OpenAI Responses' 450+ char IDs with
 *       {@code |} separators, which Anthropic rejects — is sanitized and the
 *       associated tool-result IDs remapped.</li>
 *   <li><b>Orphaned tool calls get synthetic results.</b> An assistant tool call
 *       with no matching tool result before the next assistant/user turn (a
 *       cancelled or crashed turn leaves those) would break providers that require
 *       every {@code tool_use} answered; a synthetic
 *       {@code "(no result provided)"} result keeps the protocol shape legal.</li>
 *   <li><b>Media is downgraded for known non-vision targets.</b> When the caller
 *       has an EXACT catalog assertion that the target model has no image input,
 *       image parts become a text placeholder. Unknown targets stay permissive —
 *       the strict-gateway media-rejection fallback stays authoritative for
 *       anything the catalog only guesses at (family regexes).</li>
 * </ol>
 */
final class TranscriptNormalizer {

    /** The strictest known tool-call ID grammar (Anthropic's). */
    private static final Pattern WIRE_VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_ID_LENGTH = 64;

    /** Placeholder for image parts on targets asserted (exactly) to lack image input. */
    static final String IMAGE_PLACEHOLDER = "(image omitted: model does not support images)";

    /** Synthetic result for an unanswered tool call. */
    static final String NO_RESULT = "(no result provided — the tool call was interrupted)";

    /**
     * Normalization target: the model this request is being built for.
     *
     * @param originKey      the producing-backend identity of the CURRENT target
     *                       ("provider/model"); stamped messages from a different
     *                       origin are treated as cross-model
     * @param imageSupported {@code false} only on an EXACT catalog assertion of no
     *                       image input; {@code null}/{@code true} keep media
     */
    record Target(String originKey, Boolean imageSupported) {}

    private TranscriptNormalizer() {}

    static List<AiChatMessage> normalize(List<AiChatMessage> history, Target target) {
        boolean stripImages = Boolean.FALSE.equals(target.imageSupported());
        List<AiChatMessage> mapped = new ArrayList<>(history.size());
        // Pass 1: per-message transforms (reasoning origin, ID sanitation, media).
        // ID remap is filled as assistant messages are sanitized so the following
        // TOOL messages can adopt the remapped IDs in the same pass.
        Map<String, String> idRemap = new HashMap<>();
        Set<String> usedIds = new HashSet<>();
        for (AiChatMessage m : history) {
            AiChatMessage out = m;
            if (m.role() == AiChatMessage.Role.ASSISTANT) {
                out = normalizeAssistant(m, target.originKey(), idRemap, usedIds);
            } else if (m.role() == AiChatMessage.Role.TOOL) {
                String remapped = idRemap.get(m.toolCallId());
                if (remapped != null && !remapped.equals(m.toolCallId())) {
                    out = new AiChatMessage(m.role(), m.content(), m.toolCalls(), remapped,
                            m.toolName(), m.reasoningContent(), m.media(), m.origin());
                }
            }
            if (stripImages && !m.media().isEmpty()) {
                out = withoutMedia(out);
            }
            mapped.add(out);
        }
        // Pass 2: synthesize results for tool calls never answered.
        return withSyntheticToolResults(mapped);
    }

    private static AiChatMessage normalizeAssistant(AiChatMessage m, String targetOrigin,
            Map<String, String> idRemap, Set<String> usedIds) {
        boolean crossOrigin = m.origin() != null && targetOrigin != null
                && !m.origin().equals(targetOrigin);
        boolean stripReasoning = crossOrigin && m.reasoningContent() != null;

        List<AiToolCall> calls = m.toolCalls();
        List<AiToolCall> sanitizedCalls = null;
        for (int i = 0; i < calls.size(); i++) {
            AiToolCall call = calls.get(i);
            String id = call.id() == null ? "" : call.id();
            String wireId = id;
            if (!WIRE_VALID_ID.matcher(id).matches()) {
                wireId = uniqueWireId(id, usedIds);
                idRemap.put(id, wireId);
            }
            usedIds.add(wireId);
            if (!wireId.equals(id)) {
                if (sanitizedCalls == null) sanitizedCalls = new ArrayList<>(calls);
                sanitizedCalls.set(i, new AiToolCall(wireId, call.name(), call.arguments()));
            }
        }
        if (!stripReasoning && sanitizedCalls == null) return m;
        return new AiChatMessage(m.role(), m.content(),
                sanitizedCalls != null ? List.copyOf(sanitizedCalls) : m.toolCalls(),
                m.toolCallId(), m.toolName(),
                stripReasoning ? null : m.reasoningContent(),
                m.media(), m.origin());
    }

    /** Collapses an invalid ID to the wire grammar; keeps a stable prefix and uniqueness. */
    private static String uniqueWireId(String raw, Set<String> usedIds) {
        StringBuilder sb = new StringBuilder(MAX_ID_LENGTH);
        for (int i = 0; i < raw.length() && sb.length() < MAX_ID_LENGTH; i++) {
            char c = raw.charAt(i);
            sb.append((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        if (sb.isEmpty()) sb.append("call");
        String base = sb.toString();
        String candidate = base;
        int suffix = 1;
        while (usedIds.contains(candidate)) {
            String tail = "-" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), MAX_ID_LENGTH - tail.length())) + tail;
        }
        return candidate;
    }

    private static AiChatMessage withoutMedia(AiChatMessage m) {
        String content = m.content() == null || m.content().isBlank()
                ? IMAGE_PLACEHOLDER
                : m.content() + "\n" + IMAGE_PLACEHOLDER;
        return new AiChatMessage(m.role(), content, m.toolCalls(), m.toolCallId(),
                m.toolName(), m.reasoningContent(), List.of(), m.origin());
    }

    /**
     * Inserts a synthetic tool result for every assistant tool call that has no
     * matching result before the conversation moves on (next assistant/user turn or
     * end of history). System messages between a tool call and its results are
     * transparent to the pairing.
     */
    private static List<AiChatMessage> withSyntheticToolResults(List<AiChatMessage> messages) {
        List<AiChatMessage> result = new ArrayList<>(messages.size());
        List<AiToolCall> pendingCalls = null;
        Set<String> answeredIds = new HashSet<>();
        List<AiChatMessage> heldSystemMessages = new ArrayList<>();
        for (AiChatMessage m : messages) {
            switch (m.role()) {
                case ASSISTANT -> {
                    flushPending(result, pendingCalls, answeredIds, heldSystemMessages);
                    pendingCalls = m.hasToolCalls() ? m.toolCalls() : null;
                    answeredIds = new HashSet<>();
                    result.add(m);
                }
                case TOOL -> {
                    if (m.toolCallId() != null) answeredIds.add(m.toolCallId());
                    result.add(m);
                }
                case SYSTEM -> {
                    if (pendingCalls != null) heldSystemMessages.add(m);
                    else result.add(m);
                }
                default -> {
                    flushPending(result, pendingCalls, answeredIds, heldSystemMessages);
                    pendingCalls = null;
                    result.add(m);
                }
            }
        }
        flushPending(result, pendingCalls, answeredIds, heldSystemMessages);
        return result;
    }

    private static void flushPending(List<AiChatMessage> result, List<AiToolCall> pendingCalls,
            Set<String> answeredIds, List<AiChatMessage> heldSystemMessages) {
        if (pendingCalls != null) {
            for (AiToolCall call : pendingCalls) {
                if (call.id() != null && !answeredIds.contains(call.id())) {
                    result.add(AiChatMessage.toolResult(call.id(), call.name(), NO_RESULT));
                }
            }
        }
        result.addAll(heldSystemMessages);
        heldSystemMessages.clear();
    }
}
