package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.CancellationToken;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.excel.generated.ExcelAnalyzeInput;
import fan.summer.excel.generated.ExcelAnalyzeOutput;
import fan.summer.excel.generated.ExcelCancelInput;
import fan.summer.excel.generated.ExcelCancelOutput;
import fan.summer.excel.generated.ExcelConfigureInput;
import fan.summer.excel.generated.ExcelConfigureOutput;
import fan.summer.excel.generated.ExcelExecuteInput;
import fan.summer.excel.generated.ExcelExecuteOutput;
import fan.summer.excel.generated.ExcelQueryInput;
import fan.summer.excel.generated.ExcelQueryOutput;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the stateless AI tools ({@code excel_*}) on the typed {@link ExcelRpcHandlers}. Each handler
 * consumes a generated {@code *Input} record and returns the matching {@code *Output} record; a
 * success/failure is carried in-band by the record's {@code success} flag rather than a Map envelope.
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

    /** A non-cancelled per-call context, mirroring what the worker dispatch loop binds. */
    private static RpcContext ctx() {
        return new RpcContext("test", null, null, "en", new CancellationToken(), null);
    }

    @Test
    void analyzeThenQueryThenCancel() {
        ExcelAnalyzeOutput a = handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString()), ctx());
        assertTrue(a.success());

        ExcelQueryOutput q = handlers.aiQuery(new ExcelQueryInput(), ctx());
        assertNotNull(q.state());
        assertEquals(src.toString(), q.state().sourceFile());

        ExcelCancelOutput c = handlers.aiCancel(new ExcelCancelInput(), ctx());
        assertTrue(c.success());
        assertTrue(store.active().isEmpty());
    }

    @Test
    void analyzeMissingFileErrors() {
        ExcelAnalyzeOutput a = handlers.aiAnalyze(new ExcelAnalyzeInput("/no/such/file.xlsx"), ctx());
        assertFalse(a.success());
    }

    @Test
    void executeRejectsBlankOutputDir() {
        // analyze + configure first so the handler reaches the outputDir validation.
        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString()), ctx());
        handlers.aiConfigure(new ExcelConfigureInput("BY_SHEET", null, null, null), ctx());
        ExcelExecuteOutput r = handlers.aiExecute(new ExcelExecuteInput(null, "  "), ctx());
        assertFalse(r.success());
    }

    @Test
    void analyzeConfigureExecuteBySheet() throws Exception {
        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString()), ctx());
        ExcelConfigureOutput cfg = handlers.aiConfigure(new ExcelConfigureInput("BY_SHEET", null, null, null), ctx());
        assertTrue(cfg.success());
        Path out = Files.createDirectories(tmp.resolve("out"));
        ExcelExecuteOutput r = handlers.aiExecute(new ExcelExecuteInput(null, out.toString()), ctx());
        assertTrue(r.success());
        assertTrue(Files.exists(out.resolve("Alpha.xlsx")));
    }

    @Test
    void executeWithoutConfigErrors() {
        ExcelExecuteOutput r = handlers.aiExecute(new ExcelExecuteInput(null, "/tmp/x"), ctx());
        assertFalse(r.success());
    }
}
