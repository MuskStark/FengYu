package fan.summer.buildintool.excelsplitter;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import fan.summer.database.mapper.excel.ComplexSplitConfigMapper;
import org.apache.fesod.sheet.ExcelReader;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.read.metadata.ReadSheet;
import org.apache.ibatis.session.SqlSession;
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
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
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
 * @see ExcelSplitterPlugin
 */
public class ExcelSplitter {

    private static final Logger logger = LoggerFactory.getLogger(ExcelSplitter.class);

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

    /**
     * Creates a new splitter for the given configuration and progress callback.
     *
     * @param config   split configuration (must not be null)
     * @param progress callback invoked repeatedly with (0.0–1.0 progress, status message);
     *                 may be null
     * @throws NullPointerException if config is null
     */
    public ExcelSplitter(SplitConfig config, BiConsumer<Double, String> progress) {
        this.config = Objects.requireNonNull(config);
        this.progress = progress;
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
     * @throws Exception if the split fails (file access, DB query, etc.)
     */
    public SplitResult split() throws Exception {
        progress.accept(0.0, "Starting...");
        return switch (config.mode) {
            case BY_SHEET  -> splitBySheet();
            case BY_COLUMN -> splitByColumn();
            case COMPLEX   -> complexSplit();
        };
    }

    private SplitResult splitBySheet() throws Exception {
        List<String> sheets = (config.selectedSheets != null && !config.selectedSheets.isEmpty())
                ? config.selectedSheets
                : new ArrayList<>(config.analysisResult.keySet());

        logger.info("Split by sheet | file={}, sheets={}", config.sourceFile.getFileName(), sheets.size());

        List<Path> outputs = new ArrayList<>();
        NoModelDataListener listener = new NoModelDataListener();

        try (ExcelReader reader = FesodSheet.read(config.sourceFile.toFile()).build()) {
            for (int i = 0; i < sheets.size(); i++) {
                String sheetName = sheets.get(i);
                progress.accept((double) i / sheets.size(), "Processing sheet: " + sheetName);

                ReadSheet readSheet = FesodSheet.readSheet(sheetName)
                        .registerReadListener(listener).build();
                reader.read(readSheet);

                List<Map<Integer, Object>> rows = listener.getCachedDataList();
                Map<Integer, String> headerMap = config.analysisResult.get(sheetName);

                Path out = config.outputDir.resolve(outputFileName(sheetName));
                FesodSheet.write(out.toFile())
                        .sheet(sheetName)
                        .head(buildHeaders(headerMap))
                        .doWrite(buildRows(headerMap, rows));

                outputs.add(out);
                listener.clear();
            }
        }

        progress.accept(1.0, "Done");
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

        int total = groups.size();
        AtomicInteger current = new AtomicInteger(0);
        List<Path> outputs = Collections.synchronizedList(new ArrayList<>());

        int threads = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            pool.submit(() ->
                groups.entrySet().parallelStream().forEach(e -> {
                    Object key = e.getKey();
                    String suffix = FileNameUtil.getFileName(config.sourceFile.getFileName().toString()) + "_" + key;
                    Path out = config.outputDir.resolve(outputFileName(suffix));
                    FesodSheet.write(out.toFile())
                            .sheet(sheetName)
                            .head(buildHeaders(headerMap))
                            .doWrite(buildRows(headerMap, e.getValue()));
                    outputs.add(out);
                    int n = current.incrementAndGet();
                    progress.accept((double) n / total, "Writing: " + key);
                })
            ).get();
        } finally {
            pool.shutdown();
        }

        progress.accept(1.0, "Done");
        logger.info("Split by column completed | groups={}", total);
        return new SplitResult(outputs.size(), outputs);
    }

    private SplitResult complexSplit() throws Exception {
        logger.info("Complex split | taskId={}", config.complexTaskId);

        List<ComplexSplitConfigEntity> splitConfigs;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
            splitConfigs = mapper.selectAllByTaskId(config.complexTaskId);
        }

        if (splitConfigs == null || splitConfigs.isEmpty()) {
            throw new RuntimeException("No complex split config found for taskId: " + config.complexTaskId);
        }

        List<ComplexSplitConfigEntity> normalConfigs = new ArrayList<>();
        List<ComplexSplitConfigEntity> copyAllConfigs = new ArrayList<>();
        for (ComplexSplitConfigEntity cfg : splitConfigs) {
            if (cfg.getHeaderIndex() != null && cfg.getColumnIndex() != null
                    && cfg.getHeaderIndex() == -1 && cfg.getColumnIndex() == -1) {
                copyAllConfigs.add(cfg);
            } else {
                normalConfigs.add(cfg);
            }
        }

