package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.CancellationToken;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.excel.contract.ExcelContract.ExcelAnalyzeInput;
import fan.summer.excel.contract.ExcelContract.ExcelAnalyzeOutput;
import fan.summer.excel.contract.ExcelContract.ExcelCancelInput;
import fan.summer.excel.contract.ExcelContract.ExcelCancelOutput;
import fan.summer.excel.contract.ExcelContract.ExcelComplexConfigInput;
import fan.summer.excel.contract.ExcelContract.ExcelComplexConfigOutput;
import fan.summer.excel.contract.ExcelContract.ExcelConfigureInput;
import fan.summer.excel.contract.ExcelContract.ExcelConfigureOutput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteInput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteOutput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteStartInput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteStartOutput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteStatusInput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteStatusOutput;
import fan.summer.excel.contract.ExcelContract.ExcelQueryInput;
import fan.summer.excel.contract.ExcelContract.ExcelQueryOutput;
import fan.summer.excel.contract.ExcelContract.SplitCancelInput;
import fan.summer.excel.contract.ExcelContract.SplitCancelOutput;
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
        ExcelAnalyzeOutput a = handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString(), null), ctx());
        assertTrue(a.success());

        ExcelQueryOutput q = handlers.aiQuery(new ExcelQueryInput(null), ctx());
        assertNotNull(q.state());
        assertEquals(src.toString(), q.state().sourceFile());

        ExcelCancelOutput c = handlers.aiCancel(new ExcelCancelInput(null), ctx());
        assertTrue(c.success());
        assertTrue(store.active().isEmpty());
    }

    @Test
    void analyzeMissingFileErrors() {
        ExcelAnalyzeOutput a = handlers.aiAnalyze(new ExcelAnalyzeInput("/no/such/file.xlsx", null), ctx());
        assertFalse(a.success());
    }

    @Test
    void executeRejectsBlankOutputDir() {
        // analyze + configure first so the handler reaches the outputDir validation.
        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString(), null), ctx());
        handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null, null), ctx());
        ExcelExecuteOutput r = handlers.aiExecute(new ExcelExecuteInput(null, "  ", null), ctx());
        assertFalse(r.success());
    }

    @Test
    void analyzeConfigureExecuteBySheet() throws Exception {
        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString(), null), ctx());
        ExcelConfigureOutput cfg = handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null, null), ctx());
        assertTrue(cfg.success());
        Path out = Files.createDirectories(tmp.resolve("out"));
        ExcelExecuteOutput r = handlers.aiExecute(new ExcelExecuteInput(null, out.toString(), null), ctx());
        assertTrue(r.success());
        assertTrue(Files.exists(out.resolve("Alpha.xlsx")));
    }

    @Test
    void executeWithoutConfigErrors() {
        ExcelExecuteOutput r = handlers.aiExecute(new ExcelExecuteInput(null, "/tmp/x", null), ctx());
        assertFalse(r.success());
    }

    // ---- complex config: multi-rule + filePath single call (canvas workflow shape) ----

    @Test
    void complexConfigWithFilePathAddsMultipleRulesAndExecutes() throws Exception {
        // Two sheets, one rule each — the engine writes one output sheet per source sheet name,
        // so a second rule on the same sheet is rejected (see complexConfigRejectsDuplicateSheetRule).
        Path twoSheets = tmp.resolve("two.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(twoSheets.toFile())) {
            Sheet alpha = wb.createSheet("Alpha");
            alpha.createRow(0).createCell(0).setCellValue("region");
            alpha.createRow(1).createCell(0).setCellValue("east");
            Sheet beta = wb.createSheet("Beta");
            beta.createRow(0).createCell(0).setCellValue("region");
            beta.createRow(1).createCell(0).setCellValue("west");
            wb.write(fos);
        }
        List<ExcelComplexConfigInput.ExcelComplexConfigInputEntries> entries = List.of(
            new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, 1, "Alpha"),
            new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, 1, "Beta"));
        ExcelComplexConfigOutput out = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null, entries, twoSheets.toString(), null, null, null), ctx());
        assertTrue(out.success(), out.summary());
        assertEquals(2, store.get("ai").complexEntries.size());
        // columnName resolved against the analysis to the 1-based column index.
        assertEquals(1, store.get("ai").complexEntries.get(0).columnIndex());
        // entries declare the complete rule set — re-running the same call is idempotent.
        ExcelComplexConfigOutput again = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null, entries, twoSheets.toString(), null, null, null), ctx());
        assertTrue(again.success(), again.summary());
        assertEquals(2, store.get("ai").complexEntries.size());
        // Mode is COMPLEX already — no separate excel_configure step needed.
        Path outDir = Files.createDirectories(tmp.resolve("complex-out"));
        ExcelExecuteOutput done = handlers.aiExecute(new ExcelExecuteInput(null, outDir.toString(), null), ctx());
        assertTrue(done.success(), done.summary());
        assertEquals(outDir.toAbsolutePath().normalize().toString(), done.outputDir());
        assertEquals(Set.of("two_east.xlsx", "two_west.xlsx"),
            new HashSet<>(done.files().files()));
        assertTrue(Files.isRegularFile(outDir.resolve("two_east.xlsx")));
        assertTrue(Files.isRegularFile(outDir.resolve("two_west.xlsx")));
    }

    @Test
    void complexConfigRejectsDuplicateSheetRule() throws Exception {
        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString(), null), ctx());
        List<ExcelComplexConfigInput.ExcelComplexConfigInputEntries> entries = List.of(
            new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, 1, "Alpha"),
            new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(1, null, null, 1, "Alpha"));
        ExcelComplexConfigOutput out = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null, entries, null, null, null, null), ctx());
        assertFalse(out.success());
        assertTrue(out.summary().contains("Alpha"));
    }

    @Test
    void complexConfigRejectsUnknownSheetAndBlankEntrySheet() {
        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString(), null), ctx());
        ExcelComplexConfigOutput badSheet = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null,
            List.of(new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, null, null, null, "Nope")), null, null, null, null), ctx());
        assertFalse(badSheet.success());
        assertTrue(badSheet.summary().contains("Nope"));

        ExcelComplexConfigOutput blankSheet = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null,
            List.of(new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, null, null, null, " ")), null, null, null, null), ctx());
        assertFalse(blankSheet.success());
    }

    /**
     * P2 atomicity: an add-with-filePath whose batch FAILS validation must leave the session
     * exactly as it was. Under the bug the new file/analysis were committed BEFORE the batch
     * loop, so a mid-batch failure returned the error with the NEW file+analysis but the OLD
     * (empty) rules — a later execute then ran against a half-swapped session.
     */
    @Test
    void complexConfigAddWithFilePathCommitsAtomicallyOnBatchFailure() throws Exception {
        // Session has committed state from file A (src: sheet "Alpha").
        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString(), null), ctx());

        // File B is analyzed in-call, but the batch's second entry is invalid against B.
        Path fileB = tmp.resolve("b.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(fileB.toFile())) {
            Sheet other = wb.createSheet("Other");
            other.createRow(0).createCell(0).setCellValue("region");
            other.createRow(1).createCell(0).setCellValue("east");
            wb.write(fos);
        }
        List<ExcelComplexConfigInput.ExcelComplexConfigInputEntries> entries = List.of(
            new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, 1, "Other"), // valid on B
            new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, 1, "Ghost")); // unknown on B
        ExcelComplexConfigOutput out = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null, entries, fileB.toString(), null, null, null), ctx());
        assertFalse(out.success());
        assertTrue(out.summary().contains("Ghost"));

        SplitConfig cfg = store.get("ai");
        assertEquals(src.toString(), cfg.sourceFile.toString(), "failed add must not swap the session sourceFile");
        assertTrue(cfg.analysisResult.containsKey("Alpha"), "failed add must not swap the session analysis");
        assertTrue(cfg.complexEntries.isEmpty(), "failed add must not install half of the batch");
    }

    @Test
    void legacySingleRuleAddStillWorksWithoutAnalyzeFailing() {
        ExcelComplexConfigOutput noAnalyze = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, 1, null, null, 1, null, "Alpha"), ctx());
        assertFalse(noAnalyze.success());

        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString(), null), ctx());
        ExcelComplexConfigOutput legacy = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, 1, null, null, 1, null, "Alpha"), ctx());
        assertTrue(legacy.success(), legacy.summary());
    }

    /** Omitted headerIndex defaults to the first header row — canvas/model callers name a column, not a row. */
    @Test
    void complexConfigDefaultsOmittedHeaderIndexToFirstRow() throws Exception {
        handlers.aiAnalyze(new ExcelAnalyzeInput(src.toString(), null), ctx());
        ExcelComplexConfigOutput out = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null,
            List.of(new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, null, "Alpha")),
            null, null, null, null), ctx());
        assertTrue(out.success(), out.summary());
        assertEquals(1, store.get("ai").complexEntries.get(0).headerIndex());
    }

    // ---- T2-P2 supplementary coverage: large workbook, status cursor, repeated cancel ----

    /** Large-workbook correctness: a multi-sheet workbook split BY_SHEET yields one file per sheet. */
    @Test
    void largeWorkbookSplitsBySheetCorrectly() throws Exception {
        Path big = writeWorkbook(tmp.resolve("big.xlsx"), 12);
        handlers.aiAnalyze(new ExcelAnalyzeInput(big.toString(), null), ctx());
        ExcelConfigureOutput cfg = handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null, null), ctx());
        assertTrue(cfg.success());
        Path out = Files.createDirectories(tmp.resolve("large-out"));
        ExcelExecuteOutput r = handlers.aiExecute(new ExcelExecuteInput(null, out.toString(), null), ctx());
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
        handlers.aiAnalyze(new ExcelAnalyzeInput(big.toString(), null), ctx());
        handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null, null), ctx());
        Path out = Files.createDirectories(tmp.resolve("cursor-out"));
        ExcelExecuteStartOutput start = handlers.aiExecuteStart(new ExcelExecuteStartInput(null, out.toString(), null), ctx());
        assertTrue(start.success(), start.summary());
        String jobId = start.jobId();
        assertNotNull(jobId);

        int cursor = 0;
        int prevCursor = -1;
        List<String> allDelivered = new ArrayList<>();
        boolean done = false;
        for (int poll = 0; poll < 200 && !done; poll++) {
            ExcelExecuteStatusOutput s = handlers.aiExecuteStatus(new ExcelExecuteStatusInput(cursor, jobId, null), ctx());
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
        handlers.aiAnalyze(new ExcelAnalyzeInput(big.toString(), null), ctx());
        handlers.aiConfigure(new ExcelConfigureInput(ExcelConfigureInput.ExcelConfigureInputMode.BY_SHEET, null, null, null, null), ctx());
        Path out = Files.createDirectories(tmp.resolve("cancel-out"));
        ExcelExecuteStartOutput start = handlers.aiExecuteStart(new ExcelExecuteStartInput(null, out.toString(), null), ctx());
        assertTrue(start.success(), start.summary());
        String jobId = start.jobId();

        // Repeated cancel must never throw, regardless of timing.
        SplitCancelOutput first = handlers.splitCancel(new SplitCancelInput(jobId), ctx());
        SplitCancelOutput second = handlers.splitCancel(new SplitCancelInput(jobId), ctx());
        assertNotNull(first);
        assertNotNull(second);

        boolean done = false;
        for (int poll = 0; poll < 200 && !done; poll++) {
            ExcelExecuteStatusOutput s = handlers.aiExecuteStatus(new ExcelExecuteStatusInput(0, jobId, null), ctx());
            done = Boolean.TRUE.equals(s.done());
            if (!done) Thread.sleep(5);
        }
        assertTrue(done, "cancelled (or completed) job must reach a terminal state");
    }

    // ---- P1 concurrency: background jobs run off a config snapshot ----

    /**
     * P1 concurrency: a background split job must run entirely off a PRIVATE snapshot of the
     * session config. Under the bug, startSplitJob mutated the SHARED cfg (outputDir) and handed
     * the live object to the job, so a second excel_execute_start on the same session redirected
     * the first job's remaining outputs into the second directory, and a concurrent
     * excel_complex_config clear could corrupt a complex job mid-flight. Deterministic without
     * sleeps: the workbook yields 25 sequential Phase-2 writes, so job 1 is still running when
     * the follow-up calls return microseconds later; assertions run only after job 1 reaches a
     * terminal state via polling.
     */
    @Test
    void runningJobIsImmuneToConcurrentRuleClearAndSecondStart() throws Exception {
        // One complex rule on the region column with 25 distinct values → 25 output files.
        Path big = tmp.resolve("race.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(big.toFile())) {
            Sheet data = wb.createSheet("Data");
            Row header = data.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("region");
            for (int i = 0; i < 25; i++) {
                Row r = data.createRow(i + 1);
                r.createCell(0).setCellValue(i);
                r.createCell(1).setCellValue("r" + i);
            }
            wb.write(fos);
        }
        handlers.aiAnalyze(new ExcelAnalyzeInput(big.toString(), null), ctx());
        ExcelComplexConfigOutput cfgOut = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null,
            List.of(new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(2, null, null, 1, "Data")),
            big.toString(), null, null, null), ctx());
        assertTrue(cfgOut.success(), cfgOut.summary());

        Path out1 = Files.createDirectories(tmp.resolve("race-out-1"));
        Path out2 = Files.createDirectories(tmp.resolve("race-out-2"));
        ExcelExecuteStartOutput first = handlers.aiExecuteStart(new ExcelExecuteStartInput(null, out1.toString(), null), ctx());
        assertTrue(first.success(), first.summary());

        // While job 1 is running: start a second job pointing elsewhere (under the bug this
        // re-pointed cfg.outputDir for BOTH jobs), then clear the session's complex rules.
        ExcelExecuteStartOutput second = handlers.aiExecuteStart(new ExcelExecuteStartInput(null, out2.toString(), null), ctx());
        assertTrue(second.success(), second.summary());
        ExcelComplexConfigOutput cleared = handlers.aiComplexConfig(new ExcelComplexConfigInput(
            ExcelComplexConfigInput.ExcelComplexConfigInputAction.clear, null, null, null, null, null, null), ctx());
        assertTrue(cleared.success(), cleared.summary());

        ExcelExecuteStatusOutput s = awaitTerminal(first.jobId());
        assertEquals("DONE", s.status(), "concurrent session mutation must not corrupt job 1: " + s.error());
        assertNotNull(s.result());
        assertEquals(25, s.result().fileCount());
        for (int i = 0; i < 25; i++) {
            assertTrue(Files.exists(out1.resolve("race_r" + i + ".xlsx")),
                "job 1 output redirected away from out1: race_r" + i + ".xlsx missing");
        }

        // Job 2 must also be terminal before the test returns: its worker keeps writing into
        // race-out-2 afterwards, which races JUnit's @TempDir cleanup (DirectoryNotEmptyException
        // seen on CI even though job 1 was already awaited).
        ExcelExecuteStatusOutput s2 = awaitTerminal(second.jobId());
        assertEquals("DONE", s2.status(), "job 2 must finish independently: " + s2.error());
    }

    /** Poll a job to its terminal state (mirrors the host's status polling); fails on timeout. */
    private ExcelExecuteStatusOutput awaitTerminal(String jobId) throws Exception {
        for (int poll = 0; poll < 2000; poll++) {
            ExcelExecuteStatusOutput s = handlers.aiExecuteStatus(new ExcelExecuteStatusInput(0, jobId, null), ctx());
            if (Boolean.TRUE.equals(s.done())) return s;
            Thread.sleep(5);
        }
        fail("job " + jobId + " did not reach a terminal state in time");
        return null;
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
