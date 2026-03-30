package fan.summer.utils;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for Excel file operations.
 * Provides methods for appending sheets and data rows to Excel files.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/4
 */
public class ExcelUtil {
    private static final Logger logger = LoggerFactory.getLogger(ExcelUtil.class);

    private static final Set<String> INVALID_VALUES = Set.of(
            "NA", "N/A", "NULL", "NIL", "NONE", "NAN",
            "#N/A", "#NULL!", "#REF!", "#DIV/0!", "#VALUE!", "#NAME?", "#NUM!", "#ERROR!",
            "-", "--", "—", "N.A.", "N.A"
    );

    /**
     * Appends rows from 0 to endRowIndex of the specified sheet (by name) in source file to target file.
     * Creates target file if it doesn't exist; appends to existing sheet without overwriting.
     *
     * @param sourceFilePath source file path
     * @param targetFilePath target file path (creates if not exists)
     * @param sheetName      sheet name to copy
     * @param endRowIndex    end row index (0-based, inclusive)
     */
    public static void appendSheet(String sourceFilePath, String targetFilePath,
                                   String sheetName, int endRowIndex) throws IOException {
        if (endRowIndex < 0) throw new IllegalArgumentException("endRowIndex cannot be negative");

        // 1. Read source file and locate sheet
        try (FileInputStream srcFis = new FileInputStream(sourceFilePath);
             Workbook sourceWorkbook = WorkbookFactory.create(srcFis)) {

            Sheet sourceSheet = sourceWorkbook.getSheet(sheetName);
            if (sourceSheet == null) {
                throw new IllegalArgumentException("Sheet not found: " + sheetName
                        + ", available sheets: " + getSheetNames(sourceWorkbook));
            }

            // 2. Load or create target workbook
            Workbook targetWorkbook = loadOrCreate(targetFilePath);

            // 3. Target file must not have a sheet with the same name
            if (targetWorkbook.getSheet(sheetName) != null) {
                targetWorkbook.close();
                throw new IllegalArgumentException("Target file already has sheet: " + sheetName);
            }

            // 4. Append sheet
            copySheetRows(sourceSheet, targetWorkbook, sheetName, endRowIndex);

            // 5. Write back to target file
            try (FileOutputStream fos = new FileOutputStream(targetFilePath)) {
                targetWorkbook.write(fos);
            }
            targetWorkbook.close();

            logger.debug("Sheet appended | sheet={}, rows=0-{}, target={}", sheetName, endRowIndex, targetFilePath);
        }
    }

    /**
     * Appends rows from 0 to endRowIndex of the specified sheet (by index) in source file to target file.
     *
     * @param sourceFilePath source file path
     * @param targetFilePath target file path (creates if not exists)
     * @param sheetIndex     sheet index (0-based)
     * @param endRowIndex    end row index (0-based, inclusive)
     */
    public static void appendSheet(String sourceFilePath, String targetFilePath,
                                   int sheetIndex, int endRowIndex) throws IOException {
        if (endRowIndex < 0) throw new IllegalArgumentException("endRowIndex cannot be negative");

        try (FileInputStream srcFis = new FileInputStream(sourceFilePath);
             Workbook sourceWorkbook = WorkbookFactory.create(srcFis)) {

            if (sheetIndex < 0 || sheetIndex >= sourceWorkbook.getNumberOfSheets()) {
                throw new IllegalArgumentException("sheetIndex out of bounds: " + sheetIndex
                        + ", total sheets: " + sourceWorkbook.getNumberOfSheets());
            }

            Sheet sourceSheet = sourceWorkbook.getSheetAt(sheetIndex);
            appendSheet(sourceFilePath, targetFilePath, sourceSheet.getSheetName(), endRowIndex);
        }
    }

