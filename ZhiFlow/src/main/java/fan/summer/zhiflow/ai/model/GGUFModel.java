package fan.summer.zhiflow.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Represents a loaded GGUF model: metadata, tensor descriptors, and mapped weight data.
 */
public class GGUFModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GGUFModel.class);

    private final Map<String, Object> metadata;
    private final Map<String, TensorInfo> tensorIndex;
    private final ByteBuffer dataBuffer;
    private final long dataOffset;
    private final ModelConfig config;
    private final String modelName;

    public GGUFModel(Map<String, Object> metadata, List<TensorInfo> tensorInfos,
                     ByteBuffer buffer, long dataOffset, String fileName) {
        this.metadata = metadata;
        this.dataBuffer = buffer;
        this.dataOffset = dataOffset;
        this.modelName = fileName;

        this.tensorIndex = new LinkedHashMap<>();
        for (TensorInfo ti : tensorInfos) {
            tensorIndex.put(ti.name, ti);
        }

        this.config = buildConfig();
    }

    public ModelConfig getConfig() { return config; }
    public String getModelName() { return modelName; }
    public Map<String, Object> getMetadata() { return metadata; }

    @SuppressWarnings("unchecked")
    public <T> T getMeta(String key, T defaultValue) {
        Object v = metadata.get(key);
        return v != null ? (T) v : defaultValue;
    }

    public String getMetaString(String key, String defaultValue) {
        Object v = metadata.get(key);
        return v instanceof String ? (String) v : defaultValue;
    }

    /**
     * Get a read-only ByteBuffer slice containing the tensor data.
     */
    public ByteBuffer getTensorData(String tensorName) {
        TensorInfo info = tensorIndex.get(tensorName);
        if (info == null) return null;

        long absOffset = dataOffset + info.offset;
        int size = computeTensorBytes(info);
        long remaining = dataBuffer.limit() - absOffset;
        if (remaining < size) {
            log.error("Tensor '{}' data truncated: needs {} bytes at offset {} but only {} bytes available (type={}, dims={})",
                      tensorName, size, absOffset, remaining, info.type, info.dimensions);
            size = (int) remaining;
        }
        var slice = dataBuffer.slice((int) absOffset, size);
        slice.order(dataBuffer.order());
        return slice.asReadOnlyBuffer();
    }

    public TensorInfo getTensorInfo(String name) {
        return tensorIndex.get(name);
    }

    private int computeTensorBytes(TensorInfo info) {
        if (info.type.blockSize == 1) {
            return (int) (info.elementCount() * info.type.typeSize);
        }
        long nBlocks = (info.elementCount() + info.type.blockSize - 1) / info.type.blockSize;
        return (int) (nBlocks * info.type.typeSize);
    }

    private ModelConfig buildConfig() {
        // Read from metadata with multiple key prefix support
        int dim = getIntMeta("embedding_length", 0);
        int hiddenDim = getIntMeta("feed_forward_length", 0);
        int nLayers = getIntMeta("block_count", 0);
        int nHeads = getIntMeta("attention.head_count", 0);
        int nKVHeads = getIntMeta("attention.head_count_kv", nHeads);
        int vocabSize = getIntMeta("vocab_size", 0);
        int maxSeqLen = getIntMeta("context_length", 4096);
        float ropeTheta = getFloatMeta("rope.freq_base", 10000.0f);

        if (vocabSize == 0) {
            Object tokens = metadata.get("tokenizer.ggml.tokens");
            if (tokens instanceof String[] arr) vocabSize = arr.length;
        }

        // Validate dim against actual tensor dimensions (output_norm.weight is always [dim])
        TensorInfo normInfo = tensorIndex.get("output_norm.weight");
        if (normInfo != null && normInfo.elementCount() > 0) {
            int tensorDim = (int) normInfo.elementCount();
            if (dim != 0 && dim != tensorDim) {
                log.warn("Metadata dim={} doesn't match output_norm.weight size={}; using tensor-derived value", dim, tensorDim);
            }
            dim = tensorDim;
        }

        // Derive nLayers from tensor names if metadata is wrong
        if (nLayers == 0) {
            for (String name : tensorIndex.keySet()) {
                if (name.startsWith("blk.") && name.endsWith(".attn_norm.weight")) {
                    int layer = Integer.parseInt(name.substring(4, name.indexOf('.', 4)));
                    nLayers = Math.max(nLayers, layer + 1);
                }
            }
        }

        // Derive hiddenDim from FFN gate weight if metadata is wrong
        if (hiddenDim == 0) {
            TensorInfo w1 = tensorIndex.get("blk.0.ffn_gate.weight");
            if (w1 != null) hiddenDim = (int) w1.elementCount() / dim;
        }
        if (hiddenDim == 0) hiddenDim = dim * 4;

        // Derive nHeads from Q weight if metadata is wrong
        if (nHeads == 0) {
            TensorInfo wq = tensorIndex.get("blk.0.attn_q.weight");
            if (wq != null) {
                // Q weight is [nHeads * headSize, dim], element count = nHeads * headSize * dim
                // headSize = dim / nHeads, so elementCount = dim * dim / dim... no.
                // Actually Q weight shape is [nHeads * headSize, dim], elements = nHeads * headSize * dim
                // For Gemma: Q weight is [dim, dim] for full attention
                // We need to figure out nHeads from the weight shape
                // Common head sizes: 64, 128, 256
                long qElements = wq.elementCount();
                // qElements = nHeads * headSize * dim, and headSize = dim / nHeads
                // So qElements = dim * dim
                // That doesn't help. Let's try common divisors.
                int headSize = 64; // most common
                if (dim % 128 == 0 && dim >= 2048) headSize = 128;
                nHeads = dim / headSize;
            }
        }
        if (nHeads == 0) nHeads = 32;

        // Derive headSize from Q weight tensor shape.
        // GGUF stores 2D tensors as [ne0, ne1] where ne0=columns(inDim), ne1=rows(outDim).
        // Q weight: ne0=dim, ne1=nHeads*headDim. So headDim = ne1 / nHeads.
        int headSize = dim / nHeads;
        TensorInfo wq = tensorIndex.get("blk.0.attn_q.weight");
        if (wq != null && wq.dimensions.size() >= 2) {
            long qOutDim = wq.dimensions.get(1);
            if (nHeads > 0) {
                int derived = (int) (qOutDim / nHeads);
                if (derived > 0) headSize = derived;
            }
        }
        // Also try metadata
        int metaHeadSize = getIntMeta("attention.head_dim", 0);
        if (metaHeadSize > 0) headSize = metaHeadSize;

        // Derive nKVHeads from K weight shape vs Q weight shape
        if (nKVHeads == 0 || nKVHeads == nHeads) {
            TensorInfo wk = tensorIndex.get("blk.0.attn_k.weight");
            if (wq != null && wk != null && headSize > 0) {
                long kElem = wk.elementCount();
                // K weight: [nKVHeads * headSize, dim]
                nKVHeads = (int) (kElem / ((long) headSize * dim));
                if (nKVHeads <= 0) nKVHeads = 1;
            }
        }
        if (nKVHeads <= 0) nKVHeads = Math.max(1, nHeads / 4);

        log.info("Model config: dim={}, hiddenDim={}, layers={}, heads={}, kvHeads={}, headSize={}, vocab={}, seqLen={}, ropeTheta={}",
                 dim, hiddenDim, nLayers, nHeads, nKVHeads, headSize, vocabSize, maxSeqLen, ropeTheta);

        return new ModelConfig(dim, hiddenDim, nLayers, nHeads, nKVHeads, vocabSize, maxSeqLen, ropeTheta, headSize);
    }

    /** Try multiple metadata key prefixes (llama, gemma3, gemma, general) */
    private int getIntMeta(String suffix, int defaultVal) {
        for (String prefix : List.of("llama.", "gemma3.", "gemma.", "")) {
            String key = prefix + suffix;
            Object val = metadata.get(key);
            if (val instanceof Number n) return n.intValue();
        }
        return defaultVal;
    }

    private float getFloatMeta(String suffix, float defaultVal) {
        for (String prefix : List.of("llama.", "gemma3.", "gemma.", "")) {
            String key = prefix + suffix;
            Object val = metadata.get(key);
            if (val instanceof Number n) return n.floatValue();
        }
        return defaultVal;
    }

    // ── Lifecycle ─────────────────────────────────────────────

    /**
     * Releases the memory-mapped weight buffer. Java exposes no public API to unmap a
     * {@link java.nio.MappedByteBuffer}, so this best-effort path uses
     * {@code sun.misc.Unsafe.invokeCleaner} when reachable — otherwise switching models
     * in the pure-Java backend piles up multi-gigabyte mappings until GC runs. If Unsafe
     * is unavailable (restrictive JDK / SecurityManager) it degrades gracefully and
     * falls back to ordinary GC. Safe to call multiple times. (The native backend is
     * unaffected: it runs in a child process whose mappings die with that process.)
     */
    @Override
    public void close() {
        unmapBuffer(dataBuffer);
    }

    /** Package-private so the no-throw contract can be unit-tested without a model. */
    static void unmapBuffer(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) return;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            java.lang.reflect.Method invokeCleaner =
                unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
            invokeCleaner.invoke(unsafe, buffer);
        } catch (Throwable e) {
            log.debug("Could not force-unmap direct buffer via sun.misc.Unsafe; relying on GC", e);
        }
    }
}
