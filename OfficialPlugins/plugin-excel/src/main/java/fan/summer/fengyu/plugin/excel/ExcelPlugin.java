package fan.summer.fengyu.plugin.excel;


import org.apache.fesod.sheet.ExcelReader;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.read.metadata.ReadSheet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ExcelPlugin {
    private final ExcelSessionStore sessions;

    public ExcelPlugin(ExcelSessionStore sessions) { this.sessions = sessions; }

    public Object invoke(String action, Map<String, Object> args) {
        String session = str(args, "session");
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("session is required");
        }
        return switch (action) {
            case "analyze"   -> analyze(session, args);
            case "configure" -> configure(session, args);
            case "estimate"  -> estimate(session, args);
            case "split"     -> split(session, args);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private Map<String, Object> analyze(String session, Map<String, Object> args) {
        Path file = requirePath(args, "sourceFile");
        SplitConfig cfg = sessions.get(session);
        cfg.sourceFile = file;
        try {
            cfg.analysisResult = ExcelSplitter.analyze(file);
        } catch (Exception e) {
            throw new IllegalArgumentException("Analyze failed: " + e.getMessage(), e);
        }
        Map<String, Map<String, String>> sheets = new LinkedHashMap<>();
        cfg.analysisResult.forEach((name, cols) -> {
            Map<String, String> m = new LinkedHashMap<>();
            cols.forEach((idx, header) -> m.put(String.valueOf(idx), header));
            sheets.put(name, m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", "analyzed " + sheets.size() + " sheet(s)");
        out.put("sheets", sheets);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configure(String session, Map<String, Object> args) {
        SplitConfig cfg = sessions.get(session);
        applyConfig(cfg, args);
        switch (cfg.mode) {
            case BY_SHEET -> validateSelectedSheets(cfg);
            case BY_COLUMN -> validateColumn(cfg);
            case COMPLEX -> validateComplex(cfg);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", "configured mode=" + cfg.mode);
        return out;
    }

    /**
     * Applies the split-config fields from {@code args} onto {@code cfg}. Shared by {@code configure}
     * and {@code split} so that a {@code split} call carries everything it needs to run even when the
     * worker process was restarted between {@code configure} and {@code split} — the host tears down
     * and relaunches a plugin worker whenever its file-grant version changes (e.g. the user picks an
     * output folder on the wizard's Output step), which wipes the in-memory session store. Sending
     * the full config on {@code split} makes the session store a cache rather than a correctness
     * dependency. Fields absent from {@code args} are left untouched (partial-update semantics),
     * preserving the existing AI/UI behavior for callers that rely on a prior {@code configure}.
     */
    @SuppressWarnings("unchecked")
    private static void applyConfig(SplitConfig cfg, Map<String, Object> args) {
        String mode = str(args, "mode");
        if (mode != null) cfg.mode = SplitConfig.SplitMode.valueOf(mode);
        Object sel = args.get("selectedSheets");
        if (sel instanceof List<?> l) cfg.selectedSheets = l.stream().map(String::valueOf).toList();
        else if (!args.containsKey("selectedSheets") && cfg.analysisResult != null) {
            cfg.selectedSheets = new ArrayList<>(cfg.analysisResult.keySet());
        }
        if (args.get("splitSheet") != null) cfg.splitSheet = str(args, "splitSheet");
        if (args.get("splitColumn") != null) cfg.splitColumn = str(args, "splitColumn");
        if (args.get("splitColumnIndex") != null) {
            cfg.splitColumnIndex = num(args, "splitColumnIndex");
        } else if (cfg.splitColumn != null && cfg.analysisResult != null) {
            // UI sends header TEXT, not an index; resolve it against the analyzed headers
            // for the target sheet so ExcelSplitter.splitByColumn() groups correctly.
            Map<Integer, String> headers = cfg.analysisResult.get(cfg.splitSheet);
            if (headers != null) {
                for (Map.Entry<Integer, String> e : headers.entrySet()) {
                    if (cfg.splitColumn.equals(e.getValue())) {
                        cfg.splitColumnIndex = e.getKey();
                        break;
                    }
                }
            }
        }
        if (args.get("filePrefix") != null) cfg.filePrefix = str(args, "filePrefix");
        Object entries = args.get("complexEntries");
        if (entries instanceof List<?> l) {
            List<ComplexSplitEntry> parsed = new ArrayList<>();
            for (Object o : l) {
                Map<String, Object> m = (Map<String, Object>) o;
                parsed.add(new ComplexSplitEntry(
                    String.valueOf(m.getOrDefault("fieldName", "")),
                    String.valueOf(m.get("sheetName")),
                    ((Number) m.getOrDefault("headerIndex", -1)).intValue(),
                    ((Number) m.getOrDefault("columnIndex", -1)).intValue()));
            }
            cfg.complexEntries = parsed;
        }
    }

    private static void validateSelectedSheets(SplitConfig cfg) {
        if (cfg.selectedSheets == null || cfg.selectedSheets.isEmpty()) {
            throw new IllegalArgumentException("Select at least one sheet");
        }
        if (cfg.analysisResult == null
                || !cfg.analysisResult.keySet().containsAll(cfg.selectedSheets)) {
            throw new IllegalArgumentException("Selected sheet does not exist");
        }
    }

    private static void validateColumn(SplitConfig cfg) {
        Map<Integer, String> headers = cfg.analysisResult == null
            ? null : cfg.analysisResult.get(cfg.splitSheet);
        if (headers == null) {
            throw new IllegalArgumentException("Select a valid sheet");
        }
        if (cfg.splitColumnIndex < 0 || !headers.containsKey(cfg.splitColumnIndex)) {
            throw new IllegalArgumentException("Select a valid split column");
        }
    }

    private static void validateComplex(SplitConfig cfg) {
        if (cfg.complexEntries == null || cfg.complexEntries.isEmpty()) {
            throw new IllegalArgumentException("Add at least one complex rule");
        }
        for (ComplexSplitEntry entry : cfg.complexEntries) {
            if (cfg.analysisResult == null || !cfg.analysisResult.containsKey(entry.sheetName())) {
                throw new IllegalArgumentException(
                    "Complex rule sheet does not exist: " + entry.sheetName());
            }
            boolean copyAll = entry.headerIndex() == -1 && entry.columnIndex() == -1;
            if (!copyAll && (entry.headerIndex() < 1 || entry.columnIndex() < 1)) {
                throw new IllegalArgumentException(
                    "Header row and split column must be positive integers");
            }
        }
    }

    private Map<String, Object> split(String session, Map<String, Object> args) {
        SplitConfig cfg = sessions.get(session);
        cfg.sourceFile = requirePath(args, "sourceFile");
        cfg.outputDir = requirePath(args, "outputDir");
        if (cfg.analysisResult == null) {
            try { cfg.analysisResult = ExcelSplitter.analyze(cfg.sourceFile); }
            catch (Exception e) { throw new IllegalArgumentException("Analyze failed: " + e.getMessage(), e); }
        }
        // Re-apply the split config from this call's args so split is self-contained: the host may
        // restart the worker (and thus wipe the in-memory session) between configure and split —
        // see applyConfig. A split call that omits the config fields still works as before because
        // applyConfig leaves absent fields untouched.
        applyConfig(cfg, args);
        ExcelSplitter.SplitResult res;
        try {
            java.nio.file.Files.createDirectories(cfg.outputDir);
            res = new ExcelSplitter(cfg, null).split();
        } catch (Exception e) {
            throw new IllegalArgumentException("Split failed: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", "wrote " + res.fileCount() + " file(s)");
        out.put("fileCount", res.fileCount());
        out.put("files", res.outputFiles().stream().map(p -> p.getFileName().toString()).toList());
        return out;
    }

    /**
     * Estimates the exact number of output files the configured split would produce, without
     * writing anything. Requires a prior {@code analyze} + {@code configure} on the session.
     *
     * <ul>
     *   <li>BY_SHEET — selected (or all) sheet count; no data rows read.</li>
     *   <li>BY_COLUMN — distinct values in the split column of the target sheet.</li>
     *   <li>COMPLEX — cardinality of the Phase-1 output plan (distinct column-value keys across
     *       all normal entries, merged by output base name); copy-all rules only merge into
     *       files the normal rules already create, so they never add to the count.</li>
     * </ul>
     */
    private Map<String, Object> estimate(String session, Map<String, Object> args) {
        SplitConfig cfg = sessions.get(session);
        if (cfg.analysisResult == null) {
            throw new IllegalArgumentException("Call analyze first.");
        }
        if (cfg.mode == null) {
            throw new IllegalArgumentException("Call configure first.");
        }
        int count;
        try {
            count = switch (cfg.mode) {
                case BY_SHEET -> estimateBySheet(cfg);
                case BY_COLUMN -> estimateByColumn(cfg);
                case COMPLEX -> estimateComplex(cfg);
            };
        } catch (Exception e) {
            throw new IllegalArgumentException("Estimate failed: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", "estimated " + count + " file(s)");
        out.put("fileCount", count);
        out.put("exact", true);
        return out;
    }

    private static int estimateBySheet(SplitConfig cfg) {
        if (cfg.selectedSheets != null && !cfg.selectedSheets.isEmpty()) return cfg.selectedSheets.size();
        return cfg.analysisResult.size();
    }

    private static int estimateByColumn(SplitConfig cfg) throws Exception {
        String sheetName = cfg.splitSheet;
        int colIdx = cfg.splitColumnIndex;
        NoModelDataListener listener = new NoModelDataListener();
        try (ExcelReader reader = FesodSheet.read(cfg.sourceFile.toFile()).build()) {
            ReadSheet readSheet = FesodSheet.readSheet(sheetName)
                    .registerReadListener(listener).build();
            reader.read(readSheet);
        }
        int distinct = (int) listener.getCachedDataList().stream()
                .map(row -> ExcelUtil.normalizeOrInvalid(row.getOrDefault(colIdx, null)))
                .distinct()
                .count();
        listener.clear();
        return distinct;
    }

    private static int estimateComplex(SplitConfig cfg) throws Exception {
        List<ComplexSplitEntry> normalConfigs = cfg.complexEntries.stream()
                .filter(e -> !(e.headerIndex() == -1 && e.columnIndex() == -1))
                .toList();
        if (normalConfigs.isEmpty()) {
            // Only copy-all rules: they need existing files to merge into, which the current
            // engine does not create on their own — the split would produce nothing new.
            return 0;
        }
        String sourceBase = FileNameUtil.getFileName(cfg.sourceFile.getFileName().toString());
        Set<String> outputNames = new LinkedHashSet<>();
        NoModelDataListener listener = new NoModelDataListener();
        for (ComplexSplitEntry entry : normalConfigs) {
            try (ExcelReader reader = FesodSheet.read(cfg.sourceFile.toFile()).build()) {
                ReadSheet readSheet = FesodSheet.readSheet(entry.sheetName())
                        .headRowNumber(entry.headerIndex())
                        .registerReadListener(listener).build();
                reader.read(readSheet);
            }
            int colKey = entry.columnIndex() - 1;
            listener.getCachedDataList().stream()
                    .map(row -> ExcelUtil.normalizeOrInvalid(row.getOrDefault(colKey, null)))
                    .forEach(key -> outputNames.add(sourceBase + "_" + key + ".xlsx"));
            listener.clear();
        }
        return outputNames.size();
    }

    private static String str(Map<String, Object> args, String k) {
        Object v = args == null ? null : args.get(k);
        return v == null ? null : v.toString();
    }
    private static int num(Map<String, Object> args, String k) {
        return ((Number) args.get(k)).intValue();
    }
    private static Path requirePath(Map<String, Object> args, String k) {
        String v = str(args, k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(k + " is required");
        return Paths.get(v);
    }
}
