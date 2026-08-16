package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.PluginMessages;
import org.apache.fesod.sheet.ExcelReader;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.read.metadata.ReadSheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Core Excel splitting engine that performs file analysis and three split modes
 * ({@link SplitConfig.SplitMode#BY_SHEET}, {@link SplitConfig.SplitMode#BY_COLUMN},
 * {@link SplitConfig.SplitMode#COMPLEX}).
 *
 * <p>An {@link ExcelSplitter} instance is constructed with a {@link SplitConfig} containing
 * all parameters (source file, mode, output directory, etc.) and a progress callback. The
 * {@link #split()} method executes synchronously on the calling thread; callers should
 * typically invoke it on a background thread.
 *
 * <p>Analysis (reading headers from all sheets) is provided as a static method so the UI
 * layer can populate the configuration before the user commits to a split.
 *
 * @since 3.0.0
 * @see SplitConfig
 */
public class ExcelSplitter {

    private static final Logger logger = LoggerFactory.getLogger(ExcelSplitter.class);

    /**
     * Localized message resolver for split progress log messages, resolved once per instance from
     * this plugin's {@code i18n/messages[_zh].properties}. A field rather than a constructor param
     * so existing callers (and tests) keep working unchanged.
     */
    private final PluginMessages msgs = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, ExcelSplitter.class);

    /**
     * Result of a split operation, containing the number of output files produced and
     * the absolute paths of those files.
     *
     * @param fileCount  total output file count
     * @param outputFiles ordered list of output file paths
     */
    public record SplitResult(int fileCount, List<Path> outputFiles) {}

    private final SplitConfig config;
    private final BiConsumer<Double, String> progress;
    private final Supplier<Boolean> shouldCancel;

    /**
     * Creates a new splitter for the given configuration and progress callback.
     *
     * @param config   split configuration (must not be null)
     * @param progress callback invoked repeatedly with (0.0–1.0 progress, status message);
     *                 may be null
     * @throws NullPointerException if config is null
     */
    public ExcelSplitter(SplitConfig config, BiConsumer<Double, String> progress) {
        this(config, progress, null);
    }

    /**
     * Creates a new splitter with an optional cooperative-cancel probe.
     *
     * <p>When {@code shouldCancel} returns {@code true} the split aborts by throwing
     * {@link InterruptedException} at the next checkpoint (between sheets, between column groups,
     * between complex-split entries). This lets a job-mode caller ({@code split_start} /
     * {@code excel_execute_start}) wire a {@code Jobs.Cancellable} into the engine without the
     * engine depending on the SDK. The synchronous RPC path passes {@code null}.
     *
     * @param config        split configuration (must not be null)
     * @param progress      callback invoked repeatedly with (0.0–1.0 progress, status message);
     *                      may be null
     * @param shouldCancel  returns {@code true} to request cooperative cancellation; may be null
     * @throws NullPointerException if config is null
     * @since 4.0.0
     */
    public ExcelSplitter(SplitConfig config, BiConsumer<Double, String> progress, Supplier<Boolean> shouldCancel) {
        this.config = Objects.requireNonNull(config);
        this.progress = progress;
        this.shouldCancel = shouldCancel;
    }

    /**
     * Reads all sheets of the given Excel file and returns the header row of each sheet
     * as a map of column index to header string.
     *
     * <p>This method is static and thread-safe; it may be called from a background thread.
     *
     * @param file the path to the Excel file (.xls or .xlsx)
     * @return an ordered map keyed by sheet name; each value maps zero-based column index
     *         to the trimmed header string found in row 0
     * @throws Exception if the file cannot be opened or read (IOException, POI exception)
     */
    public static Map<String, Map<Integer, String>> analyze(Path file) throws Exception {
        Map<String, Map<Integer, String>> result = new LinkedHashMap<>();
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                Map<Integer, String> headers = new LinkedHashMap<>();
                Row headerRow = sheet.getRow(0);
                if (headerRow != null) {
                    for (int c = headerRow.getFirstCellNum(); c < headerRow.getLastCellNum(); c++) {
                        var cell = headerRow.getCell(c);
                        if (cell != null) {
                            headers.put(c, cell.toString().trim());
                        }
                    }
                }
                result.put(sheet.getSheetName(), headers);
            }
        }
        return result;
    }

    /**
     * Executes the configured split operation and returns the result.
     *
     * <p>This method blocks until all output files are written. The current thread
     * should therefore be a background thread to avoid freezing the UI.
     *
     * @return a {@link SplitResult} containing the file count and output paths
     * @throws Exception if the split fails (file access, etc.)
     */
    public SplitResult split() throws Exception {
        onProgress(0.0, msgs.format("ex.progress.starting"));
        return switch (config.mode) {
            case BY_SHEET  -> splitBySheet();
            case BY_COLUMN -> splitByColumn();
            case COMPLEX   -> complexSplit();
        };
    }

    private void onProgress(double pct, String msg) {
        if (progress != null) progress.accept(pct, msg);
    }

    /**
     * Cooperative cancel checkpoint. Throws {@link InterruptedException} (treated as cancellation
     * by the job wrapper) if {@code shouldCancel} is wired and reports {@code true}. No-op when
     * the splitter was constructed without a cancel probe (synchronous RPC path).
     */
    private void checkCancelled() throws InterruptedException {
        if (shouldCancel != null && Boolean.TRUE.equals(shouldCancel.get())) {
            throw new InterruptedException("split cancelled");
        }
    }

    /** {@link #checkCancelled()} for use inside stream lambdas, which cannot throw checked exceptions. */
    private void checkCancelledUnchecked() {
        try {
            checkCancelled();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Resolves {@code fileName} under the configured output directory and defensively verifies
     * the normalized result stays inside it. Split keys are cell values sanitized into filename
     * segments, so escaping should be impossible — this containment check is the second line
     * of defence against a regression in the sanitization.
     */
    private Path resolveOutput(String fileName) {
        Path dir = config.outputDir.normalize();
        Path out = dir.resolve(fileName).normalize();
        if (!out.startsWith(dir)) {
            throw new RuntimeException(msgs.format("ex.err.outputOutsideDir", fileName));
        }
        return out;
    }

    /**
     * Bookkeeping of a split's planned output paths, so a failed or cancelled run can remove
     * every file the split created — successfully written outputs AND files abandoned mid-write
     * when the failure hit (an interrupted writer's partial file never reaches the caller's
     * completed-files list). Paths that already existed before the split are never deleted: the
     * split does not own them.
     */
    private static final class OutputPlan {
        private final Set<Path> planned = new LinkedHashSet<>();
        private final Set<Path> preExisting = new HashSet<>();

        /** Registers a planned output path, snapshotting whether it already exists. */
        void add(Path p) {
            if (!planned.add(p)) return; // already planned (e.g. two keys sanitize to one name)
            if (Files.exists(p)) preExisting.add(p);
        }

        /** Best-effort removal of planned outputs this split created; deletion failures are
         *  logged and swallowed (the original error wins). */
        void deleteCreated() {
            for (Path p : planned) {
                if (preExisting.contains(p)) continue;
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    logger.warn("could not delete partial output {}: {}", p, e.toString());
                }
            }
        }
    }

    private SplitResult splitBySheet() throws Exception {
        List<String> sheets = (config.selectedSheets != null && !config.selectedSheets.isEmpty())
                ? config.selectedSheets
                : new ArrayList<>(config.analysisResult.keySet());

        logger.info("Split by sheet | file={}, sheets={}", config.sourceFile.getFileName(), sheets.size());

        List<Path> outputs = new ArrayList<>();
        OutputPlan plan = new OutputPlan();
        NoModelDataListener listener = new NoModelDataListener();

        try (ExcelReader reader = FesodSheet.read(config.sourceFile.toFile()).build()) {
            for (int i = 0; i < sheets.size(); i++) {
                String sheetName = sheets.get(i);
                checkCancelled();
                Map<Integer, String> headerMap = config.analysisResult.get(sheetName);
                if (headerMap == null) {
                    // Stale selection (e.g. the sheet list outlived a re-analyze): fail with a
                    // clear localized error instead of an NPE from TreeMap(null) in buildHeaders.
                    throw new RuntimeException(msgs.format("ex.err.unknownSheet", sheetName));
                }
                onProgress((double) i / sheets.size(), msgs.format("ex.progress.processingSheet", sheetName));

                ReadSheet readSheet = FesodSheet.readSheet(sheetName)
                        .registerReadListener(listener).build();
                reader.read(readSheet);

                List<Map<Integer, Object>> rows = listener.getCachedDataList();

                Path out = resolveOutput(outputFileName(sheetName));
                plan.add(out);
                FesodSheet.write(out.toFile())
                        .sheet(sheetName)
                        .head(buildHeaders(headerMap))
                        .doWrite(buildRows(headerMap, rows));

                outputs.add(out);
                listener.clear();
            }
        } catch (Exception e) {
            // A failed/cancelled run must not leave partial outputs that look complete.
            plan.deleteCreated();
            throw e;
        }

        onProgress(1.0, msgs.format("ex.progress.done"));
        logger.info("Split by sheet completed | files={}", outputs.size());
        return new SplitResult(outputs.size(), outputs);
    }

    private SplitResult splitByColumn() throws Exception {
        String sheetName = config.splitSheet;
        int colIdx = config.splitColumnIndex;
        Map<Integer, String> headerMap = config.analysisResult.get(sheetName);

        logger.info("Split by column | file={}, sheet={}, colIdx={}", config.sourceFile.getFileName(), sheetName, colIdx);

        NoModelDataListener listener = new NoModelDataListener();
        try (ExcelReader reader = FesodSheet.read(config.sourceFile.toFile()).build()) {
            ReadSheet readSheet = FesodSheet.readSheet(sheetName)
                    .registerReadListener(listener).build();
            reader.read(readSheet);
        }

        Map<Object, List<Map<Integer, Object>>> groups = new LinkedHashMap<>(
                listener.getCachedDataList().stream()
                        .collect(Collectors.groupingBy(row ->
                                ExcelUtil.normalizeOrInvalid(row.getOrDefault(colIdx, null)))));
        listener.clear();

        // Plan every output path BEFORE any write: on failure the cleanup must also remove
        // files abandoned mid-write by an interrupted sibling writer (which never reach
        // `outputs`), while never touching files that pre-date this split.
        Map<Object, Path> plannedByKey = new LinkedHashMap<>();
        OutputPlan plan = new OutputPlan();
        for (Object key : groups.keySet()) {
            String suffix = FileNameUtil.getFileName(config.sourceFile.getFileName().toString())
                    + "_" + FileNameUtil.sanitizeSegment(key.toString());
            Path out = resolveOutput(outputFileName(suffix));
            plannedByKey.put(key, out);
            plan.add(out);
        }

        int total = groups.size();
        AtomicInteger current = new AtomicInteger(0);
        List<Path> outputs = Collections.synchronizedList(new ArrayList<>());

        int threads = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        ForkJoinPool pool = new ForkJoinPool(threads);
        checkCancelled();
        try {
            pool.submit(() ->
                groups.entrySet().parallelStream().forEach(e -> {
                    // Cooperative checkpoint inside the write loop so a cancel is honoured
                    // per group, not only once for the whole parallel phase.
                    checkCancelledUnchecked();
                    Object key = e.getKey();
                    Path out = plannedByKey.get(key);
                    FesodSheet.write(out.toFile())
                            .sheet(sheetName)
                            .head(buildHeaders(headerMap))
                            .doWrite(buildRows(headerMap, e.getValue()));
                    outputs.add(out);
                    int n = current.incrementAndGet();
                    onProgress((double) n / total, msgs.format("ex.progress.writing", key));
                })
            ).get();
        } catch (Exception e) {
            // A failed/cancelled run must not leave completed partial outputs: stop sibling
            // writers still in flight (an interrupted writer abandons a partial file that
            // never reaches `outputs`), wait for the pool to quiesce, then remove every
            // planned output this split created (best-effort).
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            plan.deleteCreated();
            throw e;
        } finally {
            pool.shutdown();
        }

        onProgress(1.0, msgs.format("ex.progress.done"));
        logger.info("Split by column completed | groups={}", total);
        return new SplitResult(outputs.size(), outputs);
    }

    private SplitResult complexSplit() throws Exception {
        List<ComplexSplitEntry> splitConfigs = config.complexEntries;
        if (splitConfigs == null || splitConfigs.isEmpty()) {
            throw new RuntimeException(msgs.format("ex.err.noComplexEntries"));
        }

        List<ComplexSplitEntry> normalConfigs = new ArrayList<>();
        List<ComplexSplitEntry> copyAllConfigs = new ArrayList<>();
        for (ComplexSplitEntry cfg : splitConfigs) {
            if (cfg.headerIndex() == -1 && cfg.columnIndex() == -1) {
                copyAllConfigs.add(cfg);
            } else {
                normalConfigs.add(cfg);
            }
        }

        // === Phase 1: Single-pass read per normal config, build output plan ===
        record WriteTask(ComplexSplitEntry cfg, List<Map<Integer, Object>> rows) {}
        Map<String, List<WriteTask>> plan = new LinkedHashMap<>();

        for (int i = 0; i < normalConfigs.size(); i++) {
            ComplexSplitEntry cfg = normalConfigs.get(i);
            checkCancelled();
            onProgress(0.05 + 0.3 * i / Math.max(1, normalConfigs.size()),
                    msgs.format("ex.progress.reading", cfg.sheetName()));

            NoModelDataListener listener = new NoModelDataListener();
            try (ExcelReader reader = FesodSheet.read(config.sourceFile.toFile()).build()) {
                ReadSheet sheet = FesodSheet.readSheet(cfg.sheetName())
                        .headRowNumber(cfg.headerIndex())
                        .registerReadListener(listener).build();
                reader.read(sheet);
            }

            int colKey = cfg.columnIndex() - 1;
            listener.getCachedDataList().stream()
                    .collect(Collectors.groupingBy(row ->
                            ExcelUtil.normalizeOrInvalid(row.getOrDefault(colKey, null))))
                    .forEach((key, rows) -> {
                        String baseName = FileNameUtil.getFileName(config.sourceFile.getFileName().toString())
                                + "_" + FileNameUtil.sanitizeSegment(key) + ".xlsx";
                        plan.computeIfAbsent(baseName, k -> new ArrayList<>())
                                .add(new WriteTask(cfg, rows));
                    });
            listener.clear();
        }

        // === Phase 2 & 3: source opened ONCE for both write + copyAll ===
        int totalFiles = plan.size();
        int writeDone = 0;

        // Track only the files we create, so Phase 3 doesn't corrupt pre-existing files
        List<Path> createdFiles = new ArrayList<>();
        // Cleanup bookkeeping: covers completed files AND the one abandoned mid-write when a
        // failure hits (createdFiles only records successful writes).
        OutputPlan outputPlan = new OutputPlan();

        try (FileInputStream srcFis = new FileInputStream(config.sourceFile.toFile());
             Workbook srcWb = WorkbookFactory.create(srcFis)) {

            // Phase 2: one XSSFWorkbook per output file, flushed to disk once
            for (Map.Entry<String, List<WriteTask>> entry : plan.entrySet()) {
                checkCancelled();
                String baseName = entry.getKey();
                Path outPath = resolveOutput(baseName);
                outputPlan.add(outPath);

                try (XSSFWorkbook tgtWb = new XSSFWorkbook()) {
                    for (WriteTask task : entry.getValue()) {
                        Sheet srcSheet = srcWb.getSheet(task.cfg().sheetName());
                        if (srcSheet == null) continue;
                        ExcelUtil.copyHeaderToWorkbook(srcSheet, tgtWb,
                                task.cfg().sheetName(), task.cfg().headerIndex() - 1);
                        Sheet tgtSheet = tgtWb.getSheet(task.cfg().sheetName());
                        Row templateRow = srcSheet.getRow(task.cfg().headerIndex());
                        ExcelUtil.writeDataRowsToSheet(tgtSheet, tgtWb, templateRow,
                                task.cfg().headerIndex(), task.rows());
                    }
                    try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                        tgtWb.write(fos);
                    }
                }

                createdFiles.add(outPath);
                writeDone++;
                onProgress(0.35 + 0.5 * writeDone / Math.max(1, totalFiles), msgs.format("ex.progress.writing", baseName));
            }

            // Phase 3: copyAll sheets — only merge into files created by Phase 2
            if (!copyAllConfigs.isEmpty()) {
                for (int i = 0; i < createdFiles.size(); i++) {
                    checkCancelled();
                    File targetFile = createdFiles.get(i).toFile();
                    try (FileInputStream tgtFis = new FileInputStream(targetFile);
                         Workbook tgtWb = WorkbookFactory.create(tgtFis)) {
                        for (ComplexSplitEntry copyConfig : copyAllConfigs) {
                            Sheet srcSheet = srcWb.getSheet(copyConfig.sheetName());
                            if (srcSheet != null && tgtWb.getSheet(copyConfig.sheetName()) == null) {
                                ExcelUtil.copySheetToWorkbook(srcSheet, tgtWb);
                            }
                        }
                        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                            tgtWb.write(fos);
                        }
                    }
                    onProgress(0.85 + 0.15 * (i + 1) / Math.max(1, createdFiles.size()),
                            msgs.format("ex.progress.copyingSheets", targetFile.getName()));
                }
            }
        } catch (Exception e) {
            // A failed/cancelled run must not leave partial outputs that look complete —
            // files mid-rewrite in Phase 3 are still partial (missing copy-all sheets).
            outputPlan.deleteCreated();
            throw e;
        }

        List<Path> outputPaths = createdFiles.stream()
                .sorted(java.util.Comparator.comparing(Path::getFileName))
                .collect(Collectors.toList());

        onProgress(1.0, msgs.format("ex.progress.done"));
        logger.info("Complex split completed | normalConfigs={}, copyAllConfigs={}, outputFiles={}",
                normalConfigs.size(), copyAllConfigs.size(), outputPaths.size());
        return new SplitResult(outputPaths.size(), outputPaths);
    }

    private static List<List<String>> buildHeaders(Map<Integer, String> headMap) {
        List<List<String>> headers = new ArrayList<>();
        new TreeMap<>(headMap).forEach((index, name) -> headers.add(Collections.singletonList(name)));
        return headers;
    }

    private static List<List<Object>> buildRows(Map<Integer, String> headMap,
                                                List<Map<Integer, Object>> dataList) {
        List<Integer> sortedKeys = new ArrayList<>(new TreeMap<>(headMap).keySet());
        List<List<Object>> rows = new ArrayList<>();
        for (Map<Integer, Object> rowMap : dataList) {
            List<Object> row = new ArrayList<>();
            for (Integer key : sortedKeys) {
                Object val = rowMap.get(key);
                row.add(val != null ? val : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private String outputFileName(String suffix) {
        String prefix = (config.filePrefix == null || config.filePrefix.isBlank())
                ? "" : config.filePrefix + "_";
        return prefix + suffix + ".xlsx";
    }
}
