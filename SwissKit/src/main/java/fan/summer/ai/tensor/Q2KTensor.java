package fan.summer.ai.tensor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Q2_K quantized tensor: 2-bit K-quant with 256-element super-blocks.
 * <p>
 * Block layout (84 bytes per 256 elements):
 *   d:      F16 super-block scale (2 bytes, offset 0)
 *   dmin:   F16 super-block minimum (2 bytes, offset 2)
 *   scales: 16 bytes of packed sub-block scales (offset 4)
 *   qs:     64 bytes of quantized values (offset 20)
 * <p>
 * Dequantization ported from llama.cpp dequantize_row_q2_K.
 * Each group of 64 elements consumes 32 qs bytes and 2 scale bytes:
 *   - 32 elements from low 2 bits of qs (scale=sc&0xF, min=m>>4)
 *   - 32 elements from high 4 bits of qs (scale=sc>>4, min=m&0xF)
 */
public class Q2KTensor extends FloatTensor {

    private static final Logger log = LoggerFactory.getLogger(Q2KTensor.class);
    private static final int QK_K = 256;
    // struct layout: d(2) + dmin(2) + scales(16) + qs(64) = 84 bytes
    private static final int BLOCK_BYTES = 84;
    private static final int OFFSET_D = 0;
    private static final int OFFSET_DMIN = 2;
    private static final int OFFSET_SCALES = 4;
    private static final int OFFSET_QS = 20;

    private final ByteBuffer buffer;
    private final int size;
    private final int nBlocks;

    public Q2KTensor(ByteBuffer buffer, int size) {
        this.buffer = buffer;
        this.size = size;
        this.nBlocks = (size + QK_K - 1) / QK_K;
        log.debug("Q2KTensor created: size={}, nBlocks={}", size, nBlocks);
    }

    @Override public int size() { return size; }

    @Override
    public float get(int index) {
        int blockIdx = index / QK_K;
        int withinBlock = index % QK_K;
        int blockOffset = blockIdx * BLOCK_BYTES;

        float d = Float.float16ToFloat(buffer.getShort(blockOffset + OFFSET_D));
        float min = Float.float16ToFloat(buffer.getShort(blockOffset + OFFSET_DMIN));

        // Each group of 64 elements uses 32 qs bytes and 2 scale bytes
        int groupIdx = withinBlock / 64;
        int withinGroup = withinBlock % 64;

        int scaleIdx = OFFSET_SCALES + groupIdx * 2;
        int sc = buffer.get(blockOffset + scaleIdx) & 0xFF;
        int m = buffer.get(blockOffset + scaleIdx + 1) & 0xFF;

        int qsBase = blockOffset + OFFSET_QS + groupIdx * 32;

        if (withinGroup < 32) {
            // Low 2 bits of qs: scale from sc lower nibble, min from m upper nibble
            int q = buffer.get(qsBase + withinGroup) & 0xFF;
            return d * (sc & 0xF) * (q & 3) - min * (m >> 4);
        } else {
            // High 4 bits of qs: scale from sc upper nibble, min from m lower nibble
            int localIdx = withinGroup - 32;
            int q = buffer.get(qsBase + localIdx) & 0xFF;
            return d * (sc >> 4) * (q >> 4) - min * (m & 0xF);
        }
    }

    @Override
    public void set(int index, float value) {
        throw new UnsupportedOperationException("Read-only quantized tensor");
    }
}
