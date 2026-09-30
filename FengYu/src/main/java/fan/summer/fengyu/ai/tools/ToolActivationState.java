package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiChatMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-conversation-turn activation set for dynamic tool loading (pi's {@code setActiveTools}
 * semantics): additive-only within a chat so provider prompt-cache prefixes stay stable, capped
 * so an over-broad search cannot silently rebuild the full catalog, and seedable from the
 * mirrored chat history so a follow-up user turn does not pay the loader round-trip again for
 * tools the model was already using.
 */
public final class ToolActivationState {

    /** Activation ceiling; guards against the model sweeping the whole catalog in via broad queries. */
    public static final int MAX_ACTIVATED = 40;

    /**
     * Machine-readable marker line embedded in every {@code search_tools} result. The chat
     * history is client-owned and replayed on the next turn; scanning these markers rebuilds
     * the activation set without any server-side session state.
     */
    public static final String MARKER_PREFIX = "[fengyu-activated:";

    private final Set<String> eligible;
    private final Set<String> activated = new LinkedHashSet<>();
    private int version;

    public ToolActivationState(Set<String> eligibleToolNames) {
        this.eligible = Set.copyOf(eligibleNames(eligibleToolNames));
    }

    /** @return true when the name was newly activated (false: unknown, already active, or cap reached) */
    public synchronized boolean activate(String toolName) {
        if (toolName == null || toolName.isBlank()) return false;
        if (activated.contains(toolName) || !eligible.contains(toolName)) return false;
        if (activated.size() >= MAX_ACTIVATED) return false;
        activated.add(toolName);
        version++;
        return true;
    }

    public synchronized boolean isActive(String toolName) {
        return activated.contains(toolName);
    }

    public synchronized boolean isEligible(String toolName) {
        return eligible.contains(toolName);
    }

    public synchronized int activatedCount() {
        return activated.size();
    }

    public synchronized boolean isFull() {
        return activated.size() >= MAX_ACTIVATED;
    }

    /** Bumped on every change so the tool loop can rebuild the attached set only when needed. */
    public synchronized int version() {
        return version;
    }

    public synchronized Set<String> activatedCopy() {
        return new LinkedHashSet<>(activated);
    }

    /**
     * Rebuilds an activation set from the incoming chat history: the newest
     * {@code [fengyu-activated: ...]} markers first (most recent intent wins the cap), filtered
     * against the current eligible catalog so a tool removed or disabled since is not restored.
     */
    public static ToolActivationState seedFrom(List<AiChatMessage> history, Set<String> eligibleToolNames) {
        ToolActivationState state = new ToolActivationState(eligibleToolNames);
        if (history == null) return state;
        List<String> collected = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && collected.size() < MAX_ACTIVATED; i--) {
            AiChatMessage message = history.get(i);
            if (message.role() != AiChatMessage.Role.TOOL) continue;
            if (!"search_tools".equals(message.toolName())) continue;
            List<String> names = parseMarker(message.content());
            for (int j = names.size() - 1; j >= 0; j--) collected.add(names.get(j));
        }
        for (String name : collected) state.activate(name);
        return state;
    }

    /** Parses {@code [fengyu-activated: a, b]} into {@code [a, b]}; tolerant of absent/malformed markers. */
    public static List<String> parseMarker(String toolResultContent) {
        List<String> names = new ArrayList<>();
        if (toolResultContent == null) return names;
        int start = toolResultContent.indexOf(MARKER_PREFIX);
        if (start < 0) return names;
        int from = start + MARKER_PREFIX.length();
        int end = toolResultContent.indexOf(']', from);
        if (end < 0) return names;
        for (String part : toolResultContent.substring(from, end).split(",")) {
            String name = part.trim();
            if (!name.isEmpty() && !name.isBlank()) names.add(name);
        }
        return names;
    }

    /** Renders the marker line for a {@code search_tools} result. */
    public static String markerFor(Collection<String> activatedNames) {
        return MARKER_PREFIX + " " + String.join(", ", activatedNames) + "]";
    }

    private static Set<String> eligibleNames(Set<String> names) {
        Set<String> cleaned = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) cleaned.add(name.trim());
            }
        }
        return cleaned;
    }
}
