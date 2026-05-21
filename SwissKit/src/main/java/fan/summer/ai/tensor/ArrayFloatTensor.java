package fan.summer.ai.tensor;

/**
 * Heap-backed FP32 tensor for intermediate computation results.
 */
public class ArrayFloatTensor extends FloatTensor {

    private final float[] data;

    public ArrayFloatTensor(int size) {
        this.data = new float[size];
    }

    public ArrayFloatTensor(float[] data) {
        this.data = data;
    }

    public float[] getData() { return data; }

    @Override public int size() { return data.length; }
    @Override public float get(int index) { return data[index]; }
    @Override public void set(int index, float value) { data[index] = value; }

    @Override
    public float dot(int offset, FloatTensor other, int otherOffset, int len) {
        if (other instanceof ArrayFloatTensor a) {
            float[] od = a.data;
            float sum = 0f;
            for (int i = 0; i < len; i++) {
                sum += data[offset + i] * od[otherOffset + i];
            }
            return sum;
        }
        return super.dot(offset, other, otherOffset, len);
    }

    @Override
    public void copyTo(float[] dest, int destOffset, int len) {
        System.arraycopy(data, 0, dest, destOffset, len);
    }
}
