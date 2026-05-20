package fan.summer.ai.service;

import fan.summer.ai.inference.LlamaRunner;
import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.ai.AiService;
import fan.summer.api.ai.AiServiceException;
import fan.summer.api.ai.AiStreamCallback;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of AiService backed by the local LlamaRunner inference engine.
 */
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    private final LlamaRunner runner;

    public AiServiceImpl() {
        this.runner = new LlamaRunner();
    }

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        try {
            log.info("Loading AI model: {}", modelPath);
            runner.load(modelPath.toString());
            log.info("AI model loaded successfully: {}", runner.getModelName());
        } catch (Exception e) {
            throw new AiServiceException("Failed to load model: " + e.getMessage(), e);
        }
    }

    @Override
    public void unloadModel() {
        runner.unload();
    }

    @Override
    public boolean isReady() {
        return runner.isReady();
    }

    @Override
    public Optional<String> getModelName() {
        return Optional.ofNullable(runner.getModelName());
    }

    @Override
    public long getMemoryUsage() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, 0.7f, 0.9f, 512, callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!runner.isReady()) {
            callback.onError(new AiServiceException("No model loaded"));
            return;
        }
        if (runner.isGenerating()) {
            callback.onError(new AiServiceException("Generation already in progress"));
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                runner.resetCache();
                String systemPrompt = SwissKitJSettingUi.getAiSystemPrompt();
                String prompt = runner.buildPrompt(history, systemPrompt);
                runner.generate(prompt, temperature, topP, maxTokens, new LlamaRunner.TokenCallback() {
                    @Override
                    public void onToken(String fragment) {
                        Platform.runLater(() -> callback.onToken(fragment));
                    }

                    @Override
                    public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                        Platform.runLater(() -> callback.onComplete(fullResponse, tokensGenerated, tokensPerSecond));
                    }
                });
            } catch (Exception e) {
                log.error("Generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }

    @Override
    public void cancelGeneration() {
        runner.cancel();
    }

    @Override
    public boolean isGenerating() {
        return runner.isGenerating();
    }
}
