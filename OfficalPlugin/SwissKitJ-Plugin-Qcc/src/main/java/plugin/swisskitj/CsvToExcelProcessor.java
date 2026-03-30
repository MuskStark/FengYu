package plugin.swisskitj;


import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
/**
 * Qichacha "Actual Controller" batch query CSV to Excel processor.
 *
 * Supported control types: Actual Controller / Suspected Actual Controller / Concert Party
 * Supported column name variants: Actual Controller (Public Info), Direct Shareholding Ratio, Total Shareholding Ratio, etc.
 */
public class CsvToExcelProcessor {

    // ── 输出表头 ──────────────────────────────────────────────────────────
    private static final String[] HEADERS = {
            "公司名称", "控制类型", "序号",
            "控制人姓名/机构",
            "直接持股比例", "总持股比例", "表决权比例",
            "控制链", "判定依据"
    };

    // ── 控制类型常量 ──────────────────────────────────────────────────────
    private static final String TYPE_ACTUAL    = "实际控制人";
    private static final String TYPE_SUSPECTED = "疑似实际控制人";
    private static final String TYPE_CONCERT   = "一致行动人";

    // ── Main Process ────────────────────────────────────────────────────────────
    public static void process(String inputCsv, String outputXlsx) throws Exception {
        List<List<String>> rows = readCsv(inputCsv);
        List<DataRow> records = parseData(rows);

        System.out.printf("Parsed %d records, involving %d companies%n",
                records.size(),
                records.stream().map(r -> r.getCompany()).distinct().count());

        writeExcel(records, outputXlsx);
    }

    // ── CSV Parsing ──────────────────────────────────────────────────────────

    /**
     * Reads CSV file and returns all rows as list of cell lists.
     *
     * @param filePath path to the CSV file
     * @return list of rows, each row is a list of cell values
     * @throws IOException if file cannot be read
     */
    private static List<List<String>> readCsv(String filePath) throws IOException {
        List<List<String>> allRows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {
            // Skip BOM if present
            br.mark(4);
            int bom = br.read();
            if (bom != 0xFEFF) br.reset();

            String line;
            while ((line = br.readLine()) != null) {
                List<String> cells = parseCsvLine(line);
                if (cells.stream().anyMatch(s -> !s.isEmpty())) {
                    allRows.add(cells);
                }
            }
        }
        return allRows;
    }

