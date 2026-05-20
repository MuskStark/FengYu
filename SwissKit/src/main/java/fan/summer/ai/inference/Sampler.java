package fan.summer.ai.inference;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Token sampler with temperature and top-p (nucleus) sampling.
 */
public class Sampler {

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
    }

    /**
     * Sample the next token from logits.
     */
    public int sample(float[] logits) {
        int vocabSize = logits.length;

        // Greedy
        if (temperature == 0f) {
            return argmax(logits);
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
            return sampleTopP(probs);
        }

        // Multinomial
        return sampleMultinomial(probs);
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
        float max = Float.NEGATIVE_INFINITY;
        for (float p : probs) if (p > max) max = p;

        float sum = 0f;
        for (int i = 0; i < probs.length; i++) {
            probs[i] = (float) Math.exp(probs[i] - max);
            sum += probs[i];
        }
        float invSum = 1f / sum;
        for (int i = 0; i < probs.length; i++) {
            probs[i] *= invSum;
        }
    }

    private int sampleTopP(float[] probs) {
        // Create index array sorted by probability descending
        int[] indices = new int[probs.length];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        // Partial sort: find top-p cutoff
        // Use simple selection for small vocab sizes
        indices = java.util.Arrays.stream(indices)
            .boxed()
            .sorted((a, b) -> Float.compare(probs[b], probs[a]))
            .mapToInt(Integer::intValue)
            .toArray();

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
