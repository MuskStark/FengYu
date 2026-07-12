package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExcelAiToolsTest {
    @TempDir Path tmp;
    Path src;
    ExcelSessionStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new ExcelSessionStore();
        src = tmp.resolve("in.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(src.toFile())) {
            Sheet s = wb.createSheet("Alpha");
            s.createRow(0).createCell(0).setCellValue("region");
            wb.write(fos);
        }
    }

    @Test
    void analyzeThenQueryThenCancel() {
        String a = new ExcelAnalyzeTool(store).analyze(src.toString());
        assertTrue(a.contains("\"success\":true"));
        String q = new ExcelQueryTool(store).query();
        assertTrue(q.contains(src.getFileName().toString()));
        String c = new ExcelCancelTool(store).cancel();
        assertTrue(c.contains("\"success\":true"));
        assertTrue(store.active().isEmpty());
    }

    @Test
    void analyzeMissingFileErrors() {
        String a = new ExcelAnalyzeTool(store).analyze("/no/such/file.xlsx");
        assertTrue(a.contains("\"success\":false"));
    }
}
