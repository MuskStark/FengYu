package fan.summer.fengyu.ai.skill;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes {@code /<skill-id> [arguments]} typed at the start of a user message — the
 * deterministic (model-independent) counterpart to the description-matched
 * {@code skill} tool invocation.
 *
 * <p>The command IS the message (ACP-style): the user's literal text travels to the backend
 * unchanged, and the turn pipeline decides here whether it names an enabled skill. Only a
 * message that starts with {@code /} followed by a skill-id-shaped token counts; a slash
 * anywhere else — paths, dates, prose — is ordinary text, so the grammar is fully backward
 * compatible: an id that matches nothing leaves the turn byte-for-byte as it is today.
 *
 * <p>Id shape mirrors {@link SkillManifest} validation ({@code [a-z0-9]+(?:[.-][a-z0-9]+)+})
 * loosely enough to also cover the hyphenated builtin ids ({@code fengyu-plugin-dev}): a
 * leading alphanumeric then id characters, capped at 64. Matching is case-insensitive
 * (the input folds to lowercase) because the composer may insert the id via different
 * surfaces; the injected guidance always cites the registry's canonical id.
 *
 * @since 4.1.0
 */
public final class ExplicitSkillInvocationParser {

    /**
     * {@code /name} at message start (after trimming), optional whitespace, then the rest of
     * the message verbatim as the arguments. The name forbids slashes so {@code /a/b} (a
     * path) can never parse as a command.
     */
    private static final Pattern INVOCATION_PATTERN =
            Pattern.compile("^/([a-zA-Z0-9][a-zA-Z0-9._-]{0,63})(?:\\s+([\\s\\S]+))?$");

    private ExplicitSkillInvocationParser() {}

    /** What the parser concluded about one user message. */
    public sealed interface Result {

        /**
         * Not a skill invocation: the message does not start with {@code /id}, or the token
         * names no discovered skill. The turn proceeds exactly as without this feature —
         * bare custom-command names land here too (their expansion is a frontend concern).
         */
        record NotACommand() implements Result {}

        /** The message names an enabled skill; its body is injected for this turn. */
        record Invoked(Skill skill, String arguments) implements Result {}

        /**
         * The message names a discovered but disabled skill. Nothing is loaded; a short
         * guidance note is injected so the model can tell the user instead of silently
         * ignoring an explicit request.
         */
        record Disabled(String id) implements Result {}
    }

    /**
     * @param lastUserContent the last user message of the turn (may be {@code null}/blank)
     * @param registry        the live skill registry; {@code null} yields {@link Result.NotACommand}
     * @return the parse verdict, never null. A registry that throws is contained here —
     *         a misbehaving skill subsystem must degrade to plain text, never break the turn
     *         (the same contract {@link SkillPromptAppender} holds for the catalog).
     */
    public static Result parse(String lastUserContent, SkillRegistry registry) {
        if (registry == null || lastUserContent == null) return new Result.NotACommand();
        Matcher matcher = INVOCATION_PATTERN.matcher(lastUserContent.strip());
        if (!matcher.matches()) return new Result.NotACommand();
        String id = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
        Optional<Skill> found;
        try {
            found = registry.find(id);
        } catch (RuntimeException registryFailure) {
            return new Result.NotACommand();
        }
        if (found.isEmpty()) return new Result.NotACommand();
        Skill skill = found.get();
        try {
            if (!registry.isEnabled(skill)) return new Result.Disabled(skill.id());
        } catch (RuntimeException registryFailure) {
            return new Result.NotACommand();
        }
        return new Result.Invoked(skill, matcher.group(2) == null ? "" : matcher.group(2).strip());
    }
}
