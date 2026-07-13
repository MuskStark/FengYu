package fan.summer.fengyu.plugin.excel;


import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

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