    /**
     * Appends data rows to an existing sheet in target file.
     *
     * @param orgFilePath   source file path (for getting style template)
     * @param targetFilePath target file path
     * @param sheetName     target sheet name
     * @param startRowIndex row index to start writing (typically header index)
     * @param rows          data rows to append
     */
    public static void appendDataRowsByPoi(
            String orgFilePath,
            String targetFilePath,
                                     String sheetName,
                                     int startRowIndex,
                                     List<Map<Integer, Object>> rows) throws IOException {
        // Get style template row from source file
        Row templateRow = null;
        try (FileInputStream srcFis = new FileInputStream(orgFilePath);
             Workbook srcWb = WorkbookFactory.create(srcFis)) {
            Sheet srcSheet = srcWb.getSheet(sheetName);
            if (srcSheet != null) {
                templateRow = srcSheet.getRow(startRowIndex);
            }
        }

        // Open target file for appending
        File file = new File(targetFilePath);
        Workbook workbook;
        try (FileInputStream fis = new FileInputStream(file)) {
            workbook = WorkbookFactory.create(fis);
        }
        try {

        Sheet sheet = workbook.getSheet(sheetName);
        Map<Integer, CellStyle> styleCache = new HashMap<>();
        final Row finalTemplateRow = templateRow;

        int rowIdx = startRowIndex;
        for (Map<Integer, Object> rowData : rows) {
            Row row = sheet.createRow(rowIdx++);
            rowData.forEach((colIdx, value) -> {
                Cell cell = row.createCell(colIdx);

                if (finalTemplateRow != null) {
                    Cell templateCell = finalTemplateRow.getCell(colIdx);
                    if (templateCell != null) {
                        CellStyle style = styleCache.computeIfAbsent(colIdx, k -> {
                            CellStyle newStyle = workbook.createCellStyle();
                            newStyle.cloneStyleFrom(templateCell.getCellStyle());
                            return newStyle;
                        });
                        cell.setCellStyle(style);
                    }
                }

                if (value instanceof Number) {
                    cell.setCellValue(((Number) value).doubleValue());
                } else if (value instanceof Boolean) {
                    cell.setCellValue((Boolean) value);
                } else if (value != null) {
                    cell.setCellValue(value.toString());
                }
            });
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        } finally {
            workbook.close();
        }
    }

    /**
     * Normalizes the input value by trimming whitespace, or returns "INVALID" if the value
     * is null, empty, or matches a known invalid data marker.
     *
     * @param val the input value to validate
     * @return the trimmed string if valid, or "INVALID" if null/empty/invalid marker
     */
    public static String normalizeOrInvalid(Object val){
        if (val == null) return "INVALID";
        String str = val.toString().trim();
        if (str.isEmpty() || INVALID_VALUES.contains(str.toUpperCase())) {
            return "INVALID";
        }
        return str;
    }

    // ==================== Private Helper Methods ====================

    /**
     * Loads existing workbook or creates new one if file doesn't exist.
     */
    private static Workbook loadOrCreate(String targetFilePath) throws IOException {
        File file = new File(targetFilePath);
        if (file.exists() && file.length() > 0) {
            try (FileInputStream fis = new FileInputStream(file)) {
                return WorkbookFactory.create(fis);
            }
        }
        return new XSSFWorkbook();
    }

    /**
     * Copies rows 0 to endRowIndex from sourceSheet to a new sheet in targetWorkbook.
     */
    private static void copySheetRows(Sheet sourceSheet, Workbook targetWorkbook,
                                      String targetSheetName, int endRowIndex) {
        Sheet targetSheet = targetWorkbook.createSheet(targetSheetName);
        copyColumnWidths(sourceSheet, targetSheet);
        targetSheet.setDefaultRowHeight(sourceSheet.getDefaultRowHeight());

        Map<Integer, CellStyle> styleCache = new HashMap<>();
        int actualEnd = Math.min(endRowIndex, sourceSheet.getLastRowNum());

        for (int rowIdx = 0; rowIdx <= actualEnd; rowIdx++) {
            Row srcRow = sourceSheet.getRow(rowIdx);
            if (srcRow == null) continue;
            Row dstRow = targetSheet.createRow(rowIdx);
            dstRow.setHeight(srcRow.getHeight());
            copyCells(srcRow, dstRow, targetWorkbook, styleCache);
        }

        copyMergedRegions(sourceSheet, targetSheet, actualEnd);
    }

    private static void copyCells(Row srcRow, Row dstRow,
                                  Workbook targetWb,
                                  Map<Integer, CellStyle> styleCache) {
        for (int col = srcRow.getFirstCellNum(); col < srcRow.getLastCellNum(); col++) {
            Cell src = srcRow.getCell(col);
            if (src == null) continue;

            Cell dst = dstRow.createCell(col);

            CellStyle style = styleCache.computeIfAbsent(
                    src.getCellStyle().hashCode(), k -> {
                        CellStyle s = targetWb.createCellStyle();
                        s.cloneStyleFrom(src.getCellStyle());
                        return s;
                    });
            dst.setCellStyle(style);

            switch (src.getCellType()) {
                case STRING:
                    dst.setCellValue(src.getStringCellValue());
                    break;
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(src)) {
                        dst.setCellValue(src.getDateCellValue());
                    } else {
                        dst.setCellValue(src.getNumericCellValue());
                    }
                    break;
                case BOOLEAN:
                    dst.setCellValue(src.getBooleanCellValue());
                    break;
                case FORMULA:
                    dst.setCellFormula(src.getCellFormula());
                    break;
                case BLANK:
                    dst.setBlank();
                    break;
                case ERROR:
                    dst.setCellErrorValue(src.getErrorCellValue());
                    break;
                default:
                    break;
            }
        }
    }

    private static void copyColumnWidths(Sheet src, Sheet dst) {
        int maxCol = 0;
        for (Row row : src) {
            if (row.getLastCellNum() > maxCol) maxCol = row.getLastCellNum();
        }
        dst.setDefaultColumnWidth(src.getDefaultColumnWidth());
        for (int i = 0; i < maxCol; i++) {
            dst.setColumnWidth(i, src.getColumnWidth(i));
        }
    }

    private static void copyMergedRegions(Sheet src, Sheet dst, int endRow) {
        for (int i = 0; i < src.getNumMergedRegions(); i++) {
            CellRangeAddress region = src.getMergedRegion(i);
            if (region.getLastRow() <= endRow) {
                dst.addMergedRegion(region.copy());
            }
        }
    }

    private static String getSheetNames(Workbook workbook) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(workbook.getSheetName(i));
        }
        return sb.append("]").toString();
    }
}