    /**
     * Parses a single CSV line, handling quotes and =" format.
     *
     * @param line the CSV line to parse
     * @return list of cell values
     */
    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                sb.append(c);
            } else if (c == ',' && !inQuotes) {
                cells.add(cleanCell(sb.toString()));
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        cells.add(cleanCell(sb.toString()));
        return cells;
    }

    /**
     * Removes =" ... " wrapper and trims whitespace from cell value.
     *
     * @param raw the raw cell value
     * @return cleaned cell value
     */
    private static String cleanCell(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("=\"") && s.endsWith("\"")) return s.substring(2, s.length() - 1).trim();
        if (s.startsWith("="))  s = s.substring(1).trim();
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1).trim();
        return s;
    }

    // ── Data Parsing ─────────────────────────────────────────────────────────

    /**
     * Parses CSV rows into structured DataRow objects.
     *
     * @param rows raw CSV rows
     * @return list of parsed data records
     */
    private static List<DataRow> parseData(List<List<String>> rows) {
        List<DataRow> records = new ArrayList<>();

        String currentCompany = null;
        String currentSection = null;
        // Column indices (dynamically determined from header row)
        int colName = -1, colDirect = -1, colTotal = -1, colVote = -1,
                colChain = -1, colBasis = -1;

        // Skip row 0 (Qichacha disclaimer)
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.isEmpty()) continue;
            String first = row.get(0).trim();
            if (first.isEmpty()) continue;

            // ① Company name row
            if (isCompanyRow(row)) {
                currentCompany = first;
                currentSection = null;
                colName = colDirect = colTotal = colVote = colChain = colBasis = -1;
                continue;
            }

            // ② Control type row
            if (isSectionRow(row)) {
                currentSection = first;
                colName = colDirect = colTotal = colVote = colChain = colBasis = -1;
                continue;
            }

            // ③ Header row
            if (isHeaderRow(row)) {
                colName   = findCol(row, new String[]{"实际控制人","疑似实际控制人","一致行动人"},
                        new String[]{"直接","总持","表决","链"});
                colDirect = findColContains(row, "直接持股");
                colTotal  = findColContains(row, "总持股");
                colVote   = findColContains(row, "表决");
                colChain  = findColContains(row, "控制链");
                colBasis  = findColContains(row, "判定依据");
                continue;
            }

            // ④ Data row
            if (isDataRow(row) && currentCompany != null && currentSection != null) {
                DataRow dr = new DataRow();
                dr.setCompany(currentCompany);
                dr.setSectionType(currentSection);
                dr.setSeq(g(row, 0));
                dr.setName(g(row, colName));
                dr.setDirectPct(g(row, colDirect));
                dr.setTotalPct(g(row, colTotal));
                dr.setVotePct(g(row, colVote));
                dr.setControlChain(g(row, colChain));
                dr.setBasis(g(row, colBasis));
                records.add(dr);
            }
        }

        return records;
    }

    /**
     * Company name row: first column non-empty, not control type, not "Sequence", not pure digits;
     * other columns basically empty (≤1 non-empty column).
     */
    private static boolean isCompanyRow(List<String> row) {
        String first = row.get(0).trim();
        if (first.isEmpty()) return false;
        if (isSectionRow(row) || isHeaderRow(row) || isDataRow(row)) return false;
        long nonEmpty = row.stream().skip(1).filter(s -> !s.trim().isEmpty()).count();
        return nonEmpty <= 1;
    }

    /**
     * Checks if row is a control type section row.
     */
    private static boolean isSectionRow(List<String> row) {
        String first = row.get(0).trim();
        return first.equals(TYPE_ACTUAL) || first.equals(TYPE_SUSPECTED) || first.equals(TYPE_CONCERT);
    }

    /**
     * Checks if row is a header row (starts with "Sequence").
     */
    private static boolean isHeaderRow(List<String> row) {
        return row.get(0).trim().equals("序号");
    }

    /**
     * Checks if row is a data row (first column is a number).
     */
    private static boolean isDataRow(List<String> row) {
        return row.get(0).trim().matches("^\\d+$");
    }

    /**
     * Finds column index: contains any mustContain keyword, and contains none of excludes.
     *
     * @param headers list of header strings
     * @param mustContain keywords that must be present
     * @param excludes keywords that must not be present
     * @return column index, or -1 if not found
     */
    private static int findCol(List<String> headers, String[] mustContain, String[] excludes) {
        for (int j = 0; j < headers.size(); j++) {
            String h = headers.get(j);
            boolean hasKeyword = false;
            for (String m : mustContain) if (h.contains(m)) { hasKeyword = true; break; }
            if (!hasKeyword) continue;
            boolean excluded = false;
            for (String e : excludes) if (h.contains(e)) { excluded = true; break; }
            if (!excluded) return j;
        }
        return -1;
    }

    /**
     * Finds the first column containing the keyword.
     *
     * @param headers list of header strings
     * @param keyword keyword to search for
     * @return column index, or -1 if not found
     */
    private static int findColContains(List<String> headers, String keyword) {
        for (int j = 0; j < headers.size(); j++) {
            if (headers.get(j).contains(keyword)) return j;
        }
        return -1;
    }

    /**
     * Safely gets cell value at specified index.
     *
     * @param row list of cell values
     * @param idx column index
     * @return cell value or empty string if index out of bounds
     */
    private static String g(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) return "";
        return row.get(idx);
    }

    // ── Excel Output ────────────────────────────────────────────────────────

    /**
     * Writes parsed records to Excel file with styling.
     *
     * @param records list of data records to write
     * @param outputPath output Excel file path
     * @throws Exception if writing fails
     */
    private static void writeExcel(List<DataRow> records, String outputPath) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet ws = wb.createSheet("Actual Controller");

        // Freeze header row
        ws.createFreezePane(0, 1);

        // Auto filter
        ws.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, HEADERS.length - 1));

        // ── Header Style ──
        XSSFCellStyle headerStyle = wb.createCellStyle();
        XSSFFont headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerFont.setFontHeightInPoints((short) 11);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0x1F,(byte)0x38,(byte)0x64}, null));
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        applyBorder(headerStyle, BorderStyle.THIN);
        headerStyle.setWrapText(true);

        // Write header
        Row headerRow = ws.createRow(0);
        headerRow.setHeightInPoints(22);
        for (int ci = 0; ci < HEADERS.length; ci++) {
            Cell cell = headerRow.createCell(ci);
            cell.setCellValue(HEADERS[ci]);
            cell.setCellStyle(headerStyle);
        }

        // ── Section Colors ──
        XSSFColor colorActual    = new XSSFColor(new byte[]{(byte)0xD6,(byte)0xE4,(byte)0xF0}, null);
        XSSFColor colorSuspected = new XSSFColor(new byte[]{(byte)0xFF,(byte)0xF2,(byte)0xCC}, null);
        XSSFColor colorConcert   = new XSSFColor(new byte[]{(byte)0xE2,(byte)0xEF,(byte)0xDA}, null);

        int ri = 1;
        for (DataRow dr : records) {
            XSSFColor rowColor;
            if (dr.getSectionType().equals(TYPE_SUSPECTED)) rowColor = colorSuspected;
            else if (dr.getSectionType().equals(TYPE_CONCERT)) rowColor = colorConcert;
            else rowColor = colorActual;

            // Base style
            XSSFCellStyle base = wb.createCellStyle();
            base.setFillForegroundColor(rowColor);
            base.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            base.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(base, BorderStyle.THIN);

            // Wrap style (for Control Chain column)
            XSSFCellStyle wrapStyle = wb.createCellStyle();
            wrapStyle.cloneStyleFrom(base);
            wrapStyle.setWrapText(true);

            // Center style (for ratio columns)
            XSSFCellStyle centerStyle = wb.createCellStyle();
            centerStyle.cloneStyleFrom(base);
            centerStyle.setAlignment(HorizontalAlignment.CENTER);

            Row row = ws.createRow(ri++);
            row.setHeightInPoints(18);

            String[] vals = {
                    dr.getCompany(), dr.getSectionType(), dr.getSeq(), dr.getName(),
                    dr.getDirectPct(), dr.getTotalPct(), dr.getVotePct(),
                    dr.getControlChain(), dr.getBasis()
            };

            for (int ci = 0; ci < vals.length; ci++) {
                Cell cell = row.createCell(ci);
                cell.setCellValue(vals[ci]);
                if (ci == 7) {
                    cell.setCellStyle(wrapStyle);   // Control Chain wraps
                } else if (ci >= 4 && ci <= 6) {
                    cell.setCellStyle(centerStyle); // Ratios centered
                } else {
                    cell.setCellStyle(base);
                }
            }
        }

        // Column widths
        int[] colWidths = {30, 12, 6, 28, 14, 14, 14, 60, 40};
        for (int ci = 0; ci < colWidths.length; ci++) {
            ws.setColumnWidth(ci, colWidths[ci] * 256);
        }

        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            wb.write(fos);
        }
        wb.close();
    }

    /**
     * Applies border style to cell style with gray color.
     */
    private static void applyBorder(XSSFCellStyle style, BorderStyle bs) {
        style.setBorderTop(bs);
        style.setBorderBottom(bs);
        style.setBorderLeft(bs);
        style.setBorderRight(bs);
        // Gray border
        XSSFColor gray = new XSSFColor(new byte[]{(byte)0xCC,(byte)0xCC,(byte)0xCC}, null);
        style.setTopBorderColor(gray);
        style.setBottomBorderColor(gray);
        style.setLeftBorderColor(gray);
        style.setRightBorderColor(gray);

}}
