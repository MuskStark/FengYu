package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Adapts the Excel split engine to official SDK JSON-RPC handlers. Covers both the
 * session-keyed UI workflow ({@code analyze}/{@code configure}/{@code split}) and the
 * stateless AI tools ({@code excel_*}) declared in {@code manifest.json}. The AI tools
 * share a single {@code "ai"} session so a model can drive the whole flow in sequence.
 */
public final class ExcelRpcHandlers {
    static final String AI_SESSION = "ai";

    private final ExcelSessionStore sessions;
    private final ExcelPlugin plugin;

    public ExcelRpcHandlers(ExcelSessionStore sessions) {
        this.sessions = sessions;
        this.plugin = new ExcelPlugin(sessions);
    }

    // ---- UI-facing, session-keyed workflow ---------------------------------

    public Object analyze(Map<String, Object> params) {
        return plugin.invoke("analyze", params);
    }

    public Object configure(Map<String, Object> params) {
        return plugin.invoke("configure", params);
    }

    public Object split(Map<String, Object> params) {
        return plugin.invoke("split", params);
    }

    // ---- AI-facing, stateless (shared "ai" session) ------------------------

    public Object aiAnalyze(Map<String, Object> params) {
        return result(() -> {
            String filePath = requiredString(params, "filePath");
            Path file = Paths.get(filePath.trim());
            if (!Files.exists(file) || !Files.isReadable(file)) return failure("File not found: " + filePath);
            SplitConfig cfg = sessions.get(AI_SESSION);
            cfg.sourceFile = file;
            try { cfg.analysisResult = ExcelSplitter.analyze(file); }
            catch (Exception e) { return failure("Analyze failed: " + safeMessage(e)); }
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
            String outputDir = string(params, "outputDir");
            if (outputDir == null || outputDir.isBlank()) return failure("outputDir is required");
            cfg.outputDir = Paths.get(outputDir.trim());
            cfg.filePrefix = trimmed(string(params, "filePrefix"));
            try { Files.createDirectories(cfg.outputDir); } catch (Exception ignored) {}
            ExcelSplitter.SplitResult res;
            try { res = new ExcelSplitter(cfg, null).split(); }
            catch (Exception e) { return failure("Split failed: " + safeMessage(e)); }
            return ok("wrote " + res.fileCount() + " file(s)", "files", Map.of(
                "fileCount", res.fileCount(),
                "files", res.outputFiles().stream().map(p -> p.getFileName().toString()).toList()));
        });
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

    /** Wraps a handler so every result follows the {success, summary, ...} contract. */
    public PluginHandler safe(PluginHandler handler) {
        return params -> {
            try { return cast(handler.handle(params)); }
            catch (Exception error) { return failure(safeMessage(error)); }
        };
    }

    // ---- helpers -----------------------------------------------------------

    private Map<String, Object> result(Supplier<Map<String, Object>> operation) {
        try { return operation.get(); }
        catch (Exception error) { return failure(safeMessage(error)); }
    }

    private static Map<String, Object> ok(String summary, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", summary);
        if (key != null) result.put(key, value);
        return result;
    }

    private static Map<String, Object> failure(String summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("summary", summary == null || summary.isBlank() ? "Excel operation failed" : summary);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("Handler returned an invalid result");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Excel operation failed";
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    private static String string(Map<String, Object> params, String key) {
        return JsonRpcWorker.string(params, key);
    }

    private static String requiredString(Map<String, Object> params, String key) {
        String value = string(params, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
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
