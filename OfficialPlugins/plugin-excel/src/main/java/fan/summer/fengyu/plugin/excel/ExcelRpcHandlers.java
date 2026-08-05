package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.Jobs;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginHandlerSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapts the Excel split engine to official SDK JSON-RPC handlers. Covers both the
 * session-keyed UI workflow ({@code analyze}/{@code configure}/{@code split}) and the
 * stateless AI tools ({@code excel_*}) declared in {@code manifest.json}. The AI tools
 * share a single {@code "ai"} session so a model can drive the whole flow in sequence.
 *
 * <p>Entry/exit/failure logging and the {success, summary, ...} envelope are inherited from
 * {@link PluginHandlerSupport}; register handlers via {@code worker.on("m", handlers.handle("m", handlers::m))}.
 */
public final class ExcelRpcHandlers extends PluginHandlerSupport {
    static final String AI_SESSION = "ai";

    private final ExcelSessionStore sessions;
    private final ExcelPlugin plugin;
    private final Jobs jobs;

    public ExcelRpcHandlers(ExcelSessionStore sessions) {
        this(sessions, new Jobs());
    }

    public ExcelRpcHandlers(ExcelSessionStore sessions, Jobs jobs) {
        super("excel");
        this.sessions = sessions;
        this.plugin = new ExcelPlugin(sessions);
        this.jobs = jobs;
    }

    // ---- UI-facing, session-keyed workflow ---------------------------------

    public Object analyze(Map<String, Object> params) {
        return plugin.invoke("analyze", params);
    }

    public Object configure(Map<String, Object> params) {
        return plugin.invoke("configure", params);
    }

    public Object estimate(Map<String, Object> params) {
        return plugin.invoke("estimate", params);
    }

    public Object split(Map<String, Object> params) {
        return plugin.invoke("split", params);
    }

    // ---- AI-facing, stateless (shared "ai" session) ------------------------

    public Object aiAnalyze(Map<String, Object> params) {
        return result(() -> {
            String filePath = requiredPath(params, "filePath");
            Path file = Paths.get(filePath.trim());
            if (!Files.exists(file) || !Files.isReadable(file)) return failure("File not found: " + filePath);
            SplitConfig cfg = sessions.get(AI_SESSION);
            cfg.sourceFile = file;
            try { cfg.analysisResult = ExcelSplitter.analyze(file); }
            catch (Exception e) { return failure("Analyze failed: " + safeMessage(e)); }
            log.info("analyzed {} sheet(s) from {}", cfg.analysisResult.size(), file.getFileName());
            return ok("analyzed " + cfg.analysisResult.size() + " sheet(s)", "sheets", cfg.analysisResult.keySet());
        });
    }

    public Object aiConfigure(Map<String, Object> params) {
        return result(() -> {
            SplitConfig cfg = sessions.active().orElse(null);
            if (cfg == null || cfg.analysisResult == null) return failure("Call excel_analyze first.");
            String modeText = string(params, "mode");
            SplitConfig.SplitMode mode;
            try { mode = SplitConfig.SplitMode.valueOf(modeText); }
            catch (Exception e) { return failure("Invalid mode: " + modeText); }
            cfg.mode = mode;
            switch (mode) {
                case BY_SHEET -> {
                    List<String> sheets = stringList(params.get("sheets"));
                    cfg.selectedSheets = (sheets != null && !sheets.isEmpty())
                        ? new ArrayList<>(sheets) : new ArrayList<>(cfg.analysisResult.keySet());
                }
                case BY_COLUMN -> {
                    String splitSheet = string(params, "splitSheet");
                    String splitColumn = string(params, "splitColumn");
                    if (splitSheet == null || splitColumn == null)
                        return failure("splitSheet and splitColumn required for BY_COLUMN");
                    Map<Integer, String> headers = cfg.analysisResult.get(splitSheet);
                    if (headers == null) return failure("Unknown sheet: " + splitSheet);
                    Integer idx = null;
                    for (var e : headers.entrySet()) if (splitColumn.equals(e.getValue())) { idx = e.getKey(); break; }
                    if (idx == null) return failure("Unknown column: " + splitColumn);
                    cfg.splitSheet = splitSheet;
                    cfg.splitColumn = splitColumn;
                    cfg.splitColumnIndex = idx;
                }
                case COMPLEX -> {
                    if (cfg.complexEntries.isEmpty()) return failure("Add entries via excel_complex_config first");
                }
            }
            return ok("configured mode=" + mode, null, null);
        });
    }

