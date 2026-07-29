package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Reactivates the AI backend from the latest DB config. Shared by
 * {@link AiBackendInitializer} (startup) and {@code AiConfigController} (hot-swap).
 *
 * <p>Reads the current {@code ai.mode} via {@link AiConfigService}, rebuilds the
 * matching backend with fresh endpoint/key/model values, injects the discovered
 * {@code ToolCallback[]} bean, and hands it to {@link AiModeService#switchMode}.
 *
 * <p><b>Why not refresh Spring {@code ChatModel} beans?</b> {@link SpringAiCloudBackend}
 * caches the {@code ChatModel} in a {@code final} field at construction (never
 * re-resolves the bean at chat time). So the only way to pick up new config is to
 * rebuild the backend object itself — which is exactly what this does.
 *
 * <p><b>Failure softening:</b> a cloud backend with a blank endpoint/key/model is
 * registered with {@code isReady()==false} (see {@code SpringAiCloudBackend.resolveModel});
 * {@code reactivate} never throws, so {@code PUT /api/ai/config} always returns 200.
 */
@Component
public class BackendReactivator {

    private static final Logger log = LoggerFactory.getLogger(BackendReactivator.class);

    private final AiModeService aiMode;
    private final ToolCallback[] toolCallbacks;
    private final SkillRegistry skillRegistry;
    private final AiConfigService aiConfigService;
    private final ChatToolApprovalGate toolApprovalGate;

    public BackendReactivator(AiModeService aiMode,
                              ToolCallback[] toolCallbacks,
                              SkillRegistry skillRegistry,
                              AiConfigService aiConfigService,
                              ChatToolApprovalGate toolApprovalGate) {
        this.aiMode = aiMode;
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : new ToolCallback[0];
        this.skillRegistry = skillRegistry;
        this.aiConfigService = aiConfigService;
        this.toolApprovalGate = toolApprovalGate;
    }

    /** Rebuild the active backend from the latest DB config and switch to it. */
    public void reactivate() {
        String mode = aiConfigService.getAiMode();
        log.info("Reactivating AI backend, mode={}", mode);
        switch (mode) {
            case "openai" -> activate(SpringAiCloudBackend.openAi(
                aiConfigService.getAiOpenAiEndpoint(),
                aiConfigService.getAiOpenAiApiKey(),
                aiConfigService.getAiOpenAiModel()), mode);
            case "anthropic" -> activate(SpringAiCloudBackend.anthropic(
                aiConfigService.getAiAnthropicEndpoint(),
                aiConfigService.getAiAnthropicApiKey(),
                aiConfigService.getAiAnthropicModel()), mode);
            case "deepseek" -> activate(SpringAiCloudBackend.deepSeek(
                aiConfigService.getAiDeepSeekEndpoint(),
                aiConfigService.getAiDeepSeekApiKey(),
                aiConfigService.getAiDeepSeekModel()), mode);
            default -> activateLocal();
        }
    }

    private void activate(SpringAiCloudBackend backend, String mode) {
        backend.setToolCallbacks(Arrays.asList(toolCallbacks));
        backend.setToolApprovalGate(toolApprovalGate);
        backend.setSkillRegistry(skillRegistry);
        log.info("Wired {} tool callback(s) into {} backend", toolCallbacks.length, backend.provider());
        aiMode.switchMode(mode, backend);
    }

    /**
     * Local (Ollama) mode. {@link OllamaLocalBackend} has a no-arg constructor that
     * reads the model tag from DB; {@code ChatModel} is resolved lazily in
     * {@code loadModel} (triggered by {@code AiController} before first chat).
     */
    private void activateLocal() {
        OllamaLocalBackend backend = new OllamaLocalBackend();
        backend.setToolCallbacks(Arrays.asList(toolCallbacks));
        backend.setToolApprovalGate(toolApprovalGate);
        backend.setSkillRegistry(skillRegistry);
        aiMode.switchMode("local", backend);
    }
}
