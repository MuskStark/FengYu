package fan.summer.fengyu.ai.provider;

/**
 * The wire protocol a provider speaks — the single code-level dispatch point of the
 * provider registry. Vendors are data; protocols are code: every OpenAI-compatible
 * endpoint (OpenAI, DeepSeek, Zhipu, Kimi, DashScope, custom gateways, …) is
 * {@link #OPENAI_CHAT}, every Anthropic-compatible endpoint is
 * {@link #ANTHROPIC_MESSAGES}, and Ollama keeps its dedicated local backend.
 */
public enum Protocol {
    OPENAI_CHAT,
    ANTHROPIC_MESSAGES,
    OLLAMA
}
