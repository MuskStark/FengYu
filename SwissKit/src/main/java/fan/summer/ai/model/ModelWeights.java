package fan.summer.ai.model;

import fan.summer.ai.tensor.FloatTensor;
import fan.summer.ai.tensor.TensorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Provides lazy tensor access to model weights by name.
 * Tensors are read directly from the memory-mapped GGUF buffer.
 */
public class ModelWeights {

    private static final Logger log = LoggerFactory.getLogger(ModelWeights.class);

    private final GGUFModel model;

    public ModelWeights(GGUFModel model) {
        this.model = model;
    }

    /**
     * Get a tensor by its GGUF tensor name. Returns null if not found.
     */
    public FloatTensor get(String name) {
        TensorInfo info = model.getTensorInfo(name);
        if (info == null) return null;
        ByteBuffer data = model.getTensorData(name);
        if (data == null) return null;
        int elemCount = (int) info.elementCount();
        FloatTensor tensor = TensorFactory.create(data, info.type, elemCount);
        log.debug("Loaded tensor: name={} type={} dims={} elements={} bufferBytes={}",
                  name, info.type, info.dimensions, elemCount, data.limit());
        return tensor;
    }

    /**
     * Get a tensor, trying multiple name variants (different GGUF naming conventions).
     */
    public FloatTensor getAny(String... names) {
        for (String name : names) {
            FloatTensor t = get(name);
            if (t != null) return t;
        }
        return null;
    }

    /**
     * Get tensor as a float array. More efficient for repeated random access.
     */
    public float[] getFloatArray(String name) {
        FloatTensor t = get(name);
        if (t == null) return null;
        float[] arr = new float[t.size()];
        t.copyTo(arr, 0, t.size());
        return arr;
    }

    /**
     * Get embedding table — token embedding matrix [vocabSize x dim].
     */
    public FloatTensor getTokenEmbedding() {
        return getAny("token_embd.weight", "embed_tokens.weight");
    }

    /**
     * Get output projection — lm_head [vocabSize x dim].
     */
    public FloatTensor getOutputWeight() {
        FloatTensor t = getAny("output.weight", "lm_head.weight");
        if (t != null) return t;
        // Some models share embedding and output weights
        return getTokenEmbedding();
    }

    /**
     * Get the final RMS normalization weight.
     */
    public FloatTensor getFinalNormWeight() {
        return getAny("output_norm.weight", "model.norm.weight");
    }

    // Per-layer weights

    public FloatTensor getAttentionNorm(int layer) {
        return getAny(
            "blk." + layer + ".attn_norm.weight",
            "model.layers." + layer + ".input_layernorm.weight"
        );
    }

    public FloatTensor getFFNNorm(int layer) {
        return getAny(
            "blk." + layer + ".ffn_norm.weight",
            "model.layers." + layer + ".post_attention_layernorm.weight"
        );
    }

    public FloatTensor getWQ(int layer) {
        return getAny(
            "blk." + layer + ".attn_q.weight",
            "model.layers." + layer + ".self_attn.q_proj.weight"
        );
    }

    public FloatTensor getWK(int layer) {
        return getAny(
            "blk." + layer + ".attn_k.weight",
            "model.layers." + layer + ".self_attn.k_proj.weight"
        );
    }

    public FloatTensor getWV(int layer) {
        return getAny(
            "blk." + layer + ".attn_v.weight",
            "model.layers." + layer + ".self_attn.v_proj.weight"
        );
    }

    public FloatTensor getWO(int layer) {
        return getAny(
            "blk." + layer + ".attn_output.weight",
            "model.layers." + layer + ".self_attn.o_proj.weight"
        );
    }

    public FloatTensor getW1(int layer) { // gate
        return getAny(
            "blk." + layer + ".ffn_gate.weight",
            "model.layers." + layer + ".mlp.gate_proj.weight"
        );
    }

    public FloatTensor getW2(int layer) { // down
        return getAny(
            "blk." + layer + ".ffn_down.weight",
            "model.layers." + layer + ".mlp.down_proj.weight"
        );
    }

    public FloatTensor getW3(int layer) { // up
        return getAny(
            "blk." + layer + ".ffn_up.weight",
            "model.layers." + layer + ".mlp.up_proj.weight"
        );
    }
}
