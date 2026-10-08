package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

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
    private final AiToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final AiConfigService aiConfigService;
    private final ChatToolApprovalGate toolApprovalGate;
    /** Provider registry (nullable in focused tests — falls back to the legacy mode switch). */
    private final fan.summer.fengyu.ai.provider.ProviderRegistryService providerRegistry;

    /** Optional rollout recorder — present in the full app context, absent in focused tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private fan.summer.fengyu.ai.session.AiRolloutService rolloutService;

    @org.springframework.beans.factory.annotation.Autowired
    public BackendReactivator(AiModeService aiMode,
                              AiToolRegistry toolRegistry,
                              SkillRegistry skillRegistry,
                              AiConfigService aiConfigService,
                              ChatToolApprovalGate toolApprovalGate,
                              fan.summer.fengyu.ai.provider.ProviderRegistryService providerRegistry) {
        this.aiMode = aiMode;
        this.toolRegistry = toolRegistry;
        this.toolCallbacks = new ToolCallback[0];
        this.skillRegistry = skillRegistry;
        this.aiConfigService = aiConfigService;
        this.toolApprovalGate = toolApprovalGate;
        this.providerRegistry = providerRegistry;
    }

    /** Compatibility constructor for focused tests that inject a fixed callback catalog. */
    public BackendReactivator(AiModeService aiMode,
                              ToolCallback[] toolCallbacks,
                              SkillRegistry skillRegistry,
                              AiConfigService aiConfigService,
                              ChatToolApprovalGate toolApprovalGate) {
        this.aiMode = aiMode;
        this.toolRegistry = null;
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : new ToolCallback[0];
        this.skillRegistry = skillRegistry;
        this.aiConfigService = aiConfigService;
        this.toolApprovalGate = toolApprovalGate;
        this.providerRegistry = null;
    }

    /**
     * Rebuild the active backend from the latest DB config and switch to it.
     * Registry-first: the active {@link ProviderDefinition} dispatches on its protocol
     * (custom OpenAI/Anthropic-compatible instances included); the legacy {@code ai.mode}
     * switch remains as the fallback when no registry is wired (focused tests) or the
     * active id cannot be resolved.
     */
    public void reactivate() {
        if (providerRegistry != null) {
            var active = providerRegistry.activeProvider();
            if (active.isPresent()) {
                activateDefinition(active.get());
                return;
            }
        }
        legacyReactivate();
    }

    private void activateDefinition(fan.summer.fengyu.ai.provider.ProviderDefinition definition) {
        log.info("Reactivating AI backend, provider={}/{} ({})",
                definition.id(), definition.model(), definition.protocol());
        switch (definition.protocol()) {
            case OPENAI_CHAT, ANTHROPIC_MESSAGES -> activateBackend(
                    SpringAiCloudBackend.create(definition, providerRegistry.resolveApiKey(definition.id())),
                    definition.id());
            case OLLAMA -> activateBackend(new OllamaLocalBackend(
                    definition.baseUrl(), definition.model()), definition.id());
        }
    }

    /**
     * Local (Ollama) mode. {@link OllamaLocalBackend} has a no-arg constructor that
     * reads the model tag from DB; {@code ChatModel} is resolved lazily in
     * {@code loadModel} (triggered by {@code AiController} before first chat).
     */
    private void activateLocal() {
        activateBackend(new OllamaLocalBackend(), "local");
    }

    private void legacyReactivate() {
        String mode = aiConfigService.getAiMode();
        log.info("Reactivating AI backend (legacy), mode={}", mode);
        switch (mode) {
            case "openai" -> activateBackend(SpringAiCloudBackend.openAi(
                aiConfigService.getAiOpenAiEndpoint(),
                aiConfigService.getAiOpenAiApiKey(),
                aiConfigService.getAiOpenAiModel()), mode);
            case "anthropic" -> activateBackend(SpringAiCloudBackend.anthropic(
                aiConfigService.getAiAnthropicEndpoint(),
                aiConfigService.getAiAnthropicApiKey(),
                aiConfigService.getAiAnthropicModel()), mode);
            case "deepseek" -> activateBackend(SpringAiCloudBackend.deepSeek(
                aiConfigService.getAiDeepSeekEndpoint(),
                aiConfigService.getAiDeepSeekApiKey(),
                aiConfigService.getAiDeepSeekModel()), mode);
            default -> activateLocal();
        }
    }

    private void activateBackend(ChatBackend backend, String mode) {
        List<ToolCallback> callbacks = callbacks();
        if (backend instanceof SpringAiCloudBackend cloud) {
            cloud.setToolCallbacks(callbacks);
            if (toolRegistry != null) cloud.setToolCallbackSupplier(toolRegistry::callbacks);
            cloud.setToolApprovalGate(toolApprovalGate);
            cloud.setSkillRegistry(skillRegistry);
        } else if (backend instanceof OllamaLocalBackend local) {
            local.setToolCallbacks(callbacks);
            if (toolRegistry != null) local.setToolCallbackSupplier(toolRegistry::callbacks);
            local.setToolApprovalGate(toolApprovalGate);
            local.setSkillRegistry(skillRegistry);
        }
        if (rolloutService != null && backend instanceof SpringAiCloudBackend cloud) {
            cloud.setRolloutService(rolloutService);
        }
        if (rolloutService != null && backend instanceof OllamaLocalBackend local) {
            local.setRolloutService(rolloutService);
        }
        log.info("Wired {} tool callback(s) into backend {}", callbacks.size(), mode);
        aiMode.switchMode(mode, backend);
    }

    private List<ToolCallback> callbacks() {
        return toolRegistry == null ? Arrays.asList(toolCallbacks) : toolRegistry.callbacks();
    }
}
