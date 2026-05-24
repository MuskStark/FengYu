package fan.summer.ai.inference;

final class MathUtil {

    private MathUtil() {}

    static void softmax(float[] arr, int offset, int len) {
        float max = Float.NEGATIVE_INFINITY;
        int end = offset + len;
        for (int i = offset; i < end; i++) {
            if (arr[i] > max) max = arr[i];
        }
        float sum = 0f;
        for (int i = offset; i < end; i++) {
            arr[i] = (float) Math.exp(arr[i] - max);
            sum += arr[i];
        }
        float invSum = 1f / sum;
        for (int i = offset; i < end; i++) {
            arr[i] *= invSum;
        }
    }
}