    public Object aiComplexConfig(Map<String, Object> params) {
        return result(() -> {
            SplitConfig cfg = sessions.active().orElse(null);
            if (cfg == null) return failure("Call excel_analyze first.");
            String action = string(params, "action");
            String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
            switch (a) {
                case "add" -> {
                    String sheetName = string(params, "sheetName");
                    if (sheetName == null || sheetName.isBlank()) return failure("sheetName required for add");
                    int headerIndex = integer(params, "headerIndex", -1);
                    int columnIndex = integer(params, "columnIndex", -1);
                    String field = cfg.sourceFile != null ? cfg.sourceFile.getFileName().toString() : "";
                    cfg.complexEntries.add(new ComplexSplitEntry(field, sheetName, headerIndex, columnIndex));
                    return ok("added entry; total=" + cfg.complexEntries.size(), null, null);
                }
                case "list" -> {
                    List<Map<String, Object>> entries = cfg.complexEntries.stream()
                        .map(e -> Map.<String, Object>of("sheetName", e.sheetName(),
                            "headerIndex", e.headerIndex(), "columnIndex", e.columnIndex()))
                        .toList();
                    return ok(cfg.complexEntries.size() + " entr(ies)", "entries", entries);
                }
                case "clear" -> {
                    cfg.complexEntries.clear();
                    return ok("cleared", null, null);
                }
                default -> { return failure("Invalid action: " + action); }
            }
        });
    }

    public Object aiExecute(Map<String, Object> params) {
        return result(() -> {
            SplitConfig cfg = sessions.active().orElse(null);
            if (cfg == null || cfg.analysisResult == null) return failure("Call excel_analyze first.");
            if (cfg.mode == null) return failure("Call excel_configure first.");
            String outputDir = requiredPath(params, "outputDir");
            cfg.outputDir = Paths.get(outputDir.trim());
            cfg.filePrefix = trimmed(string(params, "filePrefix"));
            ExcelSplitter.SplitResult res;
            try {
                Files.createDirectories(cfg.outputDir);
                res = new ExcelSplitter(cfg, null).split();
            } catch (Exception e) { return failure("Split failed: " + safeMessage(e)); }
            log.info("split produced {} file(s) into {}", res.fileCount(), cfg.outputDir);
            return ok("wrote " + res.fileCount() + " file(s)", "files", Map.of(
                "fileCount", res.fileCount(),
                "files", res.outputFiles().stream().map(p -> p.getFileName().toString()).toList()));
        });
    }

    /**
     * Launch the split as a background job and return a {@code jobId} immediately. Use this for
     * large workbooks whose split may exceed the host's per-RPC timeout; poll
     * {@code excel_execute_status} (or {@code split_status} from the UI) with a cursor to drain
     * streamed progress logs. Mirrors the offlinepython build/deploy job pattern.
     */
    public Object aiExecuteStart(Map<String, Object> params) {
        return result(() -> {
            // AI tools share the fixed "ai" session; aiAnalyze has populated it.
            SplitConfig cfg = sessions.get(AI_SESSION);
            if (cfg.analysisResult == null) return failure("Call excel_analyze first.");
            return startSplitJob(cfg, params);
        });
    }

    public Object aiExecuteStatus(Map<String, Object> params) {
        return result(() -> {
            String jobId = requiredString(params, "jobId");
            int cursor = JsonRpcWorker.integer(params, "cursor", 0);
            return jobs.snapshot(jobId, cursor);
        });
    }