        // === Phase 1: Single-pass read per normal config, build output plan ===
        record WriteTask(ComplexSplitConfigEntity cfg, List<Map<Integer, Object>> rows) {}
        Map<String, List<WriteTask>> plan = new LinkedHashMap<>();

        for (int i = 0; i < normalConfigs.size(); i++) {
            ComplexSplitConfigEntity cfg = normalConfigs.get(i);
            progress.accept(0.05 + 0.3 * i / Math.max(1, normalConfigs.size()),
                    "Reading: " + cfg.getSheetName());

            NoModelDataListener listener = new NoModelDataListener();
            try (ExcelReader reader = FesodSheet.read(config.sourceFile.toFile()).build()) {
                ReadSheet sheet = FesodSheet.readSheet(cfg.getSheetName())
                        .headRowNumber(cfg.getHeaderIndex())
                        .registerReadListener(listener).build();
                reader.read(sheet);
            }

            int colKey = cfg.getColumnIndex() - 1;
            listener.getCachedDataList().stream()
                    .collect(Collectors.groupingBy(row ->
                            ExcelUtil.normalizeOrInvalid(row.getOrDefault(colKey, null))))
                    .forEach((key, rows) -> {
                        String baseName = FileNameUtil.getFileName(config.sourceFile.getFileName().toString())
                                + "_" + key + ".xlsx";
                        plan.computeIfAbsent(baseName, k -> new ArrayList<>())
                                .add(new WriteTask(cfg, rows));
                    });
            listener.clear();
        }

        // === Phase 2 & 3: source opened ONCE for both write + copyAll ===
        int totalFiles = plan.size();
        int writeDone = 0;

        try (FileInputStream srcFis = new FileInputStream(config.sourceFile.toFile());
             Workbook srcWb = WorkbookFactory.create(srcFis)) {

            // Phase 2: one XSSFWorkbook per output file, flushed to disk once
            for (Map.Entry<String, List<WriteTask>> entry : plan.entrySet()) {
                String baseName = entry.getKey();
                Path outPath = config.outputDir.resolve(baseName);

                try (XSSFWorkbook tgtWb = new XSSFWorkbook()) {
                    for (WriteTask task : entry.getValue()) {
                        Sheet srcSheet = srcWb.getSheet(task.cfg().getSheetName());
                        if (srcSheet == null) continue;
                        ExcelUtil.copyHeaderToWorkbook(srcSheet, tgtWb,
                                task.cfg().getSheetName(), task.cfg().getHeaderIndex() - 1);
                        Sheet tgtSheet = tgtWb.getSheet(task.cfg().getSheetName());
                        Row templateRow = srcSheet.getRow(task.cfg().getHeaderIndex());
                        ExcelUtil.writeDataRowsToSheet(tgtSheet, tgtWb, templateRow,
                                task.cfg().getHeaderIndex(), task.rows());
                    }
                    try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                        tgtWb.write(fos);
                    }
                }

                writeDone++;
                progress.accept(0.35 + 0.5 * writeDone / Math.max(1, totalFiles), "Writing: " + baseName);
            }

            // Phase 3: copyAll sheets — reuse already-open source workbook
            if (!copyAllConfigs.isEmpty()) {
                File[] outputFiles = config.outputDir.toFile().listFiles(
                        (dir, name) -> name.endsWith(".xlsx") && !name.endsWith("_metadata.xlsx"));

                if (outputFiles != null) {
                    for (int i = 0; i < outputFiles.length; i++) {
                        File targetFile = outputFiles[i];
                        try (FileInputStream tgtFis = new FileInputStream(targetFile);
                             Workbook tgtWb = WorkbookFactory.create(tgtFis)) {
                            for (ComplexSplitConfigEntity copyConfig : copyAllConfigs) {
                                Sheet srcSheet = srcWb.getSheet(copyConfig.getSheetName());
                                if (srcSheet != null && tgtWb.getSheet(copyConfig.getSheetName()) == null) {
                                    ExcelUtil.copySheetToWorkbook(srcSheet, tgtWb);
                                }
                            }
                            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                                tgtWb.write(fos);
                            }
                        }
                        progress.accept(0.85 + 0.15 * (i + 1) / Math.max(1, outputFiles.length),
                                "Copying sheets: " + targetFile.getName());
                    }
                }
            }
        }

        List<Path> outputPaths = Arrays.stream(
                        Objects.requireNonNull(config.outputDir.toFile().listFiles(
                                (dir, name) -> name.endsWith(".xlsx") && !name.endsWith("_metadata.xlsx"))))
                .map(File::toPath)
                .sorted(java.util.Comparator.comparing(Path::getFileName))
                .collect(Collectors.toList());

        progress.accept(1.0, "Done");
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
                row.add(rowMap.getOrDefault(key, ""));
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
