package fan.summer.ai.inference;

import fan.summer.ai.model.*;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * High-level inference API: load model, generate tokens, stream responses.
 */
public class LlamaRunner {

    private static final Logger log = LoggerFactory.getLogger(LlamaRunner.class);

    private GGUFModel model;
    private Transformer transformer;
    private Tokenizer tokenizer;
    private ChatTemplate chatTemplate;

    private volatile boolean generating;
    private volatile boolean cancelled;

    public void load(String modelPath) throws Exception {
        unload();
        long t0 = System.nanoTime();

        model = GGUFReader.load(modelPath);
        transformer = new Transformer(model);
        tokenizer = new Tokenizer(model);
        chatTemplate = new ChatTemplate(model);

        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("Model loaded: {} ({}ms), config={}", model.getModelName(), ms, model.getConfig());
    }

    public void unload() {
        if (model != null) {
            try {
                model.close();   // release the mmap'd weight buffer promptly on unload
            } catch (Exception e) {
                log.warn("Error closing GGUF model: {}", e.getMessage());
            }
        }
        transformer = null;
        model = null;
        tokenizer = null;
        chatTemplate = null;
    }

    public boolean isReady() {
        return model != null && transformer != null && tokenizer != null;
    }

    public String getModelName() {
        return model != null ? model.getModelName() : null;
    }

    public ModelConfig getConfig() {
        return model != null ? model.getConfig() : null;
    }

    /**
     * Build a prompt string from chat history using the model's chat template.
     */
    public String buildPrompt(List<AiChatMessage> history, String systemPrompt) {
        if (chatTemplate == null) throw new IllegalStateException("No model loaded");
        return chatTemplate.buildPrompt(history, systemPrompt);
    }

    /**
     * Generate a response from a pre-built prompt string with streaming.
     */
    public String generate(String prompt, float temperature, float topP, int maxTokens,
                           TokenCallback callback) {
        if (!isReady()) throw new IllegalStateException("No model loaded");

        generating = true;
        cancelled = false;

        try {
            int[] tokens = tokenizer.encode(prompt, true, false);
            Sampler sampler = new Sampler(temperature, topP);
            var response = new StringBuilder();
            long genStart = System.nanoTime();
            int genTokens = 0;

            // Prefill: process all prompt tokens
            for (int pos = 0; pos < tokens.length - 1; pos++) {
                if (cancelled) break;
                transformer.forward(tokens[pos], pos);
            }

            if (cancelled) {
                // Cancelled during prefill: emit an empty completion so the caller's
                // "generating" state resolves — mirrors the decode-cancel path, which
                // falls through to onComplete() at the end of the loop below. Without
                // this, cancelling while a long prompt is still being prefilled leaves
                // the UI stuck in the generating state.
                if (callback != null) {
                    callback.onComplete(response.toString(), genTokens, 0);
                }
                return response.toString();
            }

            // Start generation from last prompt token
            int pos = tokens.length - 1;
            float[] logits = transformer.forward(tokens[pos], pos);
            int nextToken = sampler.sample(logits);

            while (genTokens < maxTokens && nextToken != tokenizer.getEosToken() && !cancelled) {
                String piece = tokenizer.decode(nextToken);
                int prevLen = response.length();
                response.append(piece);

                // Text-level stop sequence detection — needed because chat-template
                // turn markers (<|im_end|>, <|eot_id|>, etc.) often are NOT the same
                // token as tokenizer.ggml.eos_token_id, so the loop above never trips
                // on them and the model rolls into a hallucinated next turn.
                int stopIdx = StopDetector.findStop(response);
                if (stopIdx >= 0) {
                    response.setLength(stopIdx);
                    int safeLen = stopIdx - prevLen;
                    if (callback != null && safeLen > 0) {
                        callback.onToken(piece.substring(0, Math.min(piece.length(), safeLen)));
                    }
                    break;
                }

                if (callback != null) {
                    callback.onToken(piece);
                }
                genTokens++;

                pos++;
                logits = transformer.forward(nextToken, pos);
                nextToken = sampler.sample(logits);
            }

            long genMs = (System.nanoTime() - genStart) / 1_000_000;
            double tokPerSec = genMs > 0 ? genTokens * 1000.0 / genMs : 0;

            if (callback != null) {
                callback.onComplete(response.toString(), genTokens, tokPerSec);
            }

            return response.toString();

        } finally {
            generating = false;
        }
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isGenerating() { return generating; }

    public void resetCache() {
        if (transformer != null) {
            transformer.getKvCache().reset();
        }
    }

    public interface TokenCallback {
        void onToken(String fragment);
        default void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {}
    }
}
