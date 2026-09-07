package fan.summer.fengyu.plugin.market;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, deterministic SemVer range evaluator for plugin host compatibility.
 * Supports whitespace-separated comparator sets and {@code ||} alternatives, plus the
 * npm-style shorthand authors instinctively write (P3): {@code ^1.2.3} caret ranges,
 * {@code ~1.2.3} tilde ranges, and wildcards ({@code *}, {@code 1.x}, {@code 1.2.x},
 * {@code 1.*}).
 *
 * <p>Caret/tilde expansion is comparator-based (npm's exact prerelease-membership rules are
 * not replicated — a prerelease inside the expanded window matches, consistent with how the
 * plain comparators in this engine already behave): {@code ^M.m.p} is {@code >=M.m.p <(M+1).0.0}
 * (next bumped leftmost non-zero component for 0.x / 0.0.x anchors, per npm), {@code ~M.m.p}
 * is {@code >=M.m.p <M.(m+1).0}, and a wildcard token is an open lower bound with the same
 * upper bounds ({@code 1.x} → {@code <2.0.0}, {@code 1.2.x} → {@code <1.3.0}, {@code *} → any).
 */
public final class SemanticVersionRange {
    private SemanticVersionRange() {}

    /** What a malformed range token's error tells the author (the audit's "explain the syntax"). */
    static final String SUPPORTED_SYNTAX =
            "Supported FengYu version ranges: full-semver comparators (>=4.0.0, <5.0.0, =4.0.0) "
            + "combined by spaces into sets and by '||' into alternatives; npm-style shorthand "
            + "^4.1.2 / ^0.2.3, ~4.1.2, and wildcards * / 1.x / 1.2.x";

    public static boolean includes(String range, String version) {
        if (range == null || range.isBlank()) {
            throw new IllegalArgumentException("FengYu engine range is required");
        }
        SemanticVersion candidate = SemanticVersion.parse(version);
        boolean matched = false;
        for (String alternative : range.split("\\|\\|", -1)) {
            List<Comparator> comparators = parseSet(alternative.trim());
            // An empty list means the alternative was a bare wildcard — vacuously true
            // (allMatch over nothing). A blank alternative itself throws in parseSet.
            if (comparators.stream().allMatch(c -> c.matches(candidate))) {
                matched = true;
            }
        }
        return matched;
    }

