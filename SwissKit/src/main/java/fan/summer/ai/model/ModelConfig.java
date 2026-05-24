package fan.summer.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Model hyperparameters extracted from GGUF metadata.
 */
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    public final int dim;
    public final int hiddenDim;
    public final int nLayers;
    public final int nHeads;
    public final int nKVHeads;
    public final int vocabSize;
    public final int maxSeqLen;
    public final float ropeTheta;
    public final int headSize;
    public final int kvDim;

    public ModelConfig(int dim, int hiddenDim, int nLayers, int nHeads, int nKVHeads,
                       int vocabSize, int maxSeqLen, float ropeTheta, int headSize) {
        this.dim = dim;
        this.hiddenDim = hiddenDim;
        this.nLayers = nLayers;
        this.nHeads = nHeads;
        this.nKVHeads = nKVHeads;
        this.vocabSize = vocabSize;
        this.maxSeqLen = maxSeqLen;
        this.ropeTheta = ropeTheta;
        this.headSize = headSize;
        this.kvDim = nKVHeads * headSize;
        log.info("ModelConfig created: dim={}, hiddenDim={}, nLayers={}, nHeads={}, nKVHeads={}, vocabSize={}, maxSeqLen={}, ropeTheta={}, headSize={}, kvDim={}",
                 dim, hiddenDim, nLayers, nHeads, nKVHeads, vocabSize, maxSeqLen, ropeTheta, headSize, kvDim);
    }

    @Override
    public String toString() {
        return "ModelConfig{dim=" + dim + ", nLayers=" + nLayers +
               ", nHeads=" + nHeads + ", nKVHeads=" + nKVHeads +
               ", vocabSize=" + vocabSize + ", maxSeqLen=" + maxSeqLen + '}';
    }
}
