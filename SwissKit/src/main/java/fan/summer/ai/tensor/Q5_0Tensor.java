package fan.summer.ai.tensor;

import java.nio.ByteBuffer;

/**
 * Q5_0 quantized tensor: 5-bit quantization with 32-element blocks.
 * <p>
 * Block layout (22 bytes per 32 elements):
 *   d:  F16 scale (2 bytes, offset 0)
 *   qh: uint32 upper bits (4 bytes, offset 2) — one bit per element
 *   qs: 16 bytes (offset 6) — 4 bits per element
 * <p>
 * Elements 0..15: lower nibble of qs[j] + bit j of qh, minus 16, times d
 * Elements 16..31: upper nibble of qs[j] + bit (j+16) of qh, minus 16, times d
 * <p>
 * Ported from llama.cpp dequantize_row_q5_0.
 */
public class Q5_0Tensor extends FloatTensor {

    private static final int BLOCK_SIZE = 32;
    private static final int BLOCK_BYTES = 22;

    private final ByteBuffer buffer;
    private final int size;

    public Q5_0Tensor(ByteBuffer buffer, int size) {
        this.buffer = buffer;
        this.size = size;
    }

    @Override public int size() { return size; }

    @Override
    public float get(int index) {
        int blockIdx = index / BLOCK_SIZE;
        int withinBlock = index % BLOCK_SIZE;
        int blockOffset = blockIdx * BLOCK_BYTES;

        float d = Float.float16ToFloat(buffer.getShort(blockOffset));
        int qh = buffer.getInt(blockOffset + 2);

        int j = withinBlock < 16 ? withinBlock : withinBlock - 16;
        int qsByte = buffer.get(blockOffset + 6 + j) & 0xFF;

        if (withinBlock < 16) {
            int xh = ((qh >> j) << 4) & 0x10;
            int val = ((qsByte & 0x0F) | xh) - 16;
            return val * d;
        } else {
            int xh = (qh >>> (j + 12)) & 0x10;
            int val = ((qsByte >> 4) | xh) - 16;
            return val * d;
        }
    }

    @Override
    public void set(int index, float value) {
        throw new UnsupportedOperationException("Read-only quantized tensor");
    }
}
