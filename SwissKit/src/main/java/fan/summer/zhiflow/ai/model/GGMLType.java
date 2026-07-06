package fan.summer.zhiflow.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GGML quantization type constants (aligned with llama.cpp ggml.h).
 */
public enum GGMLType {

    F32(0, 4),
    F16(1, 2),
    Q4_0(2, 18, 32),
    Q4_1(3, 20, 32),
    // 4,5 deprecated
    Q5_0(6, 22, 32),
    Q5_1(7, 24, 32),
    Q8_0(8, 34, 32),
    Q8_1(9, 36, 32),
    Q2_K(10, 84, 256),
    Q3_K(11, 110, 256),
    Q4_K(12, 144, 256),
    Q5_K(13, 176, 256),
    Q6_K(14, 210, 256),
    Q8_K(15, 292, 256),
    IQ2_XXS(16, 66, 256),
    IQ2_XS(17, 74, 256),
    IQ3_XXS(18, 98, 256),
    IQ1_S(19, 50, 256),
    IQ4_NL(20, 18, 32),
    IQ3_S(21, 110, 256),
    IQ2_S(22, 82, 256),
    IQ4_XS(23, 136, 256),
    I8(24, 1),
    I16(25, 2),
    I32(26, 4),
    I64(27, 8),
    F64(28, 8),
    IQ1_M(29, 56, 256),
    BF16(30, 2);

    private static final Logger log = LoggerFactory.getLogger(GGMLType.class);

    public final int id;
    public final int blockSize;
    public final int typeSize;

    GGMLType(int id, int typeSize) {
        this(id, typeSize, 1);
    }

    GGMLType(int id, int typeSize, int blockSize) {
        this.id = id;
        this.typeSize = typeSize;
        this.blockSize = blockSize;
    }

    public static GGMLType fromId(int id) {
        for (GGMLType t : values()) {
            if (t.id == id) return t;
        }
        log.error("Unknown GGML type id: {}", id);
        throw new IllegalArgumentException("Unknown GGML type: " + id);
    }
}
