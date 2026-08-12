package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.RpcException;
import fan.summer.excel.generated.AnalyzeInput;
import fan.summer.excel.generated.AnalyzeOutput;
import fan.summer.excel.generated.ConfigureInput;
import fan.summer.excel.generated.ConfigureOutput;
import fan.summer.excel.generated.EstimateInput;
import fan.summer.excel.generated.EstimateOutput;
import fan.summer.excel.generated.ExcelAnalyzeInput;
import fan.summer.excel.generated.ExcelAnalyzeOutput;
import fan.summer.excel.generated.ExcelCancelInput;
import fan.summer.excel.generated.ExcelCancelOutput;
import fan.summer.excel.generated.ExcelComplexConfigInput;
import fan.summer.excel.generated.ExcelComplexConfigOutput;
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
import fan.summer.excel.generated.SplitInput;
import fan.summer.excel.generated.SplitOutput;
import fan.summer.excel.generated.SplitStartInput;
import fan.summer.excel.generated.SplitStartOutput;
import fan.summer.excel.generated.SplitStatusInput;
import fan.summer.excel.generated.SplitStatusOutput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapts the Excel split engine to the typed SDK JSON-RPC handlers. Each method consumes a generated
 * {@code *Input} record and returns the matching {@code *Output} record; {@link ExcelWorkerMain}
 * wires them through the typed {@code worker.method(PluginMethods.X, XInput.class, XOutput.class,
 * ...)} registration, so the SDK handles (de)serialization and binds an {@link RpcContext} per call.
 *
 * <p>The handlers cover both the session-keyed UI workflow ({@code analyze}/{@code configure}/
 * {@code estimate}/{@code split} — delegated to {@link ExcelPlugin#invoke}) and the stateless AI
 * tools ({@code excel_*}) declared in {@code manifest.json}. The AI tools share a single {@code "ai"}
 * session so a model can drive the whole flow in sequence.
 *
 * <p><b>Result envelope.</b> Failures are surfaced in-band as the generated output record with
 * {@code success=false} and a localized summary — the previous {@code failKey(...)} semantics — and
 * any unexpected exception is flattened the same way rather than escaping as an RPC error. Logging
 * goes through {@link RpcContext#logger()} and never records raw workbook contents. Long split
 * operations cooperative-check {@link RpcContext#cancellation()} so a transport
 * {@code $/cancelRequest} returns a clean {@code CANCELLED} response; domain job cancellation
 * ({@code split_cancel}/{@code excel_cancel}) still flows through {@link Jobs}.
 */
public final class ExcelRpcHandlers implements AutoCloseable {
    static final String AI_SESSION = "ai";

    private final ExcelSessionStore sessions;
    private final ExcelPlugin plugin;
    private final Jobs jobs;
    private final PluginMessages msgs;

    public ExcelRpcHandlers(ExcelSessionStore sessions) {
        this(sessions, new Jobs());
    }

    public ExcelRpcHandlers(ExcelSessionStore sessions, Jobs jobs) {
        this.sessions = sessions;
        this.jobs = jobs;
        this.msgs = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, ExcelRpcHandlers.class);
        this.plugin = new ExcelPlugin(sessions, msgs);
    }

    @Override public void close() { jobs.close(); }

    private String t(String key, Object... args) { return msgs.format(key, args); }

    // ---- UI-facing, session-keyed workflow (delegate to ExcelPlugin.invoke) -------------

    public AnalyzeOutput analyze(AnalyzeInput in, RpcContext ctx) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("session", in.session());
            params.put("sourceFile", in.sourceFile());
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) plugin.invoke("analyze", params);
            return new AnalyzeOutput(toAnalyzeSheets(r.get("sheets")), true, (String) r.get("summary"));
        } catch (Exception e) {
            ctx.logger().warn("analyze failed: {}", e.getClass().getSimpleName(), e);
            return new AnalyzeOutput(null, false, safeMessage(e));
        }
    }

    public ConfigureOutput configure(ConfigureInput in, RpcContext ctx) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("session", in.session());
            params.put("mode", in.mode());
            if (in.selectedSheets() != null) params.put("selectedSheets", in.selectedSheets());
            params.put("splitSheet", in.splitSheet());
            params.put("splitColumn", in.splitColumn());
            params.put("splitColumnIndex", in.splitColumnIndex());
            params.put("filePrefix", in.filePrefix());
            if (in.complexEntries() != null) {
                params.put("complexEntries", in.complexEntries().stream()
                    .map(ce -> complexEntryMap(ce.fieldName(), ce.sheetName(), ce.headerIndex(), ce.columnIndex()))
                    .toList());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) plugin.invoke("configure", params);
            return new ConfigureOutput(true, (String) r.get("summary"));
        } catch (Exception e) {
            ctx.logger().warn("configure failed: {}", e.getClass().getSimpleName(), e);
            return new ConfigureOutput(false, safeMessage(e));
        }
    }

    public EstimateOutput estimate(EstimateInput in, RpcContext ctx) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("session", in.session());
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) plugin.invoke("estimate", params);
            return new EstimateOutput((Boolean) r.get("exact"), (Integer) r.get("fileCount"),
                true, (String) r.get("summary"));
        } catch (Exception e) {
            ctx.logger().warn("estimate failed: {}", e.getClass().getSimpleName(), e);
            return new EstimateOutput(null, null, false, safeMessage(e));
        }
    }

    public SplitOutput split(SplitInput in, RpcContext ctx) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("session", in.session());
            params.put("sourceFile", in.sourceFile());
            params.put("outputDir", in.outputDir());
            params.put("mode", in.mode());
            if (in.selectedSheets() != null) params.put("selectedSheets", in.selectedSheets());
            params.put("splitSheet", in.splitSheet());
            params.put("splitColumn", in.splitColumn());
            params.put("splitColumnIndex", in.splitColumnIndex());
            params.put("filePrefix", in.filePrefix());
            if (in.complexEntries() != null) {
                params.put("complexEntries", in.complexEntries().stream()
                    .map(ce -> complexEntryMap(ce.fieldName(), ce.sheetName(), ce.headerIndex(), ce.columnIndex()))
                    .toList());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) plugin.invoke("split", params);
            @SuppressWarnings("unchecked")
            List<String> files = (List<String>) r.get("files");
            return new SplitOutput((Integer) r.get("fileCount"), files, true, (String) r.get("summary"));
        } catch (Exception e) {
            ctx.logger().warn("split failed: {}", e.getClass().getSimpleName(), e);
            return new SplitOutput(null, null, false, safeMessage(e));
        }
    }

    // ---- UI-facing async split (session-keyed) ------------------------------------------

    public SplitStartOutput splitStart(SplitStartInput in, RpcContext ctx) {
        try {
            String session = in.session();
            if (session == null || session.isBlank()) return new SplitStartOutput(null, false, t("ex.err.sessionRequired"));
            SplitConfig cfg = sessions.get(session);
            if (cfg.analysisResult == null) return new SplitStartOutput(null, false, t("ex.err.callAnalyzeFirst"));
            JobLaunch launched = startSplitJob(cfg, in.outputDir(), in.filePrefix(), ctx);
            return new SplitStartOutput(launched.jobId(), launched.success(), launched.summary());
        } catch (Exception e) {
            ctx.logger().warn("split_start failed: {}", e.getClass().getSimpleName(), e);
            return new SplitStartOutput(null, false, safeMessage(e));
        }
    }

    public SplitStatusOutput splitStatus(SplitStatusInput in, RpcContext ctx) {
        String jobId = in.jobId();
        if (jobId == null || jobId.isBlank()) {
            return new SplitStatusOutput(null, null, null, null, null, null, null, null, null, false,
                t("ex.err.paramRequired", "jobId"), null);
        }
        int cursor = in.cursor() != null ? in.cursor() : 0;
        return toSplitStatusOutput(jobs.snapshot(jobId, cursor));
    }

    public SplitCancelOutput splitCancel(SplitCancelInput in, RpcContext ctx) {
        try {
            String jobId = in.jobId();
            if (jobId == null || jobId.isBlank()) return new SplitCancelOutput(null, false, t("ex.err.paramRequired", "jobId"));
            if (!jobs.cancel(jobId)) return new SplitCancelOutput(null, false, t("ex.err.jobNotRunning", jobId));
            return new SplitCancelOutput(jobId, true, t("ex.cancelRequested"));
        } catch (Exception e) {
            ctx.logger().warn("split_cancel failed: {}", e.getClass().getSimpleName(), e);
            return new SplitCancelOutput(null, false, safeMessage(e));
        }
    }

    // ---- AI-facing, stateless tools (shared "ai" session) --------------------------------

    public ExcelAnalyzeOutput aiAnalyze(ExcelAnalyzeInput in, RpcContext ctx) {
        try {
            String filePath = in.filePath();
            if (filePath == null || filePath.isBlank()) return new ExcelAnalyzeOutput(null, false, t("ex.err.notFileRef", "filePath"));
            Path file = Paths.get(filePath.trim());
            if (!Files.exists(file) || !Files.isReadable(file)) return new ExcelAnalyzeOutput(null, false, t("ex.err.fileNotFound", filePath));
            SplitConfig cfg = sessions.get(AI_SESSION);
            cfg.sourceFile = file;
            try {
                cfg.analysisResult = ExcelSplitter.analyze(file);
            } catch (Exception e) {
                return new ExcelAnalyzeOutput(null, false, t("ex.err.analyzeFailed", safeMessage(e)));
            }
            ctx.logger().info("analyzed {} sheet(s) from {}", cfg.analysisResult.size(), file.getFileName());
            return new ExcelAnalyzeOutput(new ArrayList<>(cfg.analysisResult.keySet()), true,
                t("ex.analyzed", cfg.analysisResult.size()));
        } catch (Exception e) {
            ctx.logger().warn("excel_analyze failed: {}", e.getClass().getSimpleName(), e);
            return new ExcelAnalyzeOutput(null, false, safeMessage(e));
        }
    }

    public ExcelConfigureOutput aiConfigure(ExcelConfigureInput in, RpcContext ctx) {
        try {
            SplitConfig cfg = sessions.active().orElse(null);
            if (cfg == null || cfg.analysisResult == null) return new ExcelConfigureOutput(false, t("ex.err.callAiAnalyzeFirst"));
            String modeText = in.mode() == null ? null : in.mode().name();
            SplitConfig.SplitMode mode;
            try { mode = SplitConfig.SplitMode.valueOf(modeText); }
            catch (Exception e) { return new ExcelConfigureOutput(false, t("ex.err.invalidMode", modeText)); }
            cfg.mode = mode;
            switch (mode) {
                case BY_SHEET -> {
                    List<String> sheets = in.sheets();
                    cfg.selectedSheets = (sheets != null && !sheets.isEmpty())
                        ? new ArrayList<>(sheets) : new ArrayList<>(cfg.analysisResult.keySet());
                }
                case BY_COLUMN -> {
                    String splitSheet = in.splitSheet();
                    String splitColumn = in.splitColumn();
                    if (splitSheet == null || splitColumn == null) return new ExcelConfigureOutput(false, t("ex.err.byColumnMissing"));
                    Map<Integer, String> headers = cfg.analysisResult.get(splitSheet);
                    if (headers == null) return new ExcelConfigureOutput(false, t("ex.err.unknownSheet", splitSheet));
                    Integer idx = null;
                    for (Map.Entry<Integer, String> e : headers.entrySet()) {
                        if (splitColumn.equals(e.getValue())) { idx = e.getKey(); break; }
                    }
                    if (idx == null) return new ExcelConfigureOutput(false, t("ex.err.unknownColumn", splitColumn));
                    cfg.splitSheet = splitSheet;
                    cfg.splitColumn = splitColumn;
                    cfg.splitColumnIndex = idx;
                }
                case COMPLEX -> {
                    if (cfg.complexEntries.isEmpty()) return new ExcelConfigureOutput(false, t("ex.err.addViaComplexConfig"));
                }
            }
            return new ExcelConfigureOutput(true, t("ex.configured", mode));
        } catch (Exception e) {
            ctx.logger().warn("excel_configure failed: {}", e.getClass().getSimpleName(), e);
            return new ExcelConfigureOutput(false, safeMessage(e));
        }
    }

    public ExcelComplexConfigOutput aiComplexConfig(ExcelComplexConfigInput in, RpcContext ctx) {
        try {
            SplitConfig cfg = sessions.active().orElse(null);
            if (cfg == null) return new ExcelComplexConfigOutput(null, false, t("ex.err.callAiAnalyzeFirst"));
            String action = in.action() == null ? null : in.action().name();
            String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
            switch (a) {
                case "add" -> {
                    String sheetName = in.sheetName();
                    if (sheetName == null || sheetName.isBlank())
                        return new ExcelComplexConfigOutput(null, false, t("ex.err.sheetNameRequired"));
                    int headerIndex = in.headerIndex() != null ? in.headerIndex() : -1;
                    int columnIndex = in.columnIndex() != null ? in.columnIndex() : -1;
                    String field = cfg.sourceFile != null ? cfg.sourceFile.getFileName().toString() : "";
                    cfg.complexEntries.add(new ComplexSplitEntry(field, sheetName, headerIndex, columnIndex));
                    return new ExcelComplexConfigOutput(null, true, t("ex.complexAdded", cfg.complexEntries.size()));
                }
                case "list" -> {
                    List<ExcelComplexConfigOutput.ExcelComplexConfigOutputEntries> entries = cfg.complexEntries.stream()
                        .map(e -> new ExcelComplexConfigOutput.ExcelComplexConfigOutputEntries(
                            e.columnIndex(), e.headerIndex(), e.sheetName()))
                        .toList();
                    return new ExcelComplexConfigOutput(entries, true, t("ex.complexEntries", cfg.complexEntries.size()));
                }
                case "clear" -> {
                    cfg.complexEntries.clear();
                    return new ExcelComplexConfigOutput(null, true, t("ex.cleared"));
                }
                default -> {
                    return new ExcelComplexConfigOutput(null, false, t("ex.err.invalidAction", action));
                }
            }
        } catch (Exception e) {
            ctx.logger().warn("excel_complex_config failed: {}", e.getClass().getSimpleName(), e);
            return new ExcelComplexConfigOutput(null, false, safeMessage(e));
        }
    }

    public ExcelExecuteOutput aiExecute(ExcelExecuteInput in, RpcContext ctx) {
        try {
            SplitConfig cfg = sessions.active().orElse(null);
            if (cfg == null || cfg.analysisResult == null) return new ExcelExecuteOutput(null, false, t("ex.err.callAiAnalyzeFirst"));
            if (cfg.mode == null) return new ExcelExecuteOutput(null, false, t("ex.err.callAiConfigureFirst"));
            String outputDir = in.outputDir();
            if (outputDir == null || outputDir.isBlank()) return new ExcelExecuteOutput(null, false, t("ex.err.notFileRef", "outputDir"));
            cfg.outputDir = Paths.get(outputDir.trim());
            cfg.filePrefix = trimmed(in.filePrefix());
            // Cooperative checkpoint before the long split so a transport cancel returns CANCELLED.
            ctx.cancellation().throwIfCancelled();
            ExcelSplitter.SplitResult res;
            try {
                Files.createDirectories(cfg.outputDir);
                // Wire the transport cancellation token into the engine so it aborts mid-split too.
                res = new ExcelSplitter(cfg, null, ctx.cancellation()::isCancelled).split();
            } catch (RpcException cancel) {
                throw cancel;
            } catch (Exception e) {
                // If the engine aborted because the call was cancelled, surface CANCELLED, not a failure.
                if (ctx.cancellation().isCancelled()) ctx.cancellation().throwIfCancelled();
                return new ExcelExecuteOutput(null, false, t("ex.err.splitFailed", safeMessage(e)));
            }
            ctx.cancellation().throwIfCancelled();
            ctx.logger().info("split produced {} file(s) into {}", res.fileCount(), cfg.outputDir);
            ExcelExecuteOutput.ExcelExecuteOutputFiles files = new ExcelExecuteOutput.ExcelExecuteOutputFiles(
                res.fileCount(),
                res.outputFiles().stream().map(p -> p.getFileName().toString()).toList());
            return new ExcelExecuteOutput(files, true, t("ex.wrote", res.fileCount()));
        } catch (RpcException cancel) {
            throw cancel;
        } catch (Exception e) {
            ctx.logger().warn("excel_execute failed: {}", e.getClass().getSimpleName(), e);
            return new ExcelExecuteOutput(null, false, safeMessage(e));
        }
    }

    public ExcelExecuteStartOutput aiExecuteStart(ExcelExecuteStartInput in, RpcContext ctx) {
        try {
            // AI tools share the fixed "ai" session; aiAnalyze has populated it.
            SplitConfig cfg = sessions.get(AI_SESSION);
            if (cfg.analysisResult == null) return new ExcelExecuteStartOutput(null, false, t("ex.err.callAiAnalyzeFirst"));
            JobLaunch launched = startSplitJob(cfg, in.outputDir(), in.filePrefix(), ctx);
            return new ExcelExecuteStartOutput(launched.jobId(), launched.success(), launched.summary());
        } catch (Exception e) {
            ctx.logger().warn("excel_execute_start failed: {}", e.getClass().getSimpleName(), e);
            return new ExcelExecuteStartOutput(null, false, safeMessage(e));
        }
    }

    public ExcelExecuteStatusOutput aiExecuteStatus(ExcelExecuteStatusInput in, RpcContext ctx) {
        String jobId = in.jobId();
        if (jobId == null || jobId.isBlank()) {
            return new ExcelExecuteStatusOutput(null, null, null, null, null, null, null, null, null, false,
                t("ex.err.paramRequired", "jobId"), null);
        }
        int cursor = in.cursor() != null ? in.cursor() : 0;
        return toExcelStatusOutput(jobs.snapshot(jobId, cursor));
    }

    public ExcelQueryOutput aiQuery(ExcelQueryInput in, RpcContext ctx) {
        try {
            SplitConfig cfg = sessions.active().orElse(null);
            if (cfg == null) return new ExcelQueryOutput(null, false, t("ex.err.noActiveSession"));
            ExcelQueryOutput.ExcelQueryOutputState state = new ExcelQueryOutput.ExcelQueryOutputState(
                cfg.complexEntries.size(),
                cfg.mode != null ? cfg.mode.name() : null,
                cfg.outputDir != null ? cfg.outputDir.toString() : null,
                cfg.selectedSheets,
                cfg.sourceFile != null ? cfg.sourceFile.toString() : null,
                cfg.splitColumnIndex,
                cfg.splitSheet);
            String summary = cfg.mode != null ? t("ex.modeIs", cfg.mode.name()) : t("ex.modeUnset");
            return new ExcelQueryOutput(state, true, summary);
        } catch (Exception e) {
            ctx.logger().warn("excel_query failed: {}", e.getClass().getSimpleName(), e);
            return new ExcelQueryOutput(null, false, safeMessage(e));
        }
    }

    public ExcelCancelOutput aiCancel(ExcelCancelInput in, RpcContext ctx) {
        try {
            sessions.remove(AI_SESSION);
            return new ExcelCancelOutput(true, t("ex.sessionReset"));
        } catch (Exception e) {
            ctx.logger().warn("excel_cancel failed: {}", e.getClass().getSimpleName(), e);
            return new ExcelCancelOutput(false, safeMessage(e));
        }
    }

    // ---- shared split-job launcher --------------------------------------------------------
    //
    // Launch the split as a background job and return a jobId immediately. Used for large workbooks
    // whose split may exceed the host's per-RPC timeout; callers poll *_status with a cursor to drain
    // streamed progress logs. Mirrors the offlinepython build/deploy job pattern.

    private record JobLaunch(boolean success, String jobId, String summary) {
        static JobLaunch ok(String jobId, String summary) { return new JobLaunch(true, jobId, summary); }
        static JobLaunch fail(String summary) { return new JobLaunch(false, null, summary); }
    }

    private JobLaunch startSplitJob(SplitConfig cfg, String outputDir, String filePrefix, RpcContext ctx) {
        if (cfg.mode == null) return JobLaunch.fail(t("ex.err.callUiConfigureFirst"));
        if (outputDir == null || outputDir.isBlank()) return JobLaunch.fail(t("ex.err.paramRequired", "outputDir"));
        cfg.outputDir = Paths.get(outputDir.trim());
        cfg.filePrefix = trimmed(filePrefix);
        try { Files.createDirectories(cfg.outputDir); } catch (Exception ignored) {}

        Jobs.Job job = jobs.start("SPLIT", handle -> {
            ExcelSplitter splitter = new ExcelSplitter(cfg, (pct, msg) -> handle.log(msg), handle::isCancelled);
            try {
                // Cooperative checkpoint: honour a transport cancel that arrived before the job spun up.
                ctx.cancellation().throwIfCancelled();
                ExcelSplitter.SplitResult res = splitter.split();
                handle.setSummary(Map.of(
                    "fileCount", res.fileCount(),
                    "files", res.outputFiles().stream().map(p -> p.getFileName().toString()).toList()));
            } catch (Exception e) {
                // Jobs.start flattens exceptions to a one-line markFailed without a stack trace; log the
                // full stack here so the host log surface has diagnostics, then rethrow. Raw workbook
                // contents are never logged — only the source path, mode, and exception toString.
                ctx.logger().error("split job failed for {} (mode={}): {}", cfg.sourceFile, cfg.mode, e.toString(), e);
                throw e;
            }
        });
        return JobLaunch.ok(job.id, t("ex.splitStarted"));
    }

    // ---- Jobs.snapshot (still Map-shaped) → generated status records ---------------------

    private static SplitStatusOutput toSplitStatusOutput(Map<String, Object> s) {
        return new SplitStatusOutput(
            toInt(s.get("cursor")),
            toBool(s.get("done")),
            toInt(s.get("droppedLogs")),
            toInt(s.get("elapsedMs")),
            (String) s.get("error"),
            (String) s.get("jobId"),
            toStringList(s.get("logs")),
            toSplitStatusResult(s.get("result")),
            (String) s.get("status"),
            toSuccess(s.get("success")),
            (String) s.get("summary"),
            (String) s.get("type"));
    }

    private static SplitStatusOutput.SplitStatusOutputResult toSplitStatusResult(Object v) {
        if (!(v instanceof Map<?, ?> m)) return null;
        return new SplitStatusOutput.SplitStatusOutputResult(toInt(m.get("fileCount")), toStringList(m.get("files")));
    }

    private static ExcelExecuteStatusOutput toExcelStatusOutput(Map<String, Object> s) {
        return new ExcelExecuteStatusOutput(
            toInt(s.get("cursor")),
            toBool(s.get("done")),
            toInt(s.get("droppedLogs")),
            toInt(s.get("elapsedMs")),
            (String) s.get("error"),
            (String) s.get("jobId"),
            toStringList(s.get("logs")),
            toExcelStatusResult(s.get("result")),
            (String) s.get("status"),
            toSuccess(s.get("success")),
            (String) s.get("summary"),
            (String) s.get("type"));
    }

    private static ExcelExecuteStatusOutput.ExcelExecuteStatusOutputResult toExcelStatusResult(Object v) {
        if (!(v instanceof Map<?, ?> m)) return null;
        return new ExcelExecuteStatusOutput.ExcelExecuteStatusOutputResult(toInt(m.get("fileCount")), toStringList(m.get("files")));
    }

    // ---- small conversion helpers --------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<AnalyzeOutput.AnalyzeOutputSheets> toAnalyzeSheets(Object sheetsObj) {
        List<AnalyzeOutput.AnalyzeOutputSheets> out = new ArrayList<>();
        if (sheetsObj instanceof Map<?, ?> sheets) {
            for (Map.Entry<?, ?> e : sheets.entrySet()) {
                String name = String.valueOf(e.getKey());
                List<AnalyzeOutput.AnalyzeOutputSheets.AnalyzeOutputSheetsColumns> cols = new ArrayList<>();
                if (e.getValue() instanceof Map<?, ?> colMap) {
                    for (Map.Entry<?, ?> c : colMap.entrySet()) {
                        cols.add(new AnalyzeOutput.AnalyzeOutputSheets.AnalyzeOutputSheetsColumns(
                            String.valueOf(c.getValue()), String.valueOf(c.getKey())));
                    }
                }
                out.add(new AnalyzeOutput.AnalyzeOutputSheets(cols, name));
            }
        }
        return out;
    }

    private static Map<String, Object> complexEntryMap(String fieldName, String sheetName,
            Integer headerIndex, Integer columnIndex) {
        Map<String, Object> m = new LinkedHashMap<>();
        // Omit null values so ExcelPlugin.applyConfig's getOrDefault(...) fallbacks still apply
        // (fieldName→"", headerIndex/columnIndex→-1) exactly as the old Map-based UI flow did.
        if (fieldName != null) m.put("fieldName", fieldName);
        if (sheetName != null) m.put("sheetName", sheetName);
        if (headerIndex != null) m.put("headerIndex", headerIndex);
        if (columnIndex != null) m.put("columnIndex", columnIndex);
        return m;
    }

    private static Integer toInt(Object v) { return v instanceof Number n ? n.intValue() : null; }
    private static Boolean toBool(Object v) { return v instanceof Boolean b ? b : null; }
    private static boolean toSuccess(Object v) { return Boolean.TRUE.equals(v); }
    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object v) { return v instanceof List<?> l ? (List<String>) l : null; }

    private static String trimmed(String value) { return value == null ? "" : value.trim(); }

    /** One-line, throwable→message conversion that strips newlines so the summary stays single-line. */
    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m.replace('\r', ' ').replace('\n', ' ');
    }
}
