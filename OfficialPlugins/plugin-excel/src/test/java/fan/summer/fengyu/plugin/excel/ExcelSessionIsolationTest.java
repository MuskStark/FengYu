package fan.summer.fengyu.plugin.excel;

import fan.summer.excel.generated.ExcelAnalyzeInput;
import fan.summer.excel.generated.ExcelAnalyzeOutput;
import fan.summer.excel.generated.ExcelComplexConfigInput;
import fan.summer.excel.generated.ExcelComplexConfigOutput;
import fan.summer.excel.generated.ExcelExecuteInput;
import fan.summer.excel.generated.ExcelExecuteOutput;
import fan.summer.fengyu.sdk.CancellationToken;
import fan.summer.fengyu.sdk.RpcContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-3: concurrent runs must keep independent Excel sessions. The host injects a
 * per-run {@code sessionId} into every AI tool call; the worker scopes its
 * analyze→complex_config→execute state by that key. Interleaving two runs with
 * different files and rules must never cross-contaminate, while chat calls
 * (no sessionId) still share the default "ai" session.
 */
class ExcelSessionIsolationTest {

    @TempDir Path tmp;
    ExcelSessionStore store;
    ExcelRpcHandlers handlers;
    Path fileA;
    Path fileB;

    @BeforeEach
    void setUp() throws Exception {
        store = new ExcelSessionStore();
        handlers = new ExcelRpcHandlers(store);
        fileA = workbook("A", "region", "east", "west");
        fileB = workbook("B", "owner", "alice", "bob");
    }

    private Path workbook(String sheet, String header, String... values) throws Exception {
        Path path = tmp.resolve(sheet + ".xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(path.toFile())) {
            Sheet s = wb.createSheet(sheet);
            s.createRow(0).createCell(0).setCellValue(header);
            for (int i = 0; i < values.length; i++) {
                s.createRow(i + 1).createCell(0).setCellValue(values[i]);
            }
            wb.write(fos);
        }
        return path;
    }

    private static RpcContext ctx() {
        return new RpcContext("test", null, null, "en", new CancellationToken(), null);
    }

    @Test
    void interleavedRunSessionsNeverCrossContaminate() throws Exception {
        // Run A analyzes file A; run B analyzes file B — interleaved.
        assertTrue(handlers.aiAnalyze(new ExcelAnalyzeInput(fileA.toString(), "run-A"), ctx()).success());
        assertTrue(handlers.aiAnalyze(new ExcelAnalyzeInput(fileB.toString(), "run-B"), ctx()).success());
        // Re-analyze A (fresh rules via entries replace) while B configures.
        assertTrue(handlers.aiComplexConfig(new ExcelComplexConfigInput(
                ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null,
                List.of(new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, 1, "A")),
                fileA.toString(), null, "run-A", null), ctx()).success());
        assertTrue(handlers.aiComplexConfig(new ExcelComplexConfigInput(
                ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null,
                List.of(new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "owner", null, 1, "B")),
                fileB.toString(), null, "run-B", null), ctx()).success());
        // Interleaved execute: each run's output contains only ITS workbook's values.
        Path outA = Files.createDirectories(tmp.resolve("out-A"));
        Path outB = Files.createDirectories(tmp.resolve("out-B"));
        ExcelExecuteOutput doneA = handlers.aiExecute(
                new ExcelExecuteInput(null, outA.toString(), "run-A"), ctx());
        ExcelExecuteOutput doneB = handlers.aiExecute(
                new ExcelExecuteInput(null, outB.toString(), "run-B"), ctx());
        assertTrue(doneA.success(), doneA.summary());
        assertTrue(doneB.success(), doneB.summary());
        assertEquals(2, countXlsx(outA), "run A split by ITS column (east/west)");
        assertEquals(2, countXlsx(outB), "run B split by ITS column (alice/bob)");
        // Each run reports the directory IT wrote to — the bindable attachment dir.
        assertEquals(outA.toAbsolutePath().normalize().toString(), doneA.outputDir());
        assertEquals(outB.toAbsolutePath().normalize().toString(), doneB.outputDir());
        // The wrong run's file names must not appear in the other's output.
        assertTrue(containsName(outA, "east"));
        assertTrue(!containsName(outB, "east"), "run B must not see run A's values");
        assertTrue(containsName(outB, "alice"));
        // Independent session objects — not one shared config.
        assertNotSame(store.get("run:run-A"), store.get("run:run-B"));
    }

    @Test
    void chatCallsShareTheDefaultSession() {
        assertTrue(handlers.aiAnalyze(new ExcelAnalyzeInput(fileA.toString(), null), ctx()).success());
        // A null-session complex_config sees the same analysis — the chat flow.
        ExcelComplexConfigOutput out = handlers.aiComplexConfig(new ExcelComplexConfigInput(
                ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null,
                List.of(new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, 1, "A")),
                null, null, null, null), ctx());
        assertTrue(out.success(), out.summary());
        assertSame(store.get("ai"), store.get(ExcelRpcHandlers.sessionKey(null)));
        assertEquals("run:X", ExcelRpcHandlers.sessionKey("X"));
    }

    @Test
    void batchFailureLeavesPriorStateIntact() throws Exception {
        // Configure a good rule first.
        assertTrue(handlers.aiAnalyze(new ExcelAnalyzeInput(fileA.toString(), null), ctx()).success());
        assertTrue(handlers.aiComplexConfig(new ExcelComplexConfigInput(
                ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null,
                List.of(new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, 1, "A")),
                null, null, null, null), ctx()).success());
        int before = store.get("ai").complexEntries.size();
        // A batch whose SECOND entry is invalid must change nothing (P2-3).
        ExcelComplexConfigOutput failed = handlers.aiComplexConfig(new ExcelComplexConfigInput(
                ExcelComplexConfigInput.ExcelComplexConfigInputAction.add, null,
                List.of(
                        new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, "region", null, 1, "A"),
                        new ExcelComplexConfigInput.ExcelComplexConfigInputEntries(null, null, null, 1, "NoSuchSheet")),
                null, null, null, null), ctx());
        assertFalse(failed.success());
        assertEquals(before, store.get("ai").complexEntries.size(),
                "a failed batch must leave the prior rule set untouched");
    }

    private long countXlsx(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".xlsx")).count();
        }
    }

    private boolean containsName(Path dir, String fragment) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().contains(fragment));
        }
    }
}
