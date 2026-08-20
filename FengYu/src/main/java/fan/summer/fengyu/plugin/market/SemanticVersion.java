package fan.summer.fengyu.plugin.market;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict SemVer 2.0 parser/comparator shared by plugin catalog, inspection, and seeding. */
public final class SemanticVersion implements Comparable<SemanticVersion> {
    private static final Pattern VERSION = Pattern.compile(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
            + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
    );
    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    private final BigInteger major;
    private final BigInteger minor;
    private final BigInteger patch;
    private final List<String> prerelease;

    private SemanticVersion(BigInteger major, BigInteger minor, BigInteger patch,
            List<String> prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    public static SemanticVersion parse(String value) {
        if (value == null) throw new IllegalArgumentException("Semantic version is required");
        Matcher matcher = VERSION.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + value);
        }
        List<String> prerelease = matcher.group(4) == null
            ? List.of()
            : List.of(matcher.group(4).split("\\."));
        for (String identifier : prerelease) {
            if (NUMERIC.matcher(identifier).matches()
                    && identifier.length() > 1 && identifier.charAt(0) == '0') {
                throw new IllegalArgumentException(
                    "Numeric prerelease identifiers must not contain leading zeroes: " + value);
            }
        }
        return new SemanticVersion(
            new BigInteger(matcher.group(1)),
            new BigInteger(matcher.group(2)),
            new BigInteger(matcher.group(3)),
            prerelease);
    }

    public static boolean isValid(String value) {
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static int compare(String left, String right) {
        return parse(left).compareTo(parse(right));
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int core = major.compareTo(other.major);
        if (core != 0) return core;
        core = minor.compareTo(other.minor);
        if (core != 0) return core;
        core = patch.compareTo(other.patch);
        if (core != 0) return core;

        if (prerelease.isEmpty()) return other.prerelease.isEmpty() ? 0 : 1;
        if (other.prerelease.isEmpty()) return -1;
        int shared = Math.min(prerelease.size(), other.prerelease.size());
        for (int i = 0; i < shared; i++) {
            int identifier = compareIdentifier(prerelease.get(i), other.prerelease.get(i));
            if (identifier != 0) return identifier;
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = NUMERIC.matcher(left).matches();
        boolean rightNumeric = NUMERIC.matcher(right).matches();
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric != rightNumeric) return leftNumeric ? -1 : 1;
        return left.compareTo(right);
    }
}
