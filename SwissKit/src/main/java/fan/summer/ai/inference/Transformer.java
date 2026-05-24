package fan.summer.ai.inference;

import fan.summer.ai.model.*;
import fan.summer.ai.tensor.ArrayFloatTensor;
import fan.summer.ai.tensor.FloatTensor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transformer inference engine — single-token forward pass.
 * Implements LLaMA-style architecture: Pre-norm attention + SwiGLU FFN.
 */
public class Transformer {

    private static final Logger log = LoggerFactory.getLogger(Transformer.class);

    private final ModelConfig config;
    private final ModelWeights weights;
    private final KVCache kvCache;

    // Reusable buffers to avoid allocation per token
    private final float[] xb;      // [dim] — current hidden state
    private final float[] xb2;     // [dim] — scratch for second norm
    private final float[] q;       // [nHeads * headSize]
    private final float[] k;       // [kvDim]
    private final float[] v;       // [kvDim]
    private final float[] xbAtt;   // [dim] — attention output
    private final float[] attOut;  // [dim] — post-WO attention
    private final float[] gate;    // [hiddenDim] — SwiGLU gate
    private final float[] up;      // [hiddenDim] — SwiGLU up
    private final float[] ffnOut;  // [dim] — FFN output
    private final float[] logits;  // [vocabSize]

    public Transformer(GGUFModel model) {
        this.config = model.getConfig();
        this.weights = new ModelWeights(model);
        this.kvCache = new KVCache(config);

        this.xb = new float[config.dim];
        this.xb2 = new float[config.dim];
        this.q = new float[config.nHeads * config.headSize];
        this.k = new float[config.kvDim];
        this.v = new float[config.kvDim];
        this.xbAtt = new float[config.dim];
        this.attOut = new float[config.dim];
        this.gate = new float[config.hiddenDim];
        this.up = new float[config.hiddenDim];
        this.ffnOut = new float[config.dim];
        this.logits = new float[config.vocabSize];

        Runtime runtime = Runtime.getRuntime();
        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long kvMB = kvCache.memoryBytes() / 1024 / 1024;
        log.info("Transformer initialized: kvCache={}MB, heap={}MB", kvMB, usedMB);
    }

    /**
     * Run a single forward pass for the given token at the given position.
     * Returns the logits array (vocabSize). The caller should NOT modify it.
     */
    public float[] forward(int tokenId, int pos) {
        // ── Step 1: Token Embedding ──────────────────────────────
        FloatTensor embedding = weights.getTokenEmbedding();
        if (embedding == null) throw new RuntimeException("Missing token embedding weights");
        int dim = config.dim;
        for (int i = 0; i < dim; i++) {
            xb[i] = embedding.get(tokenId * dim + i);
        }

        // ── Step 2: Transformer layers ──────────────────────────
        for (int layer = 0; layer < config.nLayers; layer++) {

            // 2a: Pre-attention RMSNorm
            FloatTensor attNorm = weights.getAttentionNorm(layer);
            rmsnorm(xb, attNorm, xb2, dim);

            // 2b: QKV projection
            FloatTensor wq = weights.getWQ(layer);
            FloatTensor wk = weights.getWK(layer);
            FloatTensor wv = weights.getWV(layer);

            matmulInto(wq, xb2, q, config.nHeads * config.headSize, dim);
            matmulInto(wk, xb2, k, config.kvDim, dim);
            matmulInto(wv, xb2, v, config.kvDim, dim);

            // 2c: RoPE
            applyRoPE(q, k, pos);

            // 2d: Store K, V into cache
            kvCache.storeKey(layer, pos, k);
            kvCache.storeValue(layer, pos, v);

            // 2e: Multi-head attention (GQA)
            attention(q, pos, layer);

            // 2f: Output projection + residual
            FloatTensor wo = weights.getWO(layer);
            matmulInto(wo, xbAtt, attOut, dim, config.nHeads * config.headSize);
            for (int i = 0; i < dim; i++) {
                xb[i] += attOut[i];
            }

            // 2g: Pre-FFN RMSNorm
            FloatTensor ffnNorm = weights.getFFNNorm(layer);
            rmsnorm(xb, ffnNorm, xb2, dim);

            // 2h: SwiGLU FFN
            FloatTensor w1 = weights.getW1(layer);
            FloatTensor w3 = weights.getW3(layer);
            FloatTensor w2 = weights.getW2(layer);

            matmulInto(w1, xb2, gate, config.hiddenDim, dim);
            matmulInto(w3, xb2, up, config.hiddenDim, dim);

            // SwiGLU: gate = silu(gate) * up
            for (int i = 0; i < config.hiddenDim; i++) {
                gate[i] = silu(gate[i]) * up[i];
            }

            matmulInto(w2, gate, ffnOut, dim, config.hiddenDim);
            for (int i = 0; i < dim; i++) {
                xb[i] += ffnOut[i];
            }
        }

        // ── Step 3: Final RMSNorm ───────────────────────────────
        FloatTensor finalNorm = weights.getFinalNormWeight();
        rmsnorm(xb, finalNorm, xb, dim);

        // ── Step 4: Output logits ───────────────────────────────
        FloatTensor outputW = weights.getOutputWeight();
        matmulInto(outputW, xb, logits, config.vocabSize, dim);

        return logits;
    }

