package fan.summer.fengyu.plugin.offlinepython.domain;

/** One line of requirements.txt: a package name, an optional version spec, and an optional env marker. */
public record DependencySpec(String name, String versionSpec, String marker) {

    public DependencySpec {
        if (name == null) name = "";
        if (versionSpec == null) versionSpec = "";
    }

    /** Normalize a package name for comparison (PEP 503): lowercase, treat - and _ as equivalent. */
    public static String normalizeName(String name) {
        if (name == null) return "";
        return name.toLowerCase().replace("-", "_");
    }

    /** Parse a single requirements.txt line (no comments, already trimmed). */
    public static DependencySpec parse(String raw) {
        String line = raw == null ? "" : raw.trim();
        String marker = null;
        int semi = line.indexOf(';');
        if (semi >= 0) {
            marker = line.substring(semi + 1).trim();
            line = line.substring(0, semi).trim();
        }
        int i = 0;
        while (i < line.length()
                && !"<>=!~".contains(String.valueOf(line.charAt(i)))) {
            i++;
        }
        String name = line.substring(0, i).trim();
        String versionSpec = i < line.length() ? line.substring(i).trim() : "";
        return new DependencySpec(name, versionSpec, marker);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        if (!versionSpec.isEmpty()) sb.append(versionSpec);
        if (marker != null && !marker.isEmpty()) sb.append(" ; ").append(marker);
        return sb.toString();
    }
}
