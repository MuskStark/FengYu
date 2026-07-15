package fan.summer.fengyu.plugin.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the stateless AI tools ({@code excel_*}) now that they live on
 * {@link ExcelRpcHandlers} instead of Spring AI {@code @Tool} beans. Each handler
 * returns the {success, summary, ...} envelope as a Map.
 */
class ExcelRpcHandlersTest {
    @TempDir Path tmp;
    Path src;
    ExcelRpcHandlers handlers;
    ExcelSessionStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new ExcelSessionStore();
        handlers = new ExcelRpcHandlers(store);
        src = tmp.resolve("in.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(src.toFile())) {
            Sheet s = wb.createSheet("Alpha");
            s.createRow(0).createCell(0).setCellValue("region");
            s.createRow(1).createCell(0).setCellValue("east");
            wb.write(fos);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeThenQueryThenCancel() {
        Map<String, Object> a = (Map<String, Object>) handlers.aiAnalyze(Map.of("filePath", src.toString()));
        assertEquals(Boolean.TRUE, a.get("success"));

        Map<String, Object> q = (Map<String, Object>) handlers.aiQuery(Map.of());
        String sourceFile = String.valueOf(((Map<String, Object>) q.get("state")).get("sourceFile"));
        assertTrue(sourceFile.endsWith(src.getFileName().toString()));

        Map<String, Object> c = (Map<String, Object>) handlers.aiCancel(Map.of());
        assertEquals(Boolean.TRUE, c.get("success"));
        assertTrue(store.active().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeMissingFileErrors() {
        Map<String, Object> a = (Map<String, Object>) handlers.aiAnalyze(Map.of("filePath", "/no/such/file.xlsx"));
        assertEquals(Boolean.FALSE, a.get("success"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeConfigureExecuteBySheet() throws Exception {
        handlers.aiAnalyze(Map.of("filePath", src.toString()));
        Map<String, Object> cfg = (Map<String, Object>) handlers.aiConfigure(Map.of("mode", "BY_SHEET"));
        assertEquals(Boolean.TRUE, cfg.get("success"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Map<String, Object> r = (Map<String, Object>) handlers.aiExecute(Map.of("outputDir", out.toString()));
        assertEquals(Boolean.TRUE, r.get("success"));
        assertTrue(Files.exists(out.resolve("Alpha.xlsx")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeWithoutConfigErrors() {
        Map<String, Object> r = (Map<String, Object>) handlers.aiExecute(Map.of("outputDir", "/tmp/x"));
        assertEquals(Boolean.FALSE, r.get("success"));
    }
}
