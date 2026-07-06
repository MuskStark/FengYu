package fan.summer.zhiflow.ai.tools;

/**
 * Qwen3-specific tool-calling adapter.
 *
 * <p>Owns the two things that are genuinely Qwen3-specific on top of the shared
 * {@link ToolCallParser} (Hermes regex) and the {@link ThinkingStreamSegmenter}:
 * <ul>
 *   <li>the Hermes tool-call directive injected into the system prompt, which reliably
 *       triggers {@code <tool_call>} emission even though the host's chat template is
 *       a simplified ChatML re-implementation;</li>
 *   <li>the thinking-mode toggle ({@code /no_think} suppresses Qwen3 reasoning).</li>
 * </ul>
 *
 * <p>Thinking defaults to ON — it materially improves tool-calling judgment on a 4B
 * model. The toggle is a future hook (e.g. a latency-sensitive setting).
 */
public final class Qwen3Adapter {

    private static final String HERMES_DIRECTIVE =
        "\n\nWhen you need to call a tool, emit exactly one block per call:\n" +
        "<tool_call>\n{\"name\": \"<tool_name>\", \"arguments\": {<param>: <value>}}\n</tool_call>\n" +
        "Do not wrap tool calls in markdown code fences.";

    private boolean thinkingEnabled = true;

    public boolean isThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(boolean enabled) { this.thinkingEnabled = enabled; }

    /**
     * Append the Hermes directive (and, if thinking is disabled, {@code /no_think})
     * to the base system prompt.
     *
     * @param base the base system prompt; {@code null} is treated as empty
     * @return the augmented system prompt, never {@code null}
     */
    public String augmentSystemPrompt(String base) {
        String out = (base == null ? "" : base) + HERMES_DIRECTIVE;
        if (!thinkingEnabled) out += "\n\n/no_think";
        return out;
    }
}
