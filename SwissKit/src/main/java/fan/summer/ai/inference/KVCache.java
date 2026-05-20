package fan.summer.ai.inference;

import fan.summer.ai.model.ModelConfig;

import java.util.Arrays;

/**
 * Key-Value cache for transformer inference.
 * Stores historical K/V vectors to avoid recomputation during autoregressive generation.
 */
public class KVCache {

    private final int nLayers;
    private final int maxSeqLen;
    private final int kvDim;

    // Layout: [layer][seq_pos * kvDim + kv_head * headSize + offset]
    private final float[][] keyCache;
    private final float[][] valueCache;

    public KVCache(ModelConfig config) {
        this.nLayers = config.nLayers;
        this.maxSeqLen = config.maxSeqLen;
        this.kvDim = config.kvDim;

        this.keyCache = new float[nLayers][maxSeqLen * kvDim];
        this.valueCache = new float[nLayers][maxSeqLen * kvDim];
    }

    public void storeKey(int layer, int pos, float[] k) {
        System.arraycopy(k, 0, keyCache[layer], pos * kvDim, kvDim);
    }

    public void storeValue(int layer, int pos, float[] v) {
        System.arraycopy(v, 0, valueCache[layer], pos * kvDim, kvDim);
    }

    public float getKey(int layer, int pos, int i) {
        return keyCache[layer][pos * kvDim + i];
    }

    public float getValue(int layer, int pos, int i) {
        return valueCache[layer][pos * kvDim + i];
    }

    public float[] getKeySlice(int layer, int pos) {
        float[] slice = new float[kvDim];
        System.arraycopy(keyCache[layer], pos * kvDim, slice, 0, kvDim);
        return slice;
    }

    public float[] getValueSlice(int layer, int pos) {
        float[] slice = new float[kvDim];
        System.arraycopy(valueCache[layer], pos * kvDim, slice, 0, kvDim);
        return slice;
    }

    /**
     * Reset the entire cache (e.g. when starting a new conversation).
     */
    public void reset() {
        for (int l = 0; l < nLayers; l++) {
            Arrays.fill(keyCache[l], 0f);
            Arrays.fill(valueCache[l], 0f);
        }
    }

    /**
     * Estimate memory usage in bytes.
     */
    public long memoryBytes() {
        return 2L * nLayers * maxSeqLen * kvDim * 4L;
    }
}
