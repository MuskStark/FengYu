package fan.summer.fengyu.plugin.offlinepython.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class WheelEntry {
    private String name;
    private String version;
    private String file;       // path relative to output/
    private String sha256;
    private long size;
    private boolean required;  // true = in requirements.txt; false = transitive
}
