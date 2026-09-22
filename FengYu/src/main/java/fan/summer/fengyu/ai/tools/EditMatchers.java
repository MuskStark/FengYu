package fan.summer.fengyu.ai.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Progressive old-string matching for {@code edit_file}.
 *
 * <p>Model-authored search strings frequently differ cosmetically from the file: smart quotes,
 * a line-number prefix copied from a read listing, escaped characters, or drifted indentation.
 * Instead of failing on the first mismatch, this class walks a fixed waterfall from strict to
 * forgiving and stops at the first strategy that produces candidates:
 * exact → quote-normalized → line-number-prefix-stripped → escape-normalized →
 * unicode-escape-normalized → line-trimmed → indentation-flexible → block-anchor.
 *
 * <p>Three invariants keep the forgiveness safe:
 * <ul>
 *   <li>a strategy that finds more than one <em>distinct</em> candidate value is
 *       {@link Ambiguous}, never a guess;</li>
 *   <li>the three broad strategies (line-trimmed, indentation-flexible, block-anchor) are
 *       skipped for {@code replace_all}, where a fuzzy match could rewrite the wrong block;</li>
 *   <li>block-anchor additionally requires ≥ 0.8 average middle-line similarity, so a shared
 *       first/last line alone can never anchor a replacement.</li>
 * </ul>
 *
 * <p>Strategy design ported from ZCode's {@code edit-matchers.ts}
 * (github.com/zai-org/ZCode, Apache-2.0); the port keeps the waterfall order, the
 * ambiguity rule, the replace-all restriction, and the quote-style preservation semantics.
 */
public final class EditMatchers {

    private EditMatchers() {}

    public enum Strategy {
        EXACT, QUOTE_NORMALIZED, LINE_NUMBER_PREFIX_STRIPPED, ESCAPE_NORMALIZED,
        UNICODE_ESCAPE_NORMALIZED, LINE_TRIMMED, INDENTATION_FLEXIBLE, BLOCK_ANCHOR
    }

    public sealed interface MatchResult permits Matched, Ambiguous, NotFound {}

    /** One unambiguous replacement target; {@code candidateCount} may exceed 1 for replace-all. */
    public record Matched(String actualString, Strategy strategy, int candidateCount) implements MatchResult {}

    /** The strategy matched but distinct candidate values exist — a human/model must disambiguate. */
    public record Ambiguous(Strategy strategy, int candidateCount) implements MatchResult {}

    public record NotFound() implements MatchResult {}

    private static final Set<Strategy> BROAD = Set.of(
            Strategy.LINE_TRIMMED, Strategy.INDENTATION_FLEXIBLE, Strategy.BLOCK_ANCHOR);
    private static final double BLOCK_ANCHOR_MIN_SIMILARITY = 0.8;
    private static final char LEFT_SINGLE_CURLY = '‘';
    private static final char RIGHT_SINGLE_CURLY = '’';
    private static final char LEFT_DOUBLE_CURLY = '“';
    private static final char RIGHT_DOUBLE_CURLY = '”';

    private static final Pattern LINE_NUMBER_COLON = Pattern.compile("^\\s*\\d+: (.*)$");
    private static final Pattern LINE_NUMBER_TAB = Pattern.compile("^\\s*\\d+\\t(.*)$");
    private static final Pattern VISIBLE_ESCAPE = Pattern.compile("\\\\([ntr\"'`\\\\$])");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("(\\\\\\\\)|\\\\u([0-9a-fA-F]{4})");

    public static MatchResult findEditMatch(String content, String search, boolean replaceAll) {
        List<Candidate> exact = substringCandidates(content, search);
        if (!exact.isEmpty()) return toMatchResult(Strategy.EXACT, exact);

        for (Strategy strategy : Strategy.values()) {
            if (strategy == Strategy.EXACT) continue;
            if (replaceAll && BROAD.contains(strategy)) continue;
            List<Candidate> candidates = candidatesFor(strategy, content, search);
            if (candidates.isEmpty()) continue;
            return toMatchResult(strategy, candidates);
        }
        return new NotFound();
    }

