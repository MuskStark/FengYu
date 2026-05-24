package fan.summer.ai.tensor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.IntStream;

/**
 * Abstract base for all tensor types. Provides element access and dot product.
 */
public abstract class FloatTensor {

    private static final Logger log = LoggerFactory.getLogger(FloatTensor.class);

    public abstract int size();
    public abstract float get(int index);
    public abstract void set(int index, float value);

    /**
     * Dot product of this[offset..offset+size) with other[otherOffset..otherOffset+size).
     */
    public float dot(int offset, FloatTensor other, int otherOffset, int len) {
        float sum = 0f;
        for (int i = 0; i < len; i++) {
            sum += get(offset + i) * other.get(otherOffset + i);
        }
        return sum;
    }

    /**
     * Matrix-vector multiply: out = this[rows x cols] * vec[cols].
     * out[i] = dot of row i with vec.
     */
    public void matmul(FloatTensor vec, float[] out, int rows, int cols) {
        log.debug("matmul: rows={}, cols={}, vecSize={}", rows, cols, vec.size());
        IntStream.range(0, rows).parallel().forEach(i -> {
            out[i] = dot(i * cols, vec, 0, cols);
        });
    }

    public void copyTo(float[] dest, int destOffset, int len) {
        for (int i = 0; i < len; i++) {
            dest[destOffset + i] = get(i);
        }
    }
}
