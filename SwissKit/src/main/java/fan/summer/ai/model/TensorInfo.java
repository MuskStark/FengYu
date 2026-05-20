package fan.summer.ai.model;

import java.util.List;

/**
 * Describes a single tensor stored in the GGUF file.
 */
public class TensorInfo {

    public final String name;
    public final List<Long> dimensions;
    public final GGMLType type;
    public final long offset;

    public TensorInfo(String name, List<Long> dimensions, GGMLType type, long offset) {
        this.name = name;
        this.dimensions = dimensions;
        this.type = type;
        this.offset = offset;
    }

    public long elementCount() {
        long count = 1;
        for (long d : dimensions) count *= d;
        return count;
    }
}
