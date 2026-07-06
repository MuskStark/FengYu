package fan.summer.zhiflow.ai.tensor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Q8_0 quantized tensor: 8-bit quantization with 32-element blocks.
 * Block layout: [scale(f16, 2 bytes)] [quants(32 bytes, one byte per element)]
 * Total: 34 bytes per block, dequantize: x = scale * quant (int8)
 */
public class Q8_0Tensor extends FloatTensor {

    private static final Logger log = LoggerFactory.getLogger(Q8_0Tensor.class);
    private static final int BLOCK_SIZE = 32;
    private static final int BLOCK_BYTES = 2 + BLOCK_SIZE; // 34

    private final ByteBuffer buffer;
    private final int size;

    public Q8_0Tensor(ByteBuffer buffer, int size) {
        this.buffer = buffer;
        this.size = size;
        log.debug("Q8_0Tensor created: size={}", size);
    }

    @Override public int size() { return size; }

    @Override
    public float get(int index) {
        int blockIdx = index / BLOCK_SIZE;
        int withinBlock = index % BLOCK_SIZE;
        int blockOffset = blockIdx * BLOCK_BYTES;

        short scaleRaw = buffer.getShort(blockOffset);
        float scale = Float.float16ToFloat(scaleRaw);

        byte quant = buffer.get(blockOffset + 2 + withinBlock);
        return scale * quant;
    }

    @Override
    public void set(int index, float value) {
        throw new UnsupportedOperationException("Read-only quantized tensor");
    }
}
