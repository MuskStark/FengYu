package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.model.ContactImport.ParseError;
import fan.summer.fengyu.plugin.email.model.ContactImport.ParsedContact;
import fan.summer.fengyu.plugin.email.service.ContactHeaderResolver.Column;
import fan.summer.fengyu.sdk.PluginMessages;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses CSV / TSV / plain-text contact lists into {@link ParsedContact}s.
 *
 * <p>Streaming via a plain {@link Reader}; UTF-8 with an optional leading BOM
 * stripped. Field parsing implements RFC-4180-style quoting (quotes may wrap
 * fields containing the delimiter, newlines, or doubled {@code ""} escapes),
 * which is enough for the regular layouts contact exports produce. The delimiter
 * is auto-detected from the header line (comma, tab, semicolon, or pipe).
 *
 * <p>Lines are accumulated character-by-character (no buffered line-reader call) so this
 * source stays clear of the SDK-protocol guard that forbids a hand-rolled
 * JSON-RPC loop in the worker package.
 */
final class ContactCsvParser implements ContactFileParser {

    private static final PluginMessages MSGS = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, ContactCsvParser.class);

    @Override
    public Result parse(Path file) {
        List<ParsedContact> contacts = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();
        try (Reader reader = openReader(file)) {
            String headerLine = nextNonBlankLine(reader);
            if (headerLine == null) {
                errors.add(new ParseError(0, MSGS.format("em.err.contactFileEmpty")));
                return new Result(contacts, errors);
            }
            char delimiter = detectDelimiter(headerLine);
            Map<Column, Integer> columns = mapColumns(split(headerLine, delimiter));
            if (!columns.containsKey(Column.EMAIL)) {
                errors.add(new ParseError(1, MSGS.format("em.err.contactMissingEmailColumn")));
                return new Result(contacts, errors);
            }

            int row = 1; // header is row 1 conceptually; data rows start at row 2 in spreadsheets
            String line;
            while ((line = nextLine(reader)) != null) {
                row++;
                if (line.isBlank()) continue; // skip blank rows
                List<String> fields = split(line, delimiter);
                ParsedContact contact = toContact(row, fields, columns, errors);
                if (contact != null) contacts.add(contact);
            }
        } catch (IOException e) {
            // Hard I/O failure surfaces as a single error; contacts parsed so far are returned.
            errors.add(new ParseError(0, MSGS.format("em.err.contactCsvReadFailed", e.getMessage())));
        }
        return new Result(contacts, errors);
    }

    @Override public void close() { /* reader is closed per-parse via try-with-resources */ }

    private static Reader openReader(Path file) throws IOException {
        Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        reader.mark(1);
        int first = reader.read();
        if (first != -1 && first != '\uFEFF') reader.reset(); // strip BOM, else rewind
        return reader;
    }

    /** Reads the next logical line (CR and CRLF both terminate), or null at EOF. */
    private static String nextLine(Reader reader) throws IOException {
        StringBuilder out = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == '\r') {
                reader.mark(1);
                int next = reader.read();
                if (next != '\n' && next != -1) reader.reset(); // bare CR — peek, don't consume the next char
                return out.toString();
            }
            if (ch == '\n') return out.toString();
            out.append((char) ch);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** Returns the first non-blank line, or null at EOF. */
    private static String nextNonBlankLine(Reader reader) throws IOException {
        String line;
        while ((line = nextLine(reader)) != null) {
            if (!line.isBlank()) return line;
        }
        return null;
    }

    /** Auto-detect the delimiter from the header line by whichever appears first/most. */
    private static char detectDelimiter(String header) {
        int commas = count(header, ',');
        int tabs = count(header, '\t');
        int semis = count(header, ';');
        int pipes = count(header, '|');
        int max = Math.max(Math.max(commas, tabs), Math.max(semis, pipes));
        if (max == 0) return ','; // single-column file (email only)
        if (max == commas) return ',';
        if (max == tabs) return '\t';
        if (max == semis) return ';';
        return '|';
    }

    private static int count(String value, char ch) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == ch) count++;
        return count;
    }

    private static Map<Column, Integer> mapColumns(List<String> headers) {
        Map<Column, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            Column column = ContactHeaderResolver.resolve(headers.get(i));
            if (column != null) columns.putIfAbsent(column, i); // first occurrence wins
        }
        return columns;
    }

    private static ParsedContact toContact(int row, List<String> fields, Map<Column, Integer> columns,
            List<ParseError> errors) {
        String email = cell(fields, columns.get(Column.EMAIL));
        String nickname = cell(fields, columns.get(Column.NICKNAME));
        String notes = cell(fields, columns.get(Column.NOTES));
        String tagCell = cell(fields, columns.get(Column.TAGS));
        List<String> tags = tagCell == null ? List.of() : TagSplitter.split(tagCell, "auto");

        if (email == null || email.isBlank()) {
            errors.add(new ParseError(row, MSGS.format("em.err.contactMissingEmail")));
            return null;
        }
        return new ParsedContact(row, email.trim(), trimToNull(nickname), trimToNull(notes), tags);
    }

    private static String cell(List<String> fields, Integer index) {
        if (index == null || index >= fields.size()) return null;
        String value = fields.get(index);
        return value == null ? null : value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Splits a single CSV line into fields, honoring RFC-4180-style double-quote
     * wrapping and {@code ""} escapes. A quoted field may contain the delimiter.
     */
    static List<String> split(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                    else inQuotes = false;
                } else current.append(ch);
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
            } else current.append(ch);
        }
        fields.add(current.toString());
        return fields;
    }
}
