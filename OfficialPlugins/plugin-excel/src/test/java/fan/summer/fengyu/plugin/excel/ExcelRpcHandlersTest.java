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
import fan.summer.excel.generated.ExcelExecuteStartInput;
import fan.summer.excel.generated.ExcelExecuteStartOutput;
import fan.summer.excel.generated.ExcelExecuteStatusInput;
import fan.summer.excel.generated.ExcelExecuteStatusOutput;
import fan.summer.excel.generated.ExcelQueryInput;
import fan.summer.excel.generated.ExcelQueryOutput;
import fan.summer.excel.generated.SplitCancelInput;
import fan.summer.excel.generated.SplitCancelOutput;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null), ctx());
        ExcelExecuteOutput r = handlers.aiExecute(new ExcelExecuteInput(null, "  "), ctx());
        assertFalse(r.success());
    }

    @Test
    void analyzeConfigureExecuteBySheet() throws Exception {
        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString()), ctx());
        ExcelConfigureOutput cfg = handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null), ctx());
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

    // ---- T2-P2 supplementary coverage: large workbook, status cursor, repeated cancel ----

    /** Large-workbook correctness: a multi-sheet workbook split BY_SHEET yields one file per sheet. */
    @Test
    void largeWorkbookSplitsBySheetCorrectly() throws Exception {
        Path big = writeWorkbook(tmp.resolve("big.xlsx"), 12);
        handlers.aiAnalyze(new ExcelAnalyzeInput(big.toString()), ctx());
        ExcelConfigureOutput cfg = handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null), ctx());
        assertTrue(cfg.success());
        Path out = Files.createDirectories(tmp.resolve("large-out"));
        ExcelExecuteOutput r = handlers.aiExecute(new ExcelExecuteInput(null, out.toString()), ctx());
        assertTrue(r.success(), r.summary());
        assertNotNull(r.files());
        assertEquals(12, r.files().fileCount(), "one output file per sheet");
        for (int i = 0; i < 12; i++) assertTrue(Files.exists(out.resolve("Sheet" + i + ".xlsx")));
    }

    /**
     * Status-cursor pagination: polling {@code excel_execute_status} with the cursor returned by the
     * previous poll drains streamed progress without re-delivering any line. The job runs on a virtual
     * thread and may finish before the first poll, so the contract verified is cursor monotonicity and
     * zero overlap across polls (a fast completion simply yields fewer polls).
     */
    @Test
    void statusCursorDrainsProgressWithoutRedelivery() throws Exception {
        Path big = writeWorkbook(src, 15); // reuse session fixture path; overwrite with many sheets
        handlers.aiAnalyze(new ExcelAnalyzeInput(big.toString()), ctx());
        handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null), ctx());
        Path out = Files.createDirectories(tmp.resolve("cursor-out"));
        ExcelExecuteStartOutput start = handlers.aiExecuteStart(new ExcelExecuteStartInput(null, out.toString()), ctx());
        assertTrue(start.success(), start.summary());
        String jobId = start.jobId();
        assertNotNull(jobId);

        int cursor = 0;
        int prevCursor = -1;
        List<String> allDelivered = new ArrayList<>();
        boolean done = false;
        for (int poll = 0; poll < 200 && !done; poll++) {
            ExcelExecuteStatusOutput s = handlers.aiExecuteStatus(new ExcelExecuteStatusInput(cursor, jobId), ctx());
            assertTrue(s.success(), s.summary());
            assertTrue(s.cursor() >= prevCursor, "cursor must be monotonic non-decreasing");
            prevCursor = s.cursor();
            cursor = s.cursor() != null ? s.cursor() : cursor;
            if (s.logs() != null) allDelivered.addAll(s.logs());
            done = Boolean.TRUE.equals(s.done());
            if (!done) Thread.sleep(5);
        }
        assertTrue(done, "job must reach a terminal state");
        // No line is ever delivered twice: the cumulative tail across polls has no duplicates.
        Set<String> unique = new HashSet<>(allDelivered);
        assertEquals(unique.size(), allDelivered.size(), "status cursor re-delivered log lines");
    }

    /**
     * Repeated cancel is safe and terminates the job: {@code split_cancel} may be called any number of
     * times on the same jobId. {@code Jobs.cancel()} is idempotent in effect (it sets a flag the runner
     * honours at its next checkpoint) and returns true while the handle still exists, so two rapid
     * calls can both report success — the contract is that neither throws and the job reaches a
     * terminal state. The stronger "first cancel wins, second reports not-running" invariant is
     * pinned deterministically at the {@code Jobs} registry level (see JobsCancellationTest).
     */
    @Test
    void repeatedCancelIsSafeAndTerminates() throws Exception {
        Path big = writeWorkbook(src, 20); // reuse the known-good session fixture path
        handlers.aiAnalyze(new ExcelAnalyzeInput(big.toString()), ctx());
        handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null), ctx());
        Path out = Files.createDirectories(tmp.resolve("cancel-out"));
        ExcelExecuteStartOutput start = handlers.aiExecuteStart(new ExcelExecuteStartInput(null, out.toString()), ctx());
        assertTrue(start.success(), start.summary());
        String jobId = start.jobId();

        // Repeated cancel must never throw, regardless of timing.
        SplitCancelOutput first = handlers.splitCancel(new SplitCancelInput(jobId), ctx());
        SplitCancelOutput second = handlers.splitCancel(new SplitCancelInput(jobId), ctx());
        assertNotNull(first);
        assertNotNull(second);

        boolean done = false;
        for (int poll = 0; poll < 200 && !done; poll++) {
            ExcelExecuteStatusOutput s = handlers.aiExecuteStatus(new ExcelExecuteStatusInput(0, jobId), ctx());
            done = Boolean.TRUE.equals(s.done());
            if (!done) Thread.sleep(5);
        }
        assertTrue(done, "cancelled (or completed) job must reach a terminal state");
    }

    /** Write a workbook with {@code sheetCount} sheets named Sheet0..SheetN-1, each with a header + rows. */
    private static Path writeWorkbook(Path file, int sheetCount) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(file.toFile())) {
            for (int i = 0; i < sheetCount; i++) {
                Sheet s = wb.createSheet("Sheet" + i);
                s.createRow(0).createCell(0).setCellValue("region");
                s.createRow(1).createCell(0).setCellValue("east-" + i);
                s.createRow(2).createCell(0).setCellValue("west-" + i);
            }
            wb.write(fos);
        }
        return file;
    }
}
