package fan.summer.ai.tensor;

import java.nio.ByteBuffer;

/**
 * Tensor backed by a ByteBuffer slice from the GGUF memory-mapped file.
 * Supports F32 and F16 types.
 */
public class MappedTensor extends FloatTensor {

    private final ByteBuffer buffer;
    private final int size;
    private final boolean fp16;

    public MappedTensor(ByteBuffer buffer, int size, boolean fp16) {
        this.buffer = buffer;
        this.size = size;
        this.fp16 = fp16;
    }

    @Override public int size() { return size; }

    @Override
    public float get(int index) {
        if (fp16) {
            int pos = index * 2;
            if (pos + 1 >= buffer.limit()) {
                throw new IndexOutOfBoundsException(
                    "F16 tensor: index=" + index + " bytePos=" + pos + " bufferLimit=" + buffer.limit() + " declaredSize=" + size);
            }
            return Float.float16ToFloat(buffer.getShort(pos));
        }
        int pos = index * 4;
        if (pos + 3 >= buffer.limit()) {
            throw new IndexOutOfBoundsException(
                "F32 tensor: index=" + index + " bytePos=" + pos + " bufferLimit=" + buffer.limit() + " declaredSize=" + size);
        }
        return buffer.getFloat(pos);
    }

    @Override
    public void set(int index, float value) {
        throw new UnsupportedOperationException("Read-only tensor");
    }
}
