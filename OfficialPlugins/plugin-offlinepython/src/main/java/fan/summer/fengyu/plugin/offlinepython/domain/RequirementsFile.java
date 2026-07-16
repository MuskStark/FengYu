package fan.summer.fengyu.plugin.offlinepython.domain;

import java.util.ArrayList;
import java.util.List;

/** Parse and write requirements.txt. */
public final class RequirementsFile {

    private RequirementsFile() {}

    public static List<DependencySpec> parse(String text) {
        List<DependencySpec> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(DependencySpec.parse(line));
        }
        return out;
    }

    public static String write(List<DependencySpec> deps) {
        StringBuilder sb = new StringBuilder();
        for (DependencySpec d : deps) {
            sb.append(d.toString()).append(System.lineSeparator());
        }
        return sb.toString();
    }
}
