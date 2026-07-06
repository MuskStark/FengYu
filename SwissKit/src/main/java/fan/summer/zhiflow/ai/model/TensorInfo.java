package fan.summer.zhiflow.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Describes a single tensor stored in the GGUF file.
 */
public class TensorInfo {

    private static final Logger log = LoggerFactory.getLogger(TensorInfo.class);

    public final String name;
    public final List<Long> dimensions;
    public final GGMLType type;
    public final long offset;

    public TensorInfo(String name, List<Long> dimensions, GGMLType type, long offset) {
        this.name = name;
        this.dimensions = dimensions;
        this.type = type;
        this.offset = offset;
        log.debug("TensorInfo created: name={}, dimensions={}, type={}, offset={}",
                  name, dimensions, type, offset);
    }

    public long elementCount() {
        long count = 1;
        for (long d : dimensions) count *= d;
        log.debug("elementCount for {}: {}", name, count);
        return count;
    }
}
