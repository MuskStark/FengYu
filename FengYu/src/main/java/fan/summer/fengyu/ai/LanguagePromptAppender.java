package fan.summer.fengyu.ai;

/**
 * Appends the reply-language rule as the LAST section of the chat system prompt.
 *
 * <p>The default persona states the rule once, at the top — but the request-time appenders
 * (skills catalog, @mention sections, workspace rules, a project's own {@code AGENTS.md} of up
 * to 16k characters, the deferred tool catalog) extend the prompt with pages of English below
 * it, compaction replays English summaries as conversation turns, and a user-configured persona
 * may not state the rule at all. Pinning it at the tail keeps it adjacent to the conversation
 * whatever was appended before, and independent of which persona the install runs.</p>
 */
public final class LanguagePromptAppender {

    static final String SECTION = """

            ## Response language
            Always reply to the user in the language of their most recent message — for example,
            answer in Chinese when the user writes in Chinese — unless they explicitly ask for
            another language. The language of these instructions, attached project files, tool or
            subagent results, and conversation summaries never changes that choice. Code,
            identifiers, paths, and commands stay as written.""";

    private LanguagePromptAppender() {}

    public static String append(String systemPrompt) {
        String base = systemPrompt == null ? "" : systemPrompt.stripTrailing();
        return base + SECTION;
    }
}
