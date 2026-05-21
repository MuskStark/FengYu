package fan.summer.ai.tensor;

import java.nio.ByteBuffer;

/**
 * Q4_0 quantized tensor: 4-bit quantization with 32-element blocks.
 * Block layout: [scale(f16, 2 bytes)] [quants(16 bytes)]
 * Total: 18 bytes per block, dequantize: x = scale * (quant - 8)
 */
public class Q4_0Tensor extends FloatTensor {

    private static final int BLOCK_SIZE = 32;
    private static final int BLOCK_BYTES = 2 + BLOCK_SIZE / 2; // 18

    private final ByteBuffer buffer;
    private final int size;
    private final int nBlocks;

    public Q4_0Tensor(ByteBuffer buffer, int size) {
        this.buffer = buffer;
        this.size = size;
        this.nBlocks = (size + BLOCK_SIZE - 1) / BLOCK_SIZE;
    }

    @Override public int size() { return size; }

    @Override
    public float get(int index) {
        int blockIdx = index / BLOCK_SIZE;
        int withinBlock = index % BLOCK_SIZE;
        int blockOffset = blockIdx * BLOCK_BYTES;

        short scaleRaw = buffer.getShort(blockOffset);
        float scale = Float.float16ToFloat(scaleRaw);

        int byteIdx = blockOffset + 2 + withinBlock / 2;
        byte b = buffer.get(byteIdx);
        int quant = (withinBlock % 2 == 0)
                ? (b & 0x0F)
                : ((b >> 4) & 0x0F);

        return scale * (quant - 8);
    }

    @Override
    public void set(int index, float value) {
        throw new UnsupportedOperationException("Read-only quantized tensor");
    }

    /**
     * Optimized dot product: accumulates block by block with direct buffer access.
     */
    @Override
    public float dot(int offset, FloatTensor other, int otherOffset, int len) {
        float sum = 0f;
        // Simple scalar fallback — the block-optimized path is complex;
        // rely on matmul parallelism for speed instead.
        for (int i = 0; i < len; i++) {
            sum += get(offset + i) * other.get(otherOffset + i);
        }
        return sum;
    }
}
