package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.session.AiRolloutService;
import fan.summer.fengyu.ai.session.RolloutEvent;
import fan.summer.fengyu.database.entity.ai.ChatMessageEntity;
import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ChatMessageRepository;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code read_session} tool: the model's cross-session memory, read-only. Inside a
 * conversation the model can only see that conversation's own context, so "what did we
 * decide in the earlier session about X" or "continue the task from last week" are
 * unanswerable without a retrieval surface. This tool provides exactly that, in the
 * terminal coding-agent two-phase shape: <b>list</b> (no {@code conversationId}) returns
 * the most recent sessions with id/title/updatedAt/message estimate, then <b>read</b>
 * (with an id chosen from that list) returns a compact text transcript of the session's
 * trailing messages. The split exists because the model cannot guess ids — it must
 * discover them before it can address them, and a blind read of an unknown id is an
 * error, not a wildcard search.
 *
 * <p><b>Read path is reuse, not re-parsing:</b> the rollout log under
 * {@code ~/.fengyu/rollouts/<id>/rollout.jsonl} is the server's own memory of what
 * actually happened (including tool traffic), so reading goes through
 * {@link AiRolloutService#rebuild(Long, long)} — the same replay resume and fork use.
 * When no rollout exists (old install, log lost, recording disabled by a disk failure)
 * the tool falls back to the saved {@link ChatMessageEntity} rows, which trade tool
 * traffic for durability. Both sources render through the same compact
 * {@code role: content} transcript: one line per message, each capped at
 * {@link #MESSAGE_CAP} characters (a session transcript is a reference, not a file
 * dump — the cap keeps one giant paste from crowding out the rest of the window), with
 * the session title as the first line so the model can sanity-check it picked the
 * right session.
 *
 * <p><b>Read-only and ownership-scoped by construction:</b> the tool never writes
 * anywhere (READ effect, so it auto-runs without approval in every permission mode),
 * and every read is gated by {@link ConversationRepository#findByIdAndUserId(Long, Long)}
 * before the rollout directory is touched — rollout paths carry no user component
 * (they predate multi-user and are keyed by conversation id alone), so the DB check is
 * what prevents one user's model from reading another user's history by id guessing.
 * The current conversation may also be read — that is a feature, not an oversight:
 * after a compaction cut the surviving context no longer contains the earlier turns,
 * and this tool is the model's way to look them back up.
 *
 * <p>Output volume is bounded in layers: {@code lastMessages} (default 20, hard cap
 * 100) bounds the line count, {@link #MESSAGE_CAP} bounds each line, and the audited
 * callback wrapper's {@link ToolResultBudget} class budget is the final backstop for
 * pathological totals (100 x 2000 chars overflows it; the overflow marker then names
 * the saved full-output artifact, so nothing is silently lost).
 */
@Component
public class ReadSessionTool implements FengYuTool, ToolEffectProvider {

    /** The registered tool name. */
    public static final String NAME = "read_session";

    /** Sessions listed in the list phase; enough to recognize a target, small enough to scan. */
    static final int LIST_LIMIT = 20;
    /** Default trailing-message window of the read phase. */
    static final int DEFAULT_LAST_MESSAGES = 20;
    /** Upper bound of the window — the transcript is a reference, not a full export. */
    static final int MAX_LAST_MESSAGES = 100;
    /** Per-message character cap in the transcript. */
    static final int MESSAGE_CAP = 2_000;
    /** Marker appended when a single message's content is cut at the cap. */
    static final String TRUNCATION_SUFFIX = "…[truncated]";

    private final AiRolloutService rollouts;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final SecurityContext security;

    public ReadSessionTool(AiRolloutService rollouts, ConversationRepository conversations,
            ChatMessageRepository messages, SecurityContext security) {
        this.rollouts = rollouts;
        this.conversations = conversations;
        this.messages = messages;
        this.security = security;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return NAME.equals(toolName) ? ToolEffect.READ : null;
    }

    /**
     * @param conversationId the session to read, chosen from a prior list call; omit to list
     * @param lastMessages   trailing messages to include when reading (default 20, max 100)
     * @return the list view, the session transcript, or actionable guidance
     */
    @Tool(name = NAME,
          description = "Read a past conversation session (strictly read-only). Two-phase usage: "
                  + "FIRST call it without conversationId to list the most recent sessions "
                  + "(id, title, last update, message estimate); THEN call it again with the "
                  + "conversationId of the session you need to get its trailing messages as a "
                  + "compact 'role: content' transcript (lastMessages, default 20, max 100; each "
                  + "message capped at 2000 characters). Use it to consult earlier work — previous "
                  + "tasks, decisions, or context that lives in another session (including earlier "
                  + "turns of the current one lost to compaction). It never modifies anything.")
    public String readSession(
            @ToolParam(required = false,
                       description = "Session id to read, taken from a prior no-argument list call; "
                               + "omit it to list the most recent sessions instead.")
            Long conversationId,
            @ToolParam(required = false,
                       description = "How many trailing messages to include when reading a session "
                               + "(default 20, at most 100).")
            Integer lastMessages) {
        if (conversationId == null) return listSessions();
        // A window below 1 is a model mistake worth naming (silent clamping would hide it);
        // values above the cap are clamped instead — the intent ("as much as possible")
        // is unambiguous, so answering it beats failing the round.
        if (lastMessages != null && lastMessages < 1) {
            return "lastMessages must be between 1 and " + MAX_LAST_MESSAGES
                    + "; got " + lastMessages + ". Re-call with a valid window, or omit it "
                    + "for the default of " + DEFAULT_LAST_MESSAGES + ".";
        }
        int window = lastMessages == null
                ? DEFAULT_LAST_MESSAGES : Math.min(lastMessages, MAX_LAST_MESSAGES);
        return readOne(conversationId, window, lastMessages != null
                && lastMessages > MAX_LAST_MESSAGES);
    }

    // ── list phase ───────────────────────────────────────────────────────────────────────

    /**
     * The discovery half. Titles and timestamps live only in the DB, so the list comes from
     * {@link ConversationRepository} (user-scoped, newest-updated first — the sidebar's own
     * ordering). The message count is an ESTIMATE from the rollout log rather than an exact
     * DB count: the repository has no count query, loading full message rows for twenty
     * sessions just to count them is waste, and the rollout event count answers the only
     * question the model asks ("roughly how big is this session?"). Zero therefore means
     * "no rollout events", not "empty session" — the DB fallback still serves the read.
     */
    private String listSessions() {
        Long userId = security.currentUserId();
        if (userId == null) {
            return "No authenticated user; past sessions are unavailable here.";
        }
        List<ConversationEntity> recent = conversations.findByUserIdOrderByUpdatedAtDesc(userId);
        if (recent == null || recent.isEmpty()) {
            return "No past sessions found for this user. Nothing to read yet.";
        }
        Long current = ConversationContext.current();
        StringBuilder out = new StringBuilder("Most recent sessions (newest first, at most ")
                .append(LIST_LIMIT).append("). To open one, call ").append(NAME)
                .append(" with its conversationId:\n");
        int listed = 0;
        for (ConversationEntity conversation : recent) {
            if (listed++ == LIST_LIMIT) break;
            out.append("- id ").append(conversation.getId())
                    .append(" | ").append(titleOf(conversation))
                    .append(" | updated ").append(conversation.getUpdatedAt())
                    .append(" | ~").append(estimateMessages(conversation.getId()))
                    .append(" msgs");
            if (conversation.getArchivedAt() != null) out.append(" | archived");
            if (current != null && current.equals(conversation.getId())) {
                out.append(" | current conversation");
            }
            out.append('\n');
        }
        if (recent.size() > LIST_LIMIT) {
            out.append('(').append(recent.size()).append(" sessions exist; only the ")
                    .append(LIST_LIMIT).append(" most recently updated are listed.)\n");
        }
        return out.toString();
    }

    private static String titleOf(ConversationEntity conversation) {
        String title = conversation.getTitle();
        return title == null || title.isBlank() ? "(untitled)" : title.strip();
    }

    /** Rollout {@code message}-event count, via the same tolerant reader resume uses. */
    private int estimateMessages(Long conversationId) {
        int count = 0;
        for (RolloutEvent event : rollouts.typedEvents(conversationId)) {
            if (event instanceof RolloutEvent.MessageAppended) count++;
        }
        return count;
    }

    // ── read phase ───────────────────────────────────────────────────────────────────────

    private String readOne(long conversationId, int window, boolean windowCapped) {
        Long userId = security.currentUserId();
        // The ownership gate doubles as existence: an id that is not THIS user's session is
        // indistinguishable from a nonexistent one, and both must answer the same guidance.
        ConversationEntity conversation = userId == null ? null
                : conversations.findByIdAndUserId(conversationId, userId).orElse(null);
        if (conversation == null) {
            return "Conversation " + conversationId + " was not found among your sessions. "
                    + "Call " + NAME + " without conversationId to list the sessions you "
                    + "can read, then retry with one of those ids.";
        }
        String title = titleOf(conversation);

        // Preferred source: the rollout log (includes tool calls and their results — the
        // parts of a coding session that usually carry the answer). Rebuild shares the
        // exact replay path of resume/fork; the tool never parses the JSONL itself.
        List<AiChatMessage> recorded = rollouts.rebuild(conversationId, Long.MAX_VALUE);
        if (!recorded.isEmpty()) {
            return transcript(conversationId, title, rolloutLines(recorded), window,
                    "rollout log", windowCapped);
        }

        // Fallback: the saved transcript rows. Durable, but user/assistant text only.
        List<ChatMessageEntity> saved = messages.findByConversationIdOrderBySeqAsc(conversationId);
        return transcript(conversationId, title, savedLines(saved), window,
                "saved DB messages (no rollout log)", windowCapped);
    }

    /** Rollout messages as compact transcript lines; see {@link #appendLine}. */
    private static List<String> rolloutLines(List<AiChatMessage> recorded) {
        List<String> lines = new ArrayList<>();
        for (AiChatMessage message : recorded) {
            // System prompts repeat every turn in the log and are pure noise for a
            // cross-session reference; blank no-op messages say nothing either.
            if (message.role() == AiChatMessage.Role.SYSTEM) continue;
            String role = message.role() == AiChatMessage.Role.TOOL
                    ? "tool(" + orUnknown(message.toolName()) + ")"
                    : message.role().name().toLowerCase(Locale.ROOT);
            String content = message.content() == null ? "" : message.content();
            String calls = callSummary(message);
            if (content.isBlank() && calls.isEmpty()) continue;
            String body = calls.isEmpty() ? content
                    : content.isBlank() ? calls : content + " " + calls;
            appendLine(lines, role, body);
        }
        return lines;
    }

    /** Saved DB rows as the same transcript shape (roles are stored lowercase). */
    private static List<String> savedLines(List<ChatMessageEntity> saved) {
        List<String> lines = new ArrayList<>();
        for (ChatMessageEntity message : saved == null ? List.<ChatMessageEntity>of() : saved) {
            String role = message.getRole() == null ? "user"
                    : message.getRole().toLowerCase(Locale.ROOT);
            String content = message.getContent() == null ? "" : message.getContent();
            if (content.isBlank()) continue;
            appendLine(lines, role, content);
        }
        return lines;
    }

    /** One capped {@code role: content} line; the cap cut backs off a surrogate half. */
    private static void appendLine(List<String> lines, String role, String content) {
        if (content.length() > MESSAGE_CAP) {
            int cut = MESSAGE_CAP;
            // Never split a surrogate pair (CJK-heavy sessions make this the common case,
            // and a lone half would mojibake the transcript for every downstream reader).
            if (Character.isHighSurrogate(content.charAt(cut - 1))) cut--;
            content = content.substring(0, cut) + TRUNCATION_SUFFIX;
        }
        lines.add(role + ": " + content);
    }

    /**
     * Assistant tool calls rendered as a name-only bracket note. Arguments are dropped on
     * purpose: the paired {@code tool(...)} result line that follows carries the outcome,
     * and full argument JSON is the fastest way to burn the transcript budget.
     */
    private static String callSummary(AiChatMessage message) {
        if (!message.hasToolCalls()) return "";
        List<String> callNames = new ArrayList<>();
        for (var call : message.toolCalls()) callNames.add(call.name());
        return "[tool_call: " + String.join(", ", callNames) + "]";
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /** Title first, then the "last X of Y" window note, then the trailing lines. */
    private static String transcript(long conversationId, String title, List<String> lines,
            int window, String source, boolean windowCapped) {
        if (lines.isEmpty()) {
            return "Session " + conversationId + " (" + title + ") has no readable messages.";
        }
        List<String> tail = lines.size() > window
                ? lines.subList(lines.size() - window, lines.size()) : lines;
        StringBuilder out = new StringBuilder("Session ").append(conversationId)
                .append(": ").append(title).append('\n')
                .append("Showing the last ").append(tail.size()).append(" of ")
                .append(lines.size()).append(" messages (source: ").append(source)
                .append(')');
        if (windowCapped) out.append("; lastMessages was capped at ").append(MAX_LAST_MESSAGES);
        out.append(".\n");
        for (String line : tail) out.append(line).append('\n');
        return out.toString();
    }
}
