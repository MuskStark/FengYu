package fan.summer.fengyu.plugin.excel.ai;

import fan.summer.fengyu.plugin.excel.ExcelSessionStore;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelExecuteToolTest {
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
            s.createRow(1).createCell(0).setCellValue("east");
            wb.write(fos);
        }
    }

    @Test
    void analyzeConfigureExecuteBySheet() throws Exception {
        new ExcelAnalyzeTool(store).analyze(src.toString());
        String cfg = new ExcelConfigureTool(store).configure("BY_SHEET", null, null, null);
        assertTrue(cfg.contains("\"success\":true"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        String r = new ExcelExecuteTool(store).execute(out.toString(), "");
        assertTrue(r.contains("\"success\":true"));
        assertTrue(Files.exists(out.resolve("Alpha.xlsx")));
    }

    @Test
    void executeWithoutConfigErrors() {
        String r = new ExcelExecuteTool(store).execute("/tmp/x", "");
        assertTrue(r.contains("\"success\":false"));
    }
}
