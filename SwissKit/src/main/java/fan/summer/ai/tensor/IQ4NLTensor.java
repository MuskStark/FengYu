package fan.summer.ai.tensor;

import java.nio.ByteBuffer;

/**
 * IQ4_NL quantized tensor: 4-bit non-linear quantization with 32-element blocks.
 * Block layout: [scale(f16, 2 bytes)] [quants(16 bytes, 4 bits per element)]
 * Total: 18 bytes per block of 32.
 * Uses a non-linear lookup table for dequantization.
 */
public class IQ4NLTensor extends FloatTensor {

    private static final int BLOCK_SIZE = 32;
    private static final int BLOCK_BYTES = 2 + BLOCK_SIZE / 2; // 18

    // Non-linear quantization values from llama.cpp kvalues_iq4nl
    private static final float[] KVALUES = {
        -127.f, -104.f, -83.f, -65.f, -49.f, -35.f, -22.f, -10.f,
          1.f,   13.f,  25.f,  38.f,  53.f,  69.f,  89.f, 114.f
    };

    private final ByteBuffer buffer;
    private final int size;

    public IQ4NLTensor(ByteBuffer buffer, int size) {
        this.buffer = buffer;
        this.size = size;
    }

    @Override public int size() { return size; }

    @Override
    public float get(int index) {
        int blockIdx = index / BLOCK_SIZE;
        int withinBlock = index % BLOCK_SIZE;
        int blockOffset = blockIdx * BLOCK_BYTES;

        if (blockOffset + 1 >= buffer.limit()) {
            throw new IndexOutOfBoundsException(
                "IQ4_NL: index=" + index + " blockIdx=" + blockIdx + " blockOffset=" + blockOffset +
                " bufferLimit=" + buffer.limit() + " declaredSize=" + size);
        }

        short scaleRaw = buffer.getShort(blockOffset);
        float d = Float.float16ToFloat(scaleRaw);

        int byteIdx = blockOffset + 2 + withinBlock / 2;
        byte b = buffer.get(byteIdx);
        int nibble = (withinBlock % 2 == 0) ? (b & 0x0F) : ((b >> 4) & 0x0F);

        return d * KVALUES[nibble];
    }

    @Override
    public void set(int index, float value) {
        throw new UnsupportedOperationException("Read-only quantized tensor");
    }

    @Override
    public float dot(int offset, FloatTensor other, int otherOffset, int len) {
        float sum = 0f;
        for (int i = 0; i < len; i++) {
            sum += get(offset + i) * other.get(otherOffset + i);
        }
        return sum;
    }
}
