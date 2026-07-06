package fan.summer.zhiflow.ai.nativejni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parameters for loading a GGUF model via llama.cpp JNI.
 * Builder-style: {@code new ModelParams().ctxLength(4096).gpuLayers(35)}
 */
public class ModelParams {

    private static final Logger log = LoggerFactory.getLogger(ModelParams.class);

    private String modelPath;
    private int nCtx = 4096;
    private int nGpuLayers = 0;
    private int nThreads = Runtime.getRuntime().availableProcessors();
    private boolean flashAttn = false;

    public ModelParams modelPath(String path) { this.modelPath = path; return this; }
    public ModelParams ctxLength(int nCtx) { this.nCtx = nCtx; return this; }
    public ModelParams gpuLayers(int n) { this.nGpuLayers = n; return this; }
    public ModelParams threads(int n) { this.nThreads = n; return this; }
    public ModelParams flashAttention(boolean on) { this.flashAttn = on; return this; }

    public String getModelPath() { return modelPath; }
    public int getCtxLength() { return nCtx; }
    public int getGpuLayers() { return nGpuLayers; }
    public int getThreads() { return nThreads; }
    public boolean isFlashAttention() { return flashAttn; }

    @Override
    public String toString() {
        return "ModelParams{modelPath=" + modelPath + ", nCtx=" + nCtx +
               ", nGpuLayers=" + nGpuLayers + ", nThreads=" + nThreads +
               ", flashAttn=" + flashAttn + "}";
    }
}