    public KVCache getKvCache() { return kvCache; }

    // ── Math primitives ─────────────────────────────────────

    private void rmsnorm(float[] x, FloatTensor weight, float[] out, int dim) {
        float ss = 0f;
        for (int i = 0; i < dim; i++) {
            ss += x[i] * x[i];
        }
        float invRms = 1f / (float) Math.sqrt(ss / dim + 1e-5f);
        for (int i = 0; i < dim; i++) {
            out[i] = weight.get(i) * invRms * x[i];
        }
    }

    private void applyRoPE(float[] q, float[] k, int pos) {
        applyRoPEVectors(q, config.nHeads, pos);
        applyRoPEVectors(k, config.nKVHeads, pos);
    }

    private void applyRoPEVectors(float[] vec, int nHeads, int pos) {
        int headSize = config.headSize;
        for (int h = 0; h < nHeads; h++) {
            for (int i = 0; i < headSize; i += 2) {
                double freq = 1.0 / Math.pow(config.ropeTheta, (double) i / headSize);
                double angle = pos * freq;
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);

                int off = h * headSize + i;
                float v0 = vec[off], v1 = vec[off + 1];
                vec[off]     = v0 * cos - v1 * sin;
                vec[off + 1] = v0 * sin + v1 * cos;
            }
        }
    }

    private void attention(float[] q, int pos, int layer) {
        int headSize = config.headSize;
        int kvRatio = config.nHeads / config.nKVHeads;
        float scale = 1f / (float) Math.sqrt(headSize);

        java.util.Arrays.fill(xbAtt, 0f);

        for (int h = 0; h < config.nHeads; h++) {
            int kvHead = h / kvRatio;
            int qOff = h * headSize;

            // Compute attention scores
            float[] scores = new float[pos + 1];
            for (int t = 0; t <= pos; t++) {
                float score = 0f;
                for (int i = 0; i < headSize; i++) {
                    score += q[qOff + i] * kvCache.getKey(layer, t, kvHead * headSize + i);
                }
                scores[t] = score * scale;
            }

            // Softmax
            MathUtil.softmax(scores, 0, pos + 1);

            // Weighted sum of V
            for (int t = 0; t <= pos; t++) {
                float w = scores[t];
                for (int i = 0; i < headSize; i++) {
                    xbAtt[qOff + i] += w * kvCache.getValue(layer, t, kvHead * headSize + i);
                }
            }
        }
    }

    private void matmulInto(FloatTensor weight, float[] input, float[] output, int outDim, int inDim) {
        java.util.stream.IntStream.range(0, outDim).parallel().forEach(o -> {
            float sum = 0f;
            for (int i = 0; i < inDim; i++) {
                sum += weight.get(o * inDim + i) * input[i];
            }
            output[o] = sum;
        });
    }

    private float silu(float x) {
        return x * (1f / (1f + (float) Math.exp(-x)));
    }
}
