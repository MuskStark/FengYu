package fan.summer.fengyu.plugin.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelSplitterTest {

    @TempDir Path tmp;
    Path src;

    @BeforeEach
    void setUp() throws Exception {
        src = tmp.resolve("in.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(src.toFile())) {
            Sheet s1 = wb.createSheet("Alpha");
            Row h1 = s1.createRow(0); h1.createCell(0).setCellValue("region"); h1.createCell(1).setCellValue("v");
            String[] regions = {"east", "west", "east"};
            for (int i = 0; i < regions.length; i++) {
                Row r = s1.createRow(i + 1); r.createCell(0).setCellValue(regions[i]); r.createCell(1).setCellValue(i);
            }
            Sheet s2 = wb.createSheet("Beta");
            s2.createRow(0).createCell(0).setCellValue("name");
            s2.createRow(1).createCell(0).setCellValue("x");
            wb.write(fos);
        }
    }

    @Test
    void analyzeReturnsSheetsAndHeaders() throws Exception {
        Map<String, Map<Integer, String>> r = ExcelSplitter.analyze(src);
        assertEquals(List.of("Alpha", "Beta"), new ArrayList<>(r.keySet()));
        assertEquals("region", r.get("Alpha").get(0));
        assertEquals("name", r.get("Beta").get(0));
    }

    @Test
    void bySheetProducesOneFilePerSheet() throws Exception {
        SplitConfig c = new SplitConfig();
        c.sourceFile = src;
        c.analysisResult = ExcelSplitter.analyze(src);
        c.mode = SplitConfig.SplitMode.BY_SHEET;
        c.outputDir = Files.createDirectories(tmp.resolve("out1"));
        var res = new ExcelSplitter(c, null).split();
        assertEquals(2, res.fileCount());
        assertTrue(Files.exists(c.outputDir.resolve("Alpha.xlsx")));
        assertTrue(Files.exists(c.outputDir.resolve("Beta.xlsx")));
    }

    @Test
    void byColumnGroupsByUniqueValue() throws Exception {
        SplitConfig c = new SplitConfig();
        c.sourceFile = src;
        c.analysisResult = ExcelSplitter.analyze(src);
        c.mode = SplitConfig.SplitMode.BY_COLUMN;
        c.splitSheet = "Alpha";
        c.splitColumnIndex = 0;
        c.outputDir = Files.createDirectories(tmp.resolve("out2"));
        var res = new ExcelSplitter(c, null).split();
        assertEquals(2, res.fileCount()); // east, west
    }

    @Test
    void complexSplitNormalPlusCopyAll() throws Exception {
        // Reuse the fixture built in setUp(): "Alpha" (region/v, 2 distinct region
        // values -> east, west) is the normal split entry; "Beta" is copied whole
        // into every output file produced by the normal entry.
        SplitConfig c = new SplitConfig();
        c.sourceFile = src;
        c.analysisResult = ExcelSplitter.analyze(src);
        c.mode = SplitConfig.SplitMode.COMPLEX;
        c.outputDir = Files.createDirectories(tmp.resolve("out3"));
        c.complexEntries = List.of(
                // 1-based header row (row 0) and 1-based split column ("region", col 0)
                new ComplexSplitEntry("in.xlsx", "Alpha", 1, 1),
                // headerIndex == -1 && columnIndex == -1 => copy the entire "Beta" sheet
                new ComplexSplitEntry("in.xlsx", "Beta", -1, -1)
        );

        var res = new ExcelSplitter(c, null).split();
        assertEquals(2, res.fileCount()); // east, west

        // Engine's real COMPLEX naming: <stem(sourceFile)>_<value>.xlsx (no filePrefix applied)
        Path eastFile = c.outputDir.resolve("in_east.xlsx");
        Path westFile = c.outputDir.resolve("in_west.xlsx");
        assertTrue(Files.exists(eastFile), "expected " + eastFile);
        assertTrue(Files.exists(westFile), "expected " + westFile);

        for (Path out : List.of(eastFile, westFile)) {
            try (Workbook wb = WorkbookFactory.create(out.toFile())) {
                assertNotNull(wb.getSheet("Alpha"), "split sheet missing in " + out.getFileName());
                assertNotNull(wb.getSheet("Beta"), "copy-all sheet missing in " + out.getFileName());
            }
        }
    }

    // ---- sanitized output names: cell values become filename segments ----

    @Test
    void byColumnSanitizesKeysIntoSafeFilenames() throws Exception {
        // A crafted region value must become a sanitized in-directory filename, never an
        // output path escaping outputDir or a name broken on Windows.
        Path evil = tmp.resolve("evil.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(evil.toFile())) {
            Sheet s = wb.createSheet("Data");
            Row header = s.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("region");
            s.createRow(1).createCell(1).setCellValue("../../evil");
            s.createRow(2).createCell(1).setCellValue("ok");
            wb.write(fos);
        }
        SplitConfig c = new SplitConfig();
        c.sourceFile = evil;
        c.analysisResult = ExcelSplitter.analyze(evil);
        c.mode = SplitConfig.SplitMode.BY_COLUMN;
        c.splitSheet = "Data";
        c.splitColumnIndex = 1;
        c.outputDir = Files.createDirectories(tmp.resolve("out-evil"));
        var res = new ExcelSplitter(c, null).split();
        assertEquals(2, res.fileCount());
        assertTrue(Files.exists(c.outputDir.resolve("evil_.._.._evil.xlsx")));
        assertTrue(Files.exists(c.outputDir.resolve("evil_ok.xlsx")));
        // Un-sanitized, the first key would have escaped the output directory.
        assertFalse(Files.exists(c.outputDir.resolve("../../evil.xlsx").normalize()));
    }

    // ---- failed/cancelled splits leave no partial outputs ----

    /** Normal complex entry on Alpha/region (2 groups: east, west), no copy-all rules. */
    private SplitConfig complexConfig(Path outDir) throws Exception {
        SplitConfig c = new SplitConfig();
        c.sourceFile = src;
        c.analysisResult = ExcelSplitter.analyze(src);
        c.mode = SplitConfig.SplitMode.COMPLEX;
        c.outputDir = Files.createDirectories(outDir);
        c.complexEntries = List.of(new ComplexSplitEntry("in.xlsx", "Alpha", 1, 1));
        return c;
    }

    @Test
    void failedComplexSplitDeletesPartialOutputs() throws Exception {
        SplitConfig c = complexConfig(tmp.resolve("out-cpx-fail"));
        // A pre-created DIRECTORY at the second planned output makes Phase 2's second
        // FileOutputStream fail deterministically AFTER the first file was written.
        Files.createDirectory(c.outputDir.resolve("in_west.xlsx"));
        assertThrows(Exception.class, () -> new ExcelSplitter(c, null).split());
        assertFalse(Files.exists(c.outputDir.resolve("in_east.xlsx")),
            "partial output from a failed split must be deleted");
    }

    @Test
    void cancelledComplexSplitDeletesPartialOutputs() throws Exception {
        SplitConfig c = complexConfig(tmp.resolve("out-cpx-cancel"));
        // Cancel as soon as the FIRST Phase-2 output lands: the next checkpoint (inside the
        // Phase-2 write loop) aborts, and the cleanup must remove the already-written file.
        Path east = c.outputDir.resolve("in_east.xlsx");
        ExcelSplitter splitter = new ExcelSplitter(c, null, () -> Files.exists(east));
        assertThrows(InterruptedException.class, () -> splitter.split());
        assertFalse(Files.exists(east), "partial output from a cancelled split must be deleted");
    }

    @Test
    void failedBySheetSplitDeletesPartialOutputs() throws Exception {
        SplitConfig c = new SplitConfig();
        c.sourceFile = src;
        c.analysisResult = ExcelSplitter.analyze(src);
        c.mode = SplitConfig.SplitMode.BY_SHEET;
        c.outputDir = Files.createDirectories(tmp.resolve("out-sheet-fail"));
        // A directory at Beta.xlsx makes the SECOND sheet write fail after Alpha.xlsx landed.
        Files.createDirectory(c.outputDir.resolve("Beta.xlsx"));
        assertThrows(Exception.class, () -> new ExcelSplitter(c, null).split());
        assertFalse(Files.exists(c.outputDir.resolve("Alpha.xlsx")),
            "partial output from a failed split must be deleted");
    }

    @Test
    void failedByColumnSplitDeletesPartialOutputs() throws Exception {
        SplitConfig c = new SplitConfig();
        c.sourceFile = src;
        c.analysisResult = ExcelSplitter.analyze(src);
        c.mode = SplitConfig.SplitMode.BY_COLUMN;
        c.splitSheet = "Alpha";
        c.splitColumnIndex = 0;
        c.outputDir = Files.createDirectories(tmp.resolve("out-col-fail"));
        // One parallel writer fails (directory at its output); whatever the split managed to
        // create must be gone once the failure surfaces (cleanup runs after the pool quiesces).
        Files.createDirectory(c.outputDir.resolve("in_west.xlsx"));
        assertThrows(Exception.class, () -> new ExcelSplitter(c, null).split());
        assertFalse(Files.exists(c.outputDir.resolve("in_east.xlsx")),
            "partial output from a failed split must be deleted");
    }

    // ---- clear errors for stale selections ----

    @Test
    void bySheetUnknownSelectionThrowsClearLocalizedError() throws Exception {
        SplitConfig c = new SplitConfig();
        c.sourceFile = src;
        c.analysisResult = ExcelSplitter.analyze(src);
        c.mode = SplitConfig.SplitMode.BY_SHEET;
        c.selectedSheets = List.of("Alpha", "Ghost");
        c.outputDir = Files.createDirectories(tmp.resolve("out-ghost"));
        // Pre-fix this was a bare NullPointerException (TreeMap(null) in buildHeaders).
        Exception e = assertThrows(RuntimeException.class, () -> new ExcelSplitter(c, null).split());
        assertTrue(e.getMessage().contains("Ghost"), e.getMessage());
    }
}
