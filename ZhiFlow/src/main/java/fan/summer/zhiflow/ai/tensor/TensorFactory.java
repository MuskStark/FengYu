package fan.summer.zhiflow.ai.tensor;

import fan.summer.zhiflow.ai.model.GGMLType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Factory for creating tensor instances from raw buffer data.
 */
public final class TensorFactory {

    private static final Logger log = LoggerFactory.getLogger(TensorFactory.class);

    private TensorFactory() {}

    public static FloatTensor create(ByteBuffer data, GGMLType type, int elementCount) {
        log.debug("TensorFactory.create: type={}, elementCount={}", type, elementCount);
        return switch (type) {
            case F32     -> new MappedTensor(data, elementCount, false);
            case F16     -> new MappedTensor(data, elementCount, true);
            case Q8_0    -> new Q8_0Tensor(data, elementCount);
            case Q4_0    -> new Q4_0Tensor(data, elementCount);
            case IQ4_NL  -> new IQ4NLTensor(data, elementCount);
            case Q2_K    -> new Q2KTensor(data, elementCount);
            case Q3_K    -> new Q3KTensor(data, elementCount);
            case Q5_0    -> new Q5_0Tensor(data, elementCount);
            default      -> {
                log.error("Unsupported quantization type: {}", type);
                throw new UnsupportedOperationException(
                    "Unsupported quantization type: " + type +
                    ". Supported types: F32, F16, Q4_0, Q8_0, Q2_K, Q3_K, Q5_0, IQ4_NL. " +
                    "Try using a model with one of these quantization formats.");
            }
        };
    }
}
