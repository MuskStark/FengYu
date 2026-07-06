package fan.summer.zhiflow.ai.inference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Token sampler with temperature and top-p (nucleus) sampling.
 */
public class Sampler {

    private static final Logger log = LoggerFactory.getLogger(Sampler.class);

    private final float temperature;
    private final float topP;
    private final Random random;

    public Sampler(float temperature, float topP) {
        this(temperature, topP, ThreadLocalRandom.current().nextLong());
    }

    public Sampler(float temperature, float topP, long seed) {
        this.temperature = temperature;
        this.topP = topP;
        this.random = seed >= 0 ? new Random(seed) : new Random();
        log.info("Sampler created: temperature={}, topP={}, seed={}", temperature, topP, seed);
    }

    /**
     * Sample the next token from logits.
     */
    public int sample(float[] logits) {
        int vocabSize = logits.length;

        // Greedy
        if (temperature == 0f) {
            int token = argmax(logits);
            log.debug("Sampler.greedy: vocabSize={}, selectedToken={}", vocabSize, token);
            return token;
        }

        // Apply temperature
        float[] probs = new float[vocabSize];
        for (int i = 0; i < vocabSize; i++) {
            probs[i] = logits[i] / temperature;
        }

        // Softmax
        softmax(probs);

        // Top-p sampling
        if (topP < 1.0f) {
            int token = sampleTopP(probs);
            log.debug("Sampler.topP: vocabSize={}, selectedToken={}", vocabSize, token);
            return token;
        }

        // Multinomial
        int token = sampleMultinomial(probs);
        log.debug("Sampler.multinomial: vocabSize={}, selectedToken={}", vocabSize, token);
        return token;
    }

    private int argmax(float[] arr) {
        int best = 0;
        float bestVal = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > bestVal) {
                bestVal = arr[i];
                best = i;
            }
        }
        return best;
    }

    private void softmax(float[] probs) {
        MathUtil.softmax(probs, 0, probs.length);
    }

    private int sampleTopP(float[] probs) {
        Integer[] indices = new Integer[probs.length];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        java.util.Arrays.sort(indices, (a, b) -> Float.compare(probs[b], probs[a]));

        float cumProb = 0f;
        int lastIdx = indices.length - 1;
        for (int i = 0; i < indices.length; i++) {
            cumProb += probs[indices[i]];
            if (cumProb >= topP) {
                lastIdx = i;
                break;
            }
        }

        // Re-normalize within cutoff and sample
        float r = random.nextFloat() * cumProb;
        float cdf = 0f;
        for (int i = 0; i <= lastIdx; i++) {
            cdf += probs[indices[i]];
            if (r < cdf) return indices[i];
        }
        return indices[lastIdx];
    }

    private int sampleMultinomial(float[] probs) {
        float r = random.nextFloat();
        float cdf = 0f;
        for (int i = 0; i < probs.length; i++) {
            cdf += probs[i];
            if (r < cdf) return i;
        }
        return probs.length - 1;
    }
}
