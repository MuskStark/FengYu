package fan.summer.buildintool.excelsplitter;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Excel split core logic.
 * Does not depend on JavaFX, can be safely called in background threads.
 *
 * Progress callback: BiConsumer<current progress (0~1), status description>
 */
public class ExcelSplitter {

    public record SplitResult(int fileCount, List<Path> outputFiles) {}

    private final SplitConfig           config;
    private final BiConsumer<Double, String> progress;

    public ExcelSplitter(SplitConfig config, BiConsumer<Double, String> progress) {
        this.config   = config;
        this.progress = progress;
    }

    public SplitResult split() throws Exception {
        progress.accept(0.0, "Reading file…");

        try (Workbook src = WorkbookFactory.create(config.sourceFile.toFile(), null, true)) {
            return switch (config.mode) {
                case BY_SHEET      -> splitBySheet(src);
                case BY_COLUMN     -> splitByColumn(src);
                case BY_ROW_COUNT  -> splitByRowCount(src);
            };
        }
    }

    // ── Split by sheet ─────────────────────────────────────

    private SplitResult splitBySheet(Workbook src) throws Exception {
        List<String> targets = config.selectedSheets != null
            ? config.selectedSheets
            : config.sheetNames;

        List<Path> outputs = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            String name = targets.get(i);
            progress.accept((double) i / targets.size(), "Processing sheet：" + name);

            Sheet sheet = src.getSheet(name);
            if (sheet == null) continue;

            Path out = outputPath(sanitize(name));
            try (Workbook wb = new XSSFWorkbook();
                 FileOutputStream fos = new FileOutputStream(out.toFile())) {
                copySheet(src, sheet, wb);
                wb.write(fos);
            }
            outputs.add(out);
        }
        progress.accept(1.0, "Done");
        return new SplitResult(outputs.size(), outputs);
    }

    // ── Split by column value ────────────────────────────────────────

    private SplitResult splitByColumn(Workbook src) throws Exception {
        Sheet sheet = src.getSheetAt(0);
        Row header  = config.keepHeader ? sheet.getRow(sheet.getFirstRowNum()) : null;
        int  colIdx = config.splitColumnIndex;

        // Group rows by column value
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (int r = sheet.getFirstRowNum() + (config.keepHeader ? 1 : 0);
             r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String key = cellValue(row.getCell(colIdx));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<Path> outputs = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, List<Row>> entry : groups.entrySet()) {
            progress.accept((double) i / groups.size(), "Writing：" + entry.getKey());
            Path out = outputPath(sanitize(entry.getKey()));
            writeRows(src, sheet, header, entry.getValue(), out);
            outputs.add(out);
            i++;
        }
        progress.accept(1.0, "Done");
        return new SplitResult(outputs.size(), outputs);
    }

    // ── Split by row count ────────────────────────────────────────

    private SplitResult splitByRowCount(Workbook src) throws Exception {
        Sheet sheet   = src.getSheetAt(0);
        Row   header  = config.keepHeader ? sheet.getRow(sheet.getFirstRowNum()) : null;
        int   start   = sheet.getFirstRowNum() + (config.keepHeader ? 1 : 0);
        int   total   = sheet.getLastRowNum() - start + 1;
        int   batchSz = config.rowsPerFile;

        List<Path> outputs = new ArrayList<>();
        int part = 1;
        for (int r = start; r <= sheet.getLastRowNum(); r += batchSz) {
            progress.accept((double)(r - start) / total, "Writing part " + part + "…");
            List<Row> batch = new ArrayList<>();
            for (int rr = r; rr < r + batchSz && rr <= sheet.getLastRowNum(); rr++) {
                Row row = sheet.getRow(rr);
                if (row != null) batch.add(row);
            }
            Path out = outputPath("part_" + String.format("%03d", part));
            writeRows(src, sheet, header, batch, out);
            outputs.add(out);
            part++;
        }
        progress.accept(1.0, "Done");
        return new SplitResult(outputs.size(), outputs);
    }

    // ── Helper: write rows ─────────────────────────────────────

    private void writeRows(Workbook src, Sheet srcSheet,
                           Row header, List<Row> rows, Path dest) throws Exception {
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(dest.toFile())) {
            Sheet out = wb.createSheet(srcSheet.getSheetName());
            int rowNum = 0;
            if (header != null) {
                copyRow(src, wb, header, out.createRow(rowNum++));
            }
            for (Row src_row : rows) {
                copyRow(src, wb, src_row, out.createRow(rowNum++));
            }
            // Auto column width (only first 50 columns to avoid performance issues)
            int cols = Math.min(srcSheet.getRow(srcSheet.getFirstRowNum()) != null
                ? srcSheet.getRow(srcSheet.getFirstRowNum()).getLastCellNum() : 10, 50);
            for (int c = 0; c < cols; c++) out.autoSizeColumn(c);
            wb.write(fos);
        }
    }

    // ── Helper: sheet copy ─────────────────────────────────

    private void copySheet(Workbook srcWb, Sheet src, Workbook destWb) {
        Sheet dest = destWb.createSheet(src.getSheetName());
        for (int r = src.getFirstRowNum(); r <= src.getLastRowNum(); r++) {
            Row srcRow = src.getRow(r);
            if (srcRow == null) continue;
            copyRow(srcWb, destWb, srcRow, dest.createRow(r));
        }
        // Column width
        int cols = src.getRow(src.getFirstRowNum()) != null
            ? src.getRow(src.getFirstRowNum()).getLastCellNum() : 0;
        for (int c = 0; c < Math.min(cols, 50); c++) dest.autoSizeColumn(c);
    }

    private void copyRow(Workbook srcWb, Workbook destWb, Row src, Row dest) {
        dest.setHeight(src.getHeight());
        for (int c = src.getFirstCellNum(); c < src.getLastCellNum(); c++) {
            Cell srcCell  = src.getCell(c);
            Cell destCell = dest.createCell(c);
            if (srcCell == null) continue;
            copyCellValue(srcCell, destCell);
        }
    }

    private void copyCellValue(Cell src, Cell dest) {
        switch (src.getCellType()) {
            case STRING  -> dest.setCellValue(src.getStringCellValue());
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(src))
                    dest.setCellValue(src.getDateCellValue());
                else
                    dest.setCellValue(src.getNumericCellValue());
            }
            case BOOLEAN -> dest.setCellValue(src.getBooleanCellValue());
            case FORMULA -> {
                switch (src.getCachedFormulaResultType()) {
                    case NUMERIC  -> dest.setCellValue(src.getNumericCellValue());
                    case STRING   -> dest.setCellValue(src.getStringCellValue());
                    case BOOLEAN  -> dest.setCellValue(src.getBooleanCellValue());
                    default       -> dest.setCellFormula(src.getCellFormula());
                }
            }
            case BLANK   -> dest.setBlank();
            default      -> dest.setCellValue(src.toString());
        }
    }

    // ── Helper: utility methods ────────────────────────────────────

    private String cellValue(Cell cell) {
        if (cell == null) return "(empty)";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default      -> cell.toString().trim();
        };
    }

    /** Strip characters that are illegal in file names */
    private String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private Path outputPath(String name) {
        String prefix = config.filePrefix.isBlank() ? "" : config.filePrefix + "_";
        return config.outputDir.resolve(prefix + name + ".xlsx");
    }
}
