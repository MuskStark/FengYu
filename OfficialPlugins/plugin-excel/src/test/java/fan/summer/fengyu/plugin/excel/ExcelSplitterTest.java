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
}
