package fan.summer.ai.tensor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Heap-backed FP32 tensor for intermediate computation results.
 */
public class ArrayFloatTensor extends FloatTensor {

    private static final Logger log = LoggerFactory.getLogger(ArrayFloatTensor.class);

    private final float[] data;

    public ArrayFloatTensor(int size) {
        this.data = new float[size];
        log.debug("ArrayFloatTensor created with size={}", size);
    }

    public ArrayFloatTensor(float[] data) {
        this.data = data;
        log.debug("ArrayFloatTensor wrapping array of size={}", data.length);
    }

    public float[] getData() { return data; }

    @Override public int size() { return data.length; }
    @Override public float get(int index) { return data[index]; }
    @Override public void set(int index, float value) { data[index] = value; }

    @Override
    public float dot(int offset, FloatTensor other, int otherOffset, int len) {
        if (other instanceof ArrayFloatTensor a) {
            log.debug("ArrayFloatTensor.dot: fast path, len={}", len);
            float[] od = a.data;
            float sum = 0f;
            for (int i = 0; i < len; i++) {
                sum += data[offset + i] * od[otherOffset + i];
            }
            return sum;
        }
        log.debug("ArrayFloatTensor.dot: generic path, len={}", len);
        return super.dot(offset, other, otherOffset, len);
    }

    @Override
    public void copyTo(float[] dest, int destOffset, int len) {
        System.arraycopy(data, 0, dest, destOffset, len);
    }
}