    public static String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n");
    }

    /**
     * Escape-normalized matches are located by their unescaped form, so the replacement must be
     * unescaped the same way before it is written (only the escape strategy rewrites the input).
     */
    public static String normalizeReplacementForMatch(Strategy strategy, String newString) {
        return strategy == Strategy.ESCAPE_NORMALIZED ? unescapeVisibleCharacters(newString) : newString;
    }

    /**
     * When a smart-quote file absorbed the match through quote normalization, mirror the file's
     * quote style into the replacement instead of introducing straight quotes into curly text.
     */
    public static String preserveQuoteStyle(String oldString, String actualOldString, String newString) {
        if (oldString.equals(actualOldString)) return newString;
        String result = newString;
        if (actualOldString.indexOf(LEFT_DOUBLE_CURLY) >= 0
                || actualOldString.indexOf(RIGHT_DOUBLE_CURLY) >= 0) {
            result = applyCurlyDoubleQuotes(result);
        }
        if (actualOldString.indexOf(LEFT_SINGLE_CURLY) >= 0
                || actualOldString.indexOf(RIGHT_SINGLE_CURLY) >= 0) {
            result = applyCurlySingleQuotes(result);
        }
        return result;
    }

    // ── waterfall steps ─────────────────────────────────────────────────────────────────

    private static List<Candidate> candidatesFor(Strategy strategy, String content, String search) {
        return switch (strategy) {
            case EXACT -> substringCandidates(content, search);
            case QUOTE_NORMALIZED -> normalizedCandidates(content, search, EditMatchers::normalizeQuotes);
            case LINE_NUMBER_PREFIX_STRIPPED -> lineNumberPrefixCandidates(content, search);
            case ESCAPE_NORMALIZED -> escapeNormalizedCandidates(content, search);
            case UNICODE_ESCAPE_NORMALIZED -> unicodeEscapeNormalizedCandidates(content, search);
            case LINE_TRIMMED -> lineTrimmedCandidates(content, search);
            case INDENTATION_FLEXIBLE -> indentationFlexibleCandidates(content, search);
            case BLOCK_ANCHOR -> blockAnchorCandidates(content, search);
        };
    }

    private static List<Candidate> lineNumberPrefixCandidates(String content, String search) {
        String stripped = stripReadLineNumberPrefixes(search);
        if (stripped == null || stripped.equals(search)) return List.of();
        return substringCandidates(content, stripped);
    }

    private static List<Candidate> escapeNormalizedCandidates(String content, String search) {
        String unescaped = unescapeVisibleCharacters(search);
        if (unescaped.equals(search)) return List.of();
        return substringCandidates(content, unescaped);
    }

    private static List<Candidate> unicodeEscapeNormalizedCandidates(String content, String search) {
        String unescaped = unescapeUnicodeCharacters(search);
        if (unescaped.equals(search)) return List.of();
        return substringCandidates(content, unescaped);
    }

    private static List<Candidate> lineTrimmedCandidates(String content, String search) {
        String[] contentLines = lines(content);
        String[] searchLines = trimTrailingEmptyLine(lines(search));
        if (searchLines.length == 0) return List.of();

        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index <= contentLines.length - searchLines.length; index++) {
            String[] block = slice(contentLines, index, searchLines.length);
            if (!linesEqualWhenTrimmed(block, searchLines)) continue;
            candidates.add(blockCandidate(contentLines, index, searchLines.length));
        }
        return candidates;
    }

    private static List<Candidate> indentationFlexibleCandidates(String content, String search) {
        String[] contentLines = lines(content);
        String[] searchLines = trimTrailingEmptyLine(lines(search));
        if (searchLines.length < 2) return List.of();

        String normalizedSearch = removeCommonIndent(searchLines);
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index <= contentLines.length - searchLines.length; index++) {
            String[] block = slice(contentLines, index, searchLines.length);
            if (!removeCommonIndent(block).equals(normalizedSearch)) continue;
            candidates.add(blockCandidate(contentLines, index, searchLines.length));
        }
        return candidates;
    }

    private static List<Candidate> blockAnchorCandidates(String content, String search) {
        String[] contentLines = lines(content);
        String[] searchLines = trimTrailingEmptyLine(lines(search));
        if (searchLines.length < 3) return List.of();

        String first = searchLines[0].trim();
        String last = searchLines[searchLines.length - 1].trim();
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index <= contentLines.length - searchLines.length; index++) {
            String[] block = slice(contentLines, index, searchLines.length);
            if (!block[0].trim().equals(first)) continue;
            if (!block[block.length - 1].trim().equals(last)) continue;
            if (averageMiddleSimilarity(block, searchLines) < BLOCK_ANCHOR_MIN_SIMILARITY) continue;
            candidates.add(blockCandidate(contentLines, index, searchLines.length));
        }
        return candidates;
    }

    // ── substring / normalized search ───────────────────────────────────────────────────

    private record Candidate(String value, int index) {}

    private static List<Candidate> substringCandidates(String content, String search) {
        if (search.isEmpty()) return List.of();
        List<Candidate> candidates = new ArrayList<>();
        int position = 0;
        while (position <= content.length()) {
            int index = content.indexOf(search, position);
            if (index < 0) break;
            candidates.add(new Candidate(search, index));
            position = index + Math.max(search.length(), 1);
        }
        return candidates;
    }

    /**
     * Searches in the normalized projection but reports ORIGINAL text spans: every supported
     * normalizer is length-preserving, so a normalized index maps 1:1 back to the file.
     */
    private static List<Candidate> normalizedCandidates(String content, String search,
            java.util.function.UnaryOperator<String> normalize) {
        String normalizedContent = normalize.apply(content);
        String normalizedSearch = normalize.apply(search);
        if (normalizedSearch.isEmpty()) return List.of();
        List<Candidate> candidates = new ArrayList<>();
        int position = 0;
        while (position <= normalizedContent.length()) {
            int index = normalizedContent.indexOf(normalizedSearch, position);
            if (index < 0) break;
            candidates.add(new Candidate(content.substring(index, index + search.length()), index));
            position = index + Math.max(normalizedSearch.length(), 1);
        }
        return candidates;
    }

    private static MatchResult toMatchResult(Strategy strategy, List<Candidate> candidates) {
        Set<String> uniqueValues = new LinkedHashSet<>();
        for (Candidate candidate : candidates) uniqueValues.add(candidate.value());
        if (uniqueValues.size() != 1) return new Ambiguous(strategy, candidates.size());
        return new Matched(uniqueValues.iterator().next(), strategy, candidates.size());
    }

    // ── normalizers ──────────────────────────────────────────────────────────────────────

    private static String stripReadLineNumberPrefixes(String search) {
        String[] searchLines = lines(search);
        String[] stripped = new String[searchLines.length];
        for (int i = 0; i < searchLines.length; i++) {
            Matcher colon = LINE_NUMBER_COLON.matcher(searchLines[i]);
            if (colon.matches()) {
                stripped[i] = colon.group(1) == null ? "" : colon.group(1);
                continue;
            }
            Matcher tab = LINE_NUMBER_TAB.matcher(searchLines[i]);
            if (tab.matches()) {
                stripped[i] = tab.group(1) == null ? "" : tab.group(1);
                continue;
            }
            return null; // a line without a prefix disqualifies the whole search string
        }
        return String.join("\n", stripped);
    }

    static String unescapeVisibleCharacters(String search) {
        Matcher matcher = VISIBLE_ESCAPE.matcher(search);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "n" -> "\n";
                case "t" -> "\t";
                case "r" -> "\r";
                default -> matcher.group(1); // " ' ` \ $
            };
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static String unescapeUnicodeCharacters(String search) {
        Matcher matcher = UNICODE_ESCAPE.matcher(search);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            // Group 1 is an escaped backslash — keep verbatim; group 2 is a backslash-u hex
            // escape, replaced by the character it names.
            String replacement = matcher.group(1) != null
                    ? matcher.group()
                    : String.valueOf((char) Integer.parseInt(matcher.group(2), 16));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String normalizeQuotes(String value) {
        return value
                .replace(LEFT_SINGLE_CURLY, '\'')
                .replace(RIGHT_SINGLE_CURLY, '\'')
                .replace(LEFT_DOUBLE_CURLY, '"')
                .replace(RIGHT_DOUBLE_CURLY, '"');
    }

    // ── quote style restoration ──────────────────────────────────────────────────────────

    private static String applyCurlyDoubleQuotes(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            result.append(c != '"' ? c
                    : isOpeningQuoteContext(value, index) ? LEFT_DOUBLE_CURLY : RIGHT_DOUBLE_CURLY);
        }
        return result.toString();
    }

    private static String applyCurlySingleQuotes(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c != '\'') {
                result.append(c);
                continue;
            }
            char previous = index > 0 ? value.charAt(index - 1) : '\0';
            char next = index < value.length() - 1 ? value.charAt(index + 1) : '\0';
            result.append(isLetter(previous) && isLetter(next)
                    ? RIGHT_SINGLE_CURLY
                    : isOpeningQuoteContext(value, index) ? LEFT_SINGLE_CURLY : RIGHT_SINGLE_CURLY);
        }
        return result.toString();
    }

    private static boolean isOpeningQuoteContext(String value, int index) {
        if (index == 0) return true;
        char previous = value.charAt(index - 1);
        return previous == ' ' || previous == '\t' || previous == '\n' || previous == '\r'
                || previous == '(' || previous == '[' || previous == '{'
                || previous == '—' || previous == '–';
    }

    private static boolean isLetter(char value) {
        return Character.isLetter(value);
    }

    // ── line-block helpers ───────────────────────────────────────────────────────────────

    /** JS-style split: every trailing empty segment is retained. */
    private static String[] lines(String value) {
        return value.split("\n", -1);
    }

    private static String[] trimTrailingEmptyLine(String[] input) {
        if (input.length > 0 && input[input.length - 1].isEmpty()) {
            String[] trimmed = new String[input.length - 1];
            System.arraycopy(input, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return input;
    }

    private static String[] slice(String[] source, int start, int count) {
        String[] block = new String[count];
        System.arraycopy(source, start, block, 0, count);
        return block;
    }

    private static boolean linesEqualWhenTrimmed(String[] actual, String[] expected) {
        for (int offset = 0; offset < actual.length; offset++) {
            if (!actual[offset].trim().equals(expected[offset].trim())) return false;
        }
        return true;
    }

    private static Candidate blockCandidate(String[] lines, int startLine, int lineCount) {
        return new Candidate(String.join("\n", slice(lines, startLine, lineCount)),
                offsetForLine(lines, startLine));
    }

    private static int offsetForLine(String[] lines, int lineIndex) {
        int offset = 0;
        for (int index = 0; index < lineIndex; index++) offset += lines[index].length() + 1;
        return offset;
    }

    private static String removeCommonIndent(String[] input) {
        int minIndent = Integer.MAX_VALUE;
        boolean anyNonEmpty = false;
        for (String line : input) {
            if (line.trim().isEmpty()) continue;
            anyNonEmpty = true;
            minIndent = Math.min(minIndent, indentOf(line));
        }
        if (!anyNonEmpty) return String.join("\n", input);
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < input.length; index++) {
            if (index > 0) joined.append('\n');
            String line = input[index];
            joined.append(line.trim().isEmpty() ? line : line.substring(minIndent));
        }
        return joined.toString();
    }

    private static int indentOf(String line) {
        int length = 0;
        while (length < line.length() && (line.charAt(length) == ' ' || line.charAt(length) == '\t')) {
            length++;
        }
        return length;
    }

    private static double averageMiddleSimilarity(String[] actual, String[] expected) {
        if (actual.length <= 2) return 1;
        double total = 0;
        int count = 0;
        for (int index = 1; index < actual.length - 1; index++) {
            total += lineSimilarity(actual[index].trim(), expected[index].trim());
            count++;
        }
        return count == 0 ? 1 : total / count;
    }

    private static double lineSimilarity(String left, String right) {
        if (left.equals(right)) return 1;
        int maxLength = Math.max(left.length(), right.length());
        if (maxLength == 0) return 1;
        return 1 - (double) levenshtein(left, right) / maxLength;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) previous[index] = index;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            int[] current = new int[right.length() + 1];
            current[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int cost = left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1;
                current[rightIndex] = Math.min(Math.min(
                        previous[rightIndex] + 1,
                        current[rightIndex - 1] + 1),
                        previous[rightIndex - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }
}
