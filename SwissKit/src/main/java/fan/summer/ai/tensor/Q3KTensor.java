package fan.summer.ai.tensor;

import java.nio.ByteBuffer;

/**
 * Q3_K quantized tensor: 2-bit quantization with 256-element super-blocks.
 * <p>
 * Super-block layout (110 bytes per 256 elements):
 *   hmask:  32 bytes (offset 0)  — sign bits, one per element
 *   qs:     64 bytes (offset 32) — 2-bit quant values (packed 4 per byte)
 *   scales: 12 bytes (offset 96) — packed 6-bit scales
 *   d:      F16 scale (2 bytes, offset 108)
 * <p>
 * Scales are unpacked from 12 bytes into 16 signed values via uint32 rearrangement.
 * Each super-block is divided into 2 halves × 4 j-groups × 2 sub-groups × 16 elements.
 * <p>
 * Ported from llama.cpp dequantize_row_q3_K.
 */
public class Q3KTensor extends FloatTensor {

    private static final int SUPER_BLOCK_SIZE = 256;
    private static final int SUPER_BLOCK_BYTES = 110;

    private static final int KMASK1 = 0x03030303;
    private static final int KMASK2 = 0x0F0F0F0F;

    private final ByteBuffer buffer;
    private final int size;

    public Q3KTensor(ByteBuffer buffer, int size) {
        this.buffer = buffer;
        this.size = size;
    }

    @Override public int size() { return size; }

    @Override
    public float get(int index) {
        int blockIdx = index / SUPER_BLOCK_SIZE;
        int withinBlock = index % SUPER_BLOCK_SIZE;
        int blockOffset = blockIdx * SUPER_BLOCK_BYTES;

        float d = Float.float16ToFloat(buffer.getShort(blockOffset + 108));

        int half = withinBlock / 128;
        int withinHalf = withinBlock % 128;

        int j = withinHalf / 32;
        int withinGroup = withinHalf % 32;

        int subGroup = withinGroup / 16;
        int l = withinGroup % 16;

        int scaleIdx = half * 8 + j * 2 + subGroup;
        int scale = getScale(blockOffset + 96, scaleIdx);

        int qsBytePos = blockOffset + 32 + half * 32 + subGroup * 16 + l;
        int qsByte = buffer.get(qsBytePos) & 0xFF;
        int qVal = (qsByte >> (j * 2)) & 3;

        int hmaskBytePos = blockOffset + subGroup * 16 + l;
        int hmaskByte = buffer.get(hmaskBytePos) & 0xFF;
        int m = 1 << (half * 4 + j);

        int sign = (hmaskByte & m) != 0 ? 0 : 4;

        return d * scale * (qVal - sign);
    }

    private int getScale(int scalesOffset, int scaleIdx) {
        // Read the 12 scale bytes as 3 uint32 values
        int sc0 = buffer.getInt(scalesOffset);
        int sc1 = buffer.getInt(scalesOffset + 4);
        int sc2 = buffer.getInt(scalesOffset + 8);

        // Rearrange into 4 uint32 using masks
        int[] aux = new int[4];
        aux[0] = (sc0 & KMASK1) | ((sc1 & KMASK1) << 2);
        aux[1] = (sc2 & KMASK1) | ((sc0 & KMASK1) << 2);
        aux[2] = (sc1 & KMASK2) | ((sc2 & KMASK2) >>> 2);
        aux[3] = (sc0 & KMASK2) | ((sc1 & KMASK2) >>> 2);

        int byteIdx = scaleIdx / 4;
        int shift = (scaleIdx % 4) * 8;
        int val = (aux[byteIdx] >>> shift) & 0xFF;

        // 6-bit signed: bits [0..5] are magnitude, bias by -32
        return (val & 0x3F) - 32;
    }

    @Override
    public void set(int index, float value) {
        throw new UnsupportedOperationException("Read-only quantized tensor");
    }
}
