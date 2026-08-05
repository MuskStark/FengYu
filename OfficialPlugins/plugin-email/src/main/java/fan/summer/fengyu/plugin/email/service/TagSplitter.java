package fan.summer.fengyu.plugin.email.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Splits a tag-cell value into individual, trimmed, de-duplicated tag names.
 *
 * <p>Delimiters: {@code ,} {@code ;} {@code |} or any newline, unless an explicit
 * single-character delimiter is requested by the import options. Empty fragments
 * are dropped. Order of first occurrence is preserved (the importer uses original
 * casing for display while comparing case-insensitively elsewhere).
 */
final class TagSplitter {
    private static final Set<Character> AUTO_DELIMITERS = Set.of(',', ';', '|', '\n', '\r');

    /** Splits on the requested delimiter, or auto-detects when {@code delimiter == "auto"}. */
    static List<String> split(String value, String delimiter) {
        if (value == null || value.isBlank()) return List.of();
        List<String> tags = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(); // preserves order + dedupes (case-sensitive here)
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean isDelimiter = "auto".equals(delimiter)
                ? AUTO_DELIMITERS.contains(ch)
                : (delimiter.length() == 1 && ch == delimiter.charAt(0));
            if (isDelimiter) {
                add(current, tags, seen);
                current.setLength(0);
            } else current.append(ch);
        }
        add(current, tags, seen);
        return tags;
    }

    private static void add(StringBuilder current, List<String> tags, Set<String> seen) {
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty() && seen.add(trimmed)) tags.add(trimmed);
    }

    private TagSplitter() { }
}
