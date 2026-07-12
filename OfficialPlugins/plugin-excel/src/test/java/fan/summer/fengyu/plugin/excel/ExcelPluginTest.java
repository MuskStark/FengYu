package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.api.ToolCategory;
import fan.summer.fengyu.api.plugin.PluginDescriptor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelPluginTest {
    @TempDir Path tmp;
    Path src;
    ExcelPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        plugin = new ExcelPlugin(new ExcelSessionStore());
        src = tmp.resolve("in.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(src.toFile())) {
            Sheet s = wb.createSheet("Alpha");
            s.createRow(0).createCell(0).setCellValue("region");
            s.createRow(1).createCell(0).setCellValue("east");
            wb.write(fos);
        }
    }

    @Test
    void descriptorIsOfficialFileCategory() {
        PluginDescriptor d = plugin.descriptor();
        assertEquals("fan.summer.excel", d.id());
        assertEquals(ToolCategory.FILE, d.category());
        assertEquals("/plugin-ui/excel/index.js", d.uiEntry());
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeThenSplitBySheet() throws Exception {
        String sess = "s1";
        Map<String, Object> a = (Map<String, Object>) plugin.invoke("analyze",
            Map.of("session", sess, "sourceFile", src.toString()));
        assertEquals(Boolean.TRUE, a.get("success"));

        plugin.invoke("configure", Map.of("session", sess, "mode", "BY_SHEET"));

        Path out = Files.createDirectories(tmp.resolve("out"));
        Map<String, Object> r = (Map<String, Object>) plugin.invoke("split",
            Map.of("session", sess, "sourceFile", src.toString(), "outputDir", out.toString()));
        assertEquals(Boolean.TRUE, r.get("success"));
        assertEquals(1, ((Number) r.get("fileCount")).intValue());
    }

    @Test
    void unknownActionThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> plugin.invoke("bogus", Map.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void configureByColumnResolvesIndex() throws Exception {
        // Build a workbook whose header row has "region" at column index 1 (not 0),
        // with 3 data rows spanning 2 distinct region values.
        Path byCol = tmp.resolve("byCol.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(byCol.toFile())) {
            Sheet s = wb.createSheet("Data");
            Row header = s.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("region");
            Row r1 = s.createRow(1);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("east");
            Row r2 = s.createRow(2);
            r2.createCell(0).setCellValue(2);
            r2.createCell(1).setCellValue("west");
            Row r3 = s.createRow(3);
            r3.createCell(0).setCellValue(3);
            r3.createCell(1).setCellValue("east");
            wb.write(fos);
        }

        String sess = "s2";
        plugin.invoke("analyze", Map.of("session", sess, "sourceFile", byCol.toString()));

        // Deliberately omit splitColumnIndex — only the header TEXT is supplied, as the UI does.
        plugin.invoke("configure", Map.of(
            "session", sess, "mode", "BY_COLUMN",
            "splitSheet", "Data", "splitColumn", "region"));

        Path out = Files.createDirectories(tmp.resolve("outByCol"));
        Map<String, Object> r = (Map<String, Object>) plugin.invoke("split",
            Map.of("session", sess, "sourceFile", byCol.toString(), "outputDir", out.toString()));

        assertEquals(Boolean.TRUE, r.get("success"));
        // 2 distinct region values (east, west) => 2 output files. Under the old bug
        // (splitColumnIndex stuck at -1), every row collapses into a single "INVALID"
        // group and fileCount would be 1.
        assertEquals(2, ((Number) r.get("fileCount")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void splitCreatesMissingOutputDir() throws Exception {
        String sess = "s3";
        plugin.invoke("analyze", Map.of("session", sess, "sourceFile", src.toString()));
        plugin.invoke("configure", Map.of("session", sess, "mode", "BY_SHEET"));

        // Path deliberately does not exist yet; Fesod's writer does not create parent
        // dirs, so split() must create them itself before writing.
        Path out = tmp.resolve("does/not/exist/yet");
        assertFalse(Files.exists(out));

        Map<String, Object> r = (Map<String, Object>) plugin.invoke("split",
            Map.of("session", sess, "sourceFile", src.toString(), "outputDir", out.toString()));

        assertEquals(Boolean.TRUE, r.get("success"));
        assertEquals(1, ((Number) r.get("fileCount")).intValue());
        List<String> files = (List<String>) r.get("files");
        assertEquals(1, files.size());
        assertTrue(Files.exists(out.resolve(files.get(0))));
    }
}
