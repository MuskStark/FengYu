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
}
