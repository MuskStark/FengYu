package fan.summer.ai.inference;

/**
 * Internal utility class for mathematical operations used in AI inference.
 * This class is not part of the public API and should not be used externally.
 */
final class MathUtil {

    private MathUtil() {}

    /**
     * Applies the softmax function in-place to a subarray of a float array.
     * Uses the numerically stable softmax formulation:
     * {@code softmax(i) = exp(arr[i] - max) / sum(exp(arr[j] - max))}.
     *
     * @param arr    the float array to modify in-place
     * @param offset the starting index of the subarray (inclusive)
     * @param len    the number of elements in the subarray
     */
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
