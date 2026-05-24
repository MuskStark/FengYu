package fan.summer.ai.nativejni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sampling parameters for token generation via llama.cpp JNI.
 * Builder-style: {@code new GenerateParams().temperature(0.8).topP(0.95).maxTokens(1024)}
 */
public class GenerateParams {

    private static final Logger log = LoggerFactory.getLogger(GenerateParams.class);

    private int maxNewTokens = 512;
    private float temperature = 0.7f;
    private float topP = 0.9f;
    private float repeatPenalty = 1.1f;
    private long seed = -1;

    public GenerateParams maxTokens(int n) { this.maxNewTokens = n; return this; }
    public GenerateParams temperature(float t) { this.temperature = t; return this; }
    public GenerateParams topP(float p) { this.topP = p; return this; }
    public GenerateParams repeatPenalty(float p) { this.repeatPenalty = p; return this; }
    public GenerateParams seed(long s) { this.seed = s; return this; }

    public int getMaxNewTokens() { return maxNewTokens; }
    public float getTemperature() { return temperature; }
    public float getTopP() { return topP; }
    public float getRepeatPenalty() { return repeatPenalty; }
    public long getSeed() { return seed; }

    @Override
    public String toString() {
        return "GenerateParams{maxNewTokens=" + maxNewTokens + ", temperature=" + temperature +
               ", topP=" + topP + ", repeatPenalty=" + repeatPenalty + ", seed=" + seed + "}";
    }
}
