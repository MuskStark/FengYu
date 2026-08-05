package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.model.ContactImport.ParseError;
import fan.summer.fengyu.plugin.email.model.ContactImport.ParsedContact;
import fan.summer.fengyu.plugin.email.service.ContactHeaderResolver.Column;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Excel ({@code .xlsx}/{@code .xls}) contact workbooks into {@link ParsedContact}s.
 *
 * <p>Reads the first worksheet ({@code getSheetAt(0)}); if further sheets exist they are
 * reported as an informational {@link ParseError} (row 0) so the UI can note them —
 * the import does not silently use or skip them. The first non-empty row whose cells
 * match a known header alias is treated as the header row. Cell text is read via
 * {@link DataFormatter} so dates/numbers render like they do in Excel, not as raw
 * numeric codes (important for phone-like or id-like fields).
 */
final class ContactExcelParser implements ContactFileParser {
    private final String tagDelimiter;
    private Workbook workbook;

    ContactExcelParser(String tagDelimiter) {
        this.tagDelimiter = tagDelimiter == null || tagDelimiter.isBlank() ? "auto" : tagDelimiter;
    }

    @Override
    public Result parse(Path file) {
        List<ParsedContact> contacts = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();
        try {
            workbook = open(file);
            Sheet sheet = workbook.getSheetAt(0);
            if (workbook.getNumberOfSheets() > 1) {
                StringBuilder names = new StringBuilder();
                for (int i = 1; i < workbook.getNumberOfSheets(); i++) {
                    if (i > 1) names.append(", ");
                    names.append(workbook.getSheetName(i));
                }
                errors.add(new ParseError(0, "Import used the first sheet; ignored: " + names));
            }
            Map<Column, Integer> columns = new LinkedHashMap<>();
            int headerRow = findHeader(sheet, columns);
            if (headerRow < 0 || !columns.containsKey(Column.EMAIL)) {
                errors.add(new ParseError(0, "Missing an email column (expected a header like 'email', 'E-mail', or '邮箱')"));
                return new Result(contacts, errors);
            }
            DataFormatter formatter = new DataFormatter();
            int last = sheet.getLastRowNum();
            for (int r = headerRow + 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isEmpty(row, formatter)) continue; // skip blank rows
                ParsedContact contact = toContact(r + 1, row, columns, formatter, errors);
                if (contact != null) contacts.add(contact);
            }
        } catch (IOException e) {
            errors.add(new ParseError(0, "Could not read Excel file: " + e.getMessage()));
        }
        return new Result(contacts, errors);
    }

    @Override public void close() {
        if (workbook != null) try { workbook.close(); } catch (IOException ignored) { }
    }

    private static Workbook open(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return WorkbookFactory.create(in);
        }
    }

    /** Locates the first non-empty row that maps to at least one logical column; fills {@code columns}. */
    private static int findHeader(Sheet sheet, Map<Column, Integer> columns) {
        DataFormatter formatter = new DataFormatter();
        int last = sheet.getLastRowNum();
        for (int r = 0; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                Column column = ContactHeaderResolver.resolve(formatter.formatCellValue(cell));
                if (column != null) columns.putIfAbsent(column, cell.getColumnIndex());
            }
            if (!columns.isEmpty()) return r;
        }
        return -1;
    }

    private ParsedContact toContact(int row, Row excelRow, Map<Column, Integer> columns,
            DataFormatter formatter, List<ParseError> errors) {
        String email = cell(excelRow, columns.get(Column.EMAIL), formatter);
        String nickname = cell(excelRow, columns.get(Column.NICKNAME), formatter);
        String notes = cell(excelRow, columns.get(Column.NOTES), formatter);
        String tagCell = cell(excelRow, columns.get(Column.TAGS), formatter);
        List<String> tags = tagCell == null ? List.of() : TagSplitter.split(tagCell, tagDelimiter);

        if (email == null || email.isBlank()) {
            errors.add(new ParseError(row, "Missing email address"));
            return null;
        }
        return new ParsedContact(row, email.trim(), trimToNull(nickname), trimToNull(notes), tags);
    }

    private static String cell(Row row, Integer columnIndex, DataFormatter formatter) {
        if (columnIndexValid(row, columnIndex)) {
            Cell cell = row.getCell(columnIndex);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String value = formatter.formatCellValue(cell);
                return value == null ? null : value;
            }
        }
        return null;
    }

    private static boolean columnIndexValid(Row row, Integer columnIndex) {
        return columnIndex != null && columnIndex >= 0 && columnIndex < row.getLastCellNum();
    }

    private static boolean isEmpty(Row row, DataFormatter formatter) {
        if (row.getLastCellNum() <= 0) return true;
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.BLANK) {
                String value = formatter.formatCellValue(cell);
                if (value != null && !value.isBlank()) return false;
            }
        }
        return true;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
