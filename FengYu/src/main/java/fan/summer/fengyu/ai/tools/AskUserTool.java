package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.util.JsonHelper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code ask_user} tool: a structured question card the model can raise when it is
 * genuinely blocked on a decision only the user can make (approach choice, ambiguity with
 * materially different outcomes, destructive-scope confirmation beyond the permission
 * system). The turn blocks on the SAME gate/timeout machinery as tool approvals, but a
 * question never gates a tool — it is itself the interaction.
 *
 * <p>Deliberately conservative surface (the ZCode {@code AskUserQuestion} shape, V1):
 * 1–4 questions per call, each with 2–4 options plus the frontend's automatic "Other"
 * free-text escape. Only surfaces in the interactive main conversation — subagents and
 * non-chat executions (no approval gate in their tool context) get an immediate
 * "not available" result instead of blocking on a question nobody can see.</p>
 */
@Component
public class AskUserTool implements FengYuTool, ToolEffectProvider {

    /** The registered tool name (surfaces that cannot render questions hide it by this). */
    public static final String NAME = "ask_user";

    /** One selectable option of a question. */
    public record OptionInput(
            @ToolParam(description = "Concise option label (1-5 words).") String label,
            @ToolParam(required = false,
                       description = "One-line explanation of what this choice implies.") String description) {}

    /** One question; an automatic free-text 'Other' choice is added by the UI. */
    public record QuestionInput(
            @ToolParam(description = "The complete question, ending with a question mark.") String question,
            @ToolParam(required = false,
                       description = "Very short label chip for the question (max 12 chars).") String header,
            @ToolParam(description = "2-4 distinct, mutually exclusive options."
                    + " Never add an 'Other' option — the user always has one.") List<OptionInput> options,
            @ToolParam(required = false,
                       description = "Allow selecting several options (default false).") Boolean multiSelect) {}

    @Override
    public ToolEffect effectFor(String toolName) {
        return NAME.equals(toolName) ? ToolEffect.READ : null;
    }

    @Tool(name = NAME,
          description = "Ask the user structured questions when you are blocked on a decision "
                  + "that is genuinely theirs to make: multiple valid approaches with materially "
                  + "different outcomes, an ambiguity you cannot resolve from the code, or an "
                  + "irreversible action outside what the permission system already gates. Do NOT "
                  + "use it for choices with an obvious default, for questions the code or docs "
                  + "can answer, or to ask permission to keep working. 1-4 questions per call; "
                  + "each has 2-4 options and the user always has a free-text 'Other'. Answers "
                  + "come back as this tool's result.")
    public String askUser(
            @ToolParam(description = "The questions to ask (1-4).") List<QuestionInput> questions) {
        ChatToolApprovalGate gate = ToolApprovalContext.gate();
        fan.summer.fengyu.ai.AiStreamCallback callback = ToolApprovalContext.callback();
        if (gate == null || callback == null) {
            return JsonHelper.toJson(Map.of(
                    "success", false,
                    "error", "ask_user is only available in an interactive conversation "
                            + "(no user to ask here) — proceed with your best-supported choice "
                            + "and state the assumption in your answer"));
        }
        if (questions == null || questions.isEmpty()) {
            return error("At least one question is required");
        }
        if (questions.size() > 4) {
            return error("At most 4 questions per call; split or drop the least important ones");
        }
        List<Map<String, Object>> payloadQuestions = new ArrayList<>();
        for (QuestionInput question : questions) {
            if (question == null || question.question() == null || question.question().isBlank()) {
                return error("Each question needs non-empty question text");
            }
            List<Map<String, Object>> options = new ArrayList<>();
            if (question.options() != null) {
                for (OptionInput option : question.options()) {
                    if (option == null || option.label() == null || option.label().isBlank()) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("label", option.label());
                    if (option.description() != null && !option.description().isBlank()) {
                        entry.put("description", option.description());
                    }
                    options.add(entry);
                }
            }
            if (options.size() < 2) {
                return error("Each question needs at least 2 non-empty options"
                        + " (or rephrase as a free-text question in your message instead)");
            }
            if (options.size() > 4) options = options.subList(0, 4);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("question", question.question());
            if (question.header() != null && !question.header().isBlank()) {
                entry.put("header", truncateHeader(question.header()));
            }
            entry.put("options", options);
            entry.put("multiSelect", Boolean.TRUE.equals(question.multiSelect()));
            payloadQuestions.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questions", payloadQuestions);
        Map<String, Object> answers = gate.awaitQuestion(payload, callback);

        // Timeout (empty answers) is a normal outcome the model must handle, not an error.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        if (answers.isEmpty()) {
            result.put("answered", false);
            result.put("note", "The user did not answer within the timeout. Proceed with your "
                    + "best-supported choice and clearly state the assumption.");
        } else {
            result.put("answered", true);
            result.put("answers", answers.get("answers") != null ? answers.get("answers") : answers);
        }
        return JsonHelper.toJson(result);
    }

    /** Max 12 chars, but never splitting a surrogate pair: backing off one char when the
     *  cut would orphan a high half keeps the JSON-serialized header well-formed. */
    static String truncateHeader(String header) {
        if (header.length() <= 12) return header;
        int cut = 12;
        if (Character.isHighSurrogate(header.charAt(cut - 1))) cut--;
        return header.substring(0, cut);
    }

    private static String error(String message) {
        return JsonHelper.toJson(Map.of("success", false, "error", message));
    }
}