    // ---- UI-facing async split (session-keyed) ----------------------------

    public Object splitStart(Map<String, Object> params) {
        return result(() -> {
            String session = string(params, "session");
            if (session == null || session.isBlank()) return failure("session is required");
            SplitConfig cfg = sessions.get(session);
            if (cfg.analysisResult == null) return failure("Call analyze first.");
            return startSplitJob(cfg, params);
        });
    }

    public Object splitStatus(Map<String, Object> params) {
        return result(() -> {
            String jobId = requiredString(params, "jobId");
            int cursor = JsonRpcWorker.integer(params, "cursor", 0);
            return jobs.snapshot(jobId, cursor);
        });
    }

    public Object splitCancel(Map<String, Object> params) {
        return result(() -> {
            String jobId = requiredString(params, "jobId");
            if (!jobs.cancel(jobId)) return failure("job is no longer running: " + jobId);
            return ok("cancel requested", "jobId", jobId);
        });
    }

    /**
     * Shared entry for both UI and AI job paths: validate config, then launch the split on a
     * virtual thread with cooperative cancellation wired into {@link ExcelSplitter}.
     */
    private Map<String, Object> startSplitJob(SplitConfig cfg, Map<String, Object> params) {
        if (cfg.mode == null) return failure("Call configure / excel_configure first.");
        String outputDir = requiredPath(params, "outputDir");
        cfg.outputDir = Paths.get(outputDir.trim());
        cfg.filePrefix = trimmed(string(params, "filePrefix"));
        try { Files.createDirectories(cfg.outputDir); } catch (Exception ignored) {}

        Jobs.Job job = jobs.start("SPLIT", handle -> {
            ExcelSplitter splitter = new ExcelSplitter(cfg, (pct, msg) -> handle.log(msg), handle::isCancelled);
            try {
                ExcelSplitter.SplitResult res = splitter.split();
                handle.setSummary(Map.of(
                    "fileCount", res.fileCount(),
                    "files", res.outputFiles().stream().map(p -> p.getFileName().toString()).toList()));
            } catch (Exception e) {
                // Jobs.start flattens exceptions to a one-line markFailed without a stack trace;
                // log the full stack here so the host log surface has diagnostics, then rethrow.
                log.error("split job failed for {} (mode={}): {}", cfg.sourceFile, cfg.mode, e.toString(), e);
                throw e;
            }
        });
        return ok("split started", "jobId", job.id);
    }

    public Object aiQuery(Map<String, Object> params) {
        return result(() -> {
            SplitConfig cfg = sessions.active().orElse(null);
            if (cfg == null) return failure("No active Excel session; call excel_analyze first.");
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("sourceFile", cfg.sourceFile != null ? cfg.sourceFile.toString() : null);
            state.put("mode", cfg.mode != null ? cfg.mode.name() : null);
            state.put("selectedSheets", cfg.selectedSheets);
            state.put("splitSheet", cfg.splitSheet);
            state.put("splitColumnIndex", cfg.splitColumnIndex);
            state.put("complexEntries", cfg.complexEntries.size());
            state.put("outputDir", cfg.outputDir != null ? cfg.outputDir.toString() : null);
            return ok("mode=" + (cfg.mode != null ? cfg.mode.name() : "unset"), "state", state);
        });
    }

    public Object aiCancel(Map<String, Object> params) {
        return result(() -> {
            sessions.remove(AI_SESSION);
            return ok("session reset", null, null);
        });
    }

    // ---- param helpers -----------------------------------------------------

    private static String string(Map<String, Object> params, String key) {
        return JsonRpcWorker.string(params, key);
    }

    private static String requiredString(Map<String, Object> params, String key) {
        String value = string(params, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String requiredPath(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(key + " must be a resolved FengYu file reference");
        }
        return value;
    }

    private static int integer(Map<String, Object> params, String key, int fallback) {
        return JsonRpcWorker.integer(params, key, fallback);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : null;
    }
}