    public static boolean isValid(String range) {
        try {
            // Parsing is independent of this sentinel candidate; the result itself is irrelevant.
            includes(range, "0.0.0");
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static List<Comparator> parseSet(String set) {
        if (set.isBlank()) throw new IllegalArgumentException("Empty SemVer range alternative");
        List<Comparator> result = new ArrayList<>();
        for (String token : set.split("\\s+")) {
            result.addAll(parseToken(token));
        }
        return result;
    }

    /**
     * One range token → zero or more comparators. Zero means "matches everything" (the bare
     * wildcard), which the allMatch() fold over a non-empty set treats correctly.
     */
    private static List<Comparator> parseToken(String token) {
        // Bare wildcard: *, x, X — matches every version (including prereleases, like "=*" would).
        if (token.equals("*") || token.equals("x") || token.equals("X")) {
            return List.of();
        }
        if (token.startsWith("^") || token.startsWith("~")) {
            return expandCaretOrTilde(token);
        }
        // Wildcard partials: 1.x, 1.2.x, 1.*, 1.2.* — and (npm-style) bare partials 1, 1.2.
        List<Comparator> wildcard = parseWildcardPartial(token);
        if (wildcard != null) {
            return wildcard;
        }
        String operator = "=";
        String value = token;
        for (String prefix : List.of(">=", "<=", ">", "<", "=")) {
            if (token.startsWith(prefix)) {
                operator = prefix;
                value = token.substring(prefix.length());
                break;
            }
        }
        if (value.isBlank()) throw malformed(token);
        return List.of(new Comparator(operator, SemanticVersion.parse(value)));
    }

    /** {@code ^…} / {@code ~…} over a partial anchor (M, M.m, or M.m.p[-pre][+build]). */
    private static List<Comparator> expandCaretOrTilde(String token) {
        char kind = token.charAt(0);
        Partial anchor = Partial.parse(token.substring(1), token);
        if (anchor.full() != null) {
            SemanticVersion v = anchor.full();
            String upper = kind == '~'
                    ? bump(v.major(), v.minor())
                    : caretUpper(v);
            return lowerBound(v, upper);
        }
        // ^1 / ^0 → <2.0.0 / <1.0.0 ; ~1 → <2.0.0 ; ^1.2 → <2.0.0 ; ~1.2 → <1.3.0 ; ^0.2 → <0.3.0.
        if (anchor.minor() == null) {
            String upper = kind == '^' && anchor.major().signum() == 0
                    ? "1.0.0" : next(anchor.major());
            return partialLower(anchor, upper);
        }
        String upper = kind == '~'
                ? bump(anchor.major(), anchor.minor())
                : caretUpperPartial(anchor.major(), anchor.minor());
        return partialLower(anchor, upper);
    }

    /** {@code 1.x} → [1.0.0, 2.0.0), {@code 1.2.x} → [1.2.0, 1.3.0), bare {@code 1}/{@code 1.2} ditto. */
    private static List<Comparator> parseWildcardPartial(String token) {
        if (!(token.endsWith(".x") || token.endsWith(".X") || token.endsWith(".*"))) {
            // Bare numeric partials (1, 1.2) use the same windows; delegate to Partial only for
            // all-numeric shapes so operator tokens (>=…) are never diverted here.
            if (!token.matches("\\d+(\\.\\d+)?")) return null;
            Partial partial = Partial.parse(token, token);
            return partial.minor() == null
                    ? partialLower(partial, next(partial.major()))
                    : partialLower(partial, bump(partial.major(), partial.minor()));
        }
        Partial partial = Partial.parse(
                token.substring(0, token.length() - 2), token);
        return partial.minor() == null
                ? partialLower(partial, next(partial.major()))
                : partialLower(partial, bump(partial.major(), partial.minor()));
    }

    /** npm caret upper bound: bump the leftmost non-zero component. */
    private static String caretUpper(SemanticVersion anchor) {
        if (anchor.major().signum() != 0) return next(anchor.major());
        if (anchor.minor().signum() != 0) return bump(anchor.major(), anchor.minor());
        return patchBump(anchor.major(), anchor.minor(), anchor.patch());
    }

    /** Caret upper bound for a partial (M.m) anchor: no patch to bump, so 0.0 → 0.1.0. */
    private static String caretUpperPartial(BigInteger major, BigInteger minor) {
        if (major.signum() != 0) return next(major);
        return bump(major, minor);
    }

    private static List<Comparator> lowerBound(SemanticVersion anchor, String upperExclusive) {
        return List.of(new Comparator(">=", anchor), new Comparator("<", SemanticVersion.parse(upperExclusive)));
    }

    private static List<Comparator> partialLower(Partial anchor, String upperExclusive) {
        String base = anchor.minor() == null
                ? anchor.major() + ".0.0"
                : anchor.major() + "." + anchor.minor() + ".0";
        return lowerBound(SemanticVersion.parse(base), upperExclusive);
    }

    private static String next(BigInteger major) { return major.add(BigInteger.ONE) + ".0.0"; }

    private static String bump(BigInteger major, BigInteger minor) {
        return major + "." + minor.add(BigInteger.ONE) + ".0";
    }

    private static String patchBump(BigInteger major, BigInteger minor, BigInteger patch) {
        return major + "." + minor + "." + patch.add(BigInteger.ONE);
    }

    private static IllegalArgumentException malformed(String token) {
        return new IllegalArgumentException(
                "Unsupported FengYu version range token '" + token + "'. " + SUPPORTED_SYNTAX);
    }

    /** A partially-specified anchor: M, M.m, or a full version (with optional prerelease). */
    private record Partial(BigInteger major, BigInteger minor, SemanticVersion full) {
        static final Pattern PARTIAL = Pattern.compile(
                "^(0|[1-9]\\d*)(?:\\.(0|[1-9]\\d*))?"
                + "(?:\\.((?:0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?))?$");

        static Partial parse(String value, String originalToken) {
            Matcher matcher = PARTIAL.matcher(value == null ? "" : value);
            if (!matcher.matches()) throw malformed(originalToken);
            if (matcher.group(3) != null) {
                // The third group carries the full patch(+pre/build) — reparse the whole value
                // through the strict parser when all three components are present.
                try {
                    return new Partial(new BigInteger(matcher.group(1)),
                            new BigInteger(matcher.group(2)),
                            SemanticVersion.parse(value));
                } catch (IllegalArgumentException full) {
                    throw malformed(originalToken);
                }
            }
            return new Partial(new BigInteger(matcher.group(1)),
                    matcher.group(2) == null ? null : new BigInteger(matcher.group(2)), null);
        }
    }

    private record Comparator(String operator, SemanticVersion version) {
        boolean matches(SemanticVersion candidate) {
            int comparison = candidate.compareTo(version);
            return switch (operator) {
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                case "=" -> comparison == 0;
                default -> throw new IllegalArgumentException("Unsupported SemVer operator: " + operator);
            };
        }
    }
}
