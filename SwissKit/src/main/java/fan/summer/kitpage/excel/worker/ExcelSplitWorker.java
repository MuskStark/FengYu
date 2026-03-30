package fan.summer.kitpage.excel.worker;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import fan.summer.database.mapper.excel.ComplexSplitConfigMapper;
import fan.summer.kitpage.excel.listener.NoModelDataListener;
import fan.summer.utils.ExcelUtil;
import fan.summer.utils.FileNameUtil;
import org.apache.fesod.sheet.ExcelReader;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.read.metadata.ReadSheet;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Worker for splitting Excel files into separate files.
 * Runs in background thread to avoid blocking UI.
 *
 * @author phoebej
 * @version 1.00
 * @date 2026/2/27
 */
public class ExcelSplitWorker extends SwingWorker<Void, Integer> {
    private static final Logger logger = LoggerFactory.getLogger(ExcelSplitWorker.class);

    private final Path outputPath;
    private final Path orgFilePath;
    private final JProgressBar progressBar;
    private final JButton button;
    // Configuration map for split options
    private Map<String, Object> config = new HashMap<>();

    /**
     * Constructor for ExcelSplitWorker.
     *
     * @param outputPath  the directory to save split files
     * @param orgFilePath the original Excel file path
     * @param progressBar the progress bar to update
     * @param button      the button to disable during processing
     */
    public ExcelSplitWorker(Path outputPath, Path orgFilePath, JProgressBar progressBar, JButton button) {
        this.outputPath = outputPath;
        this.orgFilePath = orgFilePath;
        this.progressBar = progressBar;
        this.button = button;
    }

    /**
     * Sets the split model to split by selected sheet names.
     *
     * @param sheetNames set of sheet names to split into separate files
     * @return this plugin.swisskit.hpl.worker for method chaining
     */
    public ExcelSplitWorker setSplitSheetModel(Set<String> sheetNames) {
        config.clear();
        config.put("model", "SSM");
        config.put("sheetNames", sheetNames);
        return this;
    }

    /**
     * Sets the split model to split by column values.
     * Each unique value in the specified column will create a separate file.
     *
     * @param sheetName  the name of the sheet to split
     * @param columnName the name of the column to group by
     * @return this plugin.swisskit.hpl.worker for method chaining
     */
    public ExcelSplitWorker setSplitColumnModel(String sheetName, String columnName) {
        config.clear();
        config.put("model", "SCM");
        config.put("sheetName", sheetName);
        config.put("columnName", columnName);
        return this;
    }

    public ExcelSplitWorker setComplexSplitModel(String taskId) {
        config.clear();
        config.put("model", "SCPM");
        config.put("taskId", taskId);
        return this;
    }

    /**
     * Sets the Excel file analysis result map containing sheet names and column headers.
     *
     * @param excelFileAnalysisResultMap map of sheet names to column header maps
     * @return this plugin.swisskit.hpl.worker for method chaining
     */
    public ExcelSplitWorker setExcelFileAnalysisResultMap(Map<String, Map<Integer, String>> excelFileAnalysisResultMap) {
        if (configValidation()) {
            config.put("excelFileAnalysisResultMap", excelFileAnalysisResultMap);
        }
        return this;
    }


    @Override
    protected Void doInBackground() throws Exception {
        logger.info("Excel split started | file={}", orgFilePath.getFileName());
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(0);
            progressBar.setStringPainted(true);
            progressBar.setString("Splitting... 0%");
            button.setEnabled(false);
        });

        if (config.isEmpty()) {
            logger.error("Excel split failed: no split mode configured");
            SwingUtilities.invokeLater(() -> {
                progressBar.setString("Please select split mode first");
            });
            throw new RuntimeException("Need Set Split Model First!");
        } else {
            logger.debug("Excel split mode: {}", config.get("model"));
            switch ((String) config.get("model")) {
                case "SSM":
                    doSplitBySheet((Set<String>) config.get("sheetNames"));
                    return null;
                case "SCM":
                    doSplitByColumn((String) config.get("sheetName"), (String) config.get("columnName"));
                    return null;
                case "SCPM":
                    doComplexSplit();
                    return null;
                default:
                    return null;
            }
        }

    }

    @Override
    protected void done() {
        // EDT thread: Task completion
        try {
            get(); // Check for exceptions
            progressBar.setValue(100);
            progressBar.setString("Splitting completed!");
            button.setEnabled(true);
        } catch (Exception e) {
            progressBar.setString("Splitting failed: " + e.getMessage());
            button.setEnabled(true);
            // Button will be enabled in doInBackground when error occurs
        }
    }


    /**
     * Processes progress updates published by doInBackground.
     * Updates the progress bar in the EDT thread.
     *
     * @param chunks list of progress values
     */
    @Override
    protected void process(List<Integer> chunks) {
        if (!chunks.isEmpty()) {
            int latestProgress = chunks.get(chunks.size() - 1);
            progressBar.setValue(latestProgress);
            progressBar.setString("Splitting... " + latestProgress + "%");
        }
    }

    /*
      ExcelFile Split Function
     */

    /**
     * Splits Excel file by selected sheet names.
     * Each selected sheet will be saved to a separate file.
     *
     * @param sheetNames set of sheet names to split
     */
    private void doSplitBySheet(Set<String> sheetNames) {
        logger.debug("Starting split by sheet | sheets={}, total={}", sheetNames.size(), sheetNames);
        NoModelDataListener noModelDataListener = new NoModelDataListener();
        Map<String, Map<Integer, String>> excelFileAnalysisResultMap = (Map<String, Map<Integer, String>>) config.get("excelFileAnalysisResultMap");
        int totalSheets = sheetNames.size();
        int currentSheet = 0;

        try (ExcelReader excelReader = FesodSheet.read(orgFilePath.toFile()).build()) {
            for (String sheetName : sheetNames) {
                ReadSheet sheet2 = FesodSheet.readSheet(sheetName).registerReadListener(noModelDataListener).build();
                excelReader.read(sheet2);
                List<Map<Integer, Object>> cachedDataList = noModelDataListener.getCachedDataList();
                Map<Integer, String> headerMap = excelFileAnalysisResultMap.get(sheetName);
                List<List<String>> headers = buildHeaders(headerMap);
                List<List<Object>> rows = buildRows(headerMap, cachedDataList);
                Path resolve = outputPath.resolve(orgFilePath.getFileName() + "_" + sheetName + ".xlsx");
                FesodSheet.write(resolve.toFile())
                        .sheet(sheetName)
                        .head(headers)
                        .doWrite(rows);
                noModelDataListener.clear();
                currentSheet++;
                publish(currentSheet * 100 / totalSheets);
            }
            logger.info("Split by sheet completed | file={}, sheets={}", orgFilePath.getFileName(), currentSheet);
        } catch (Exception e) {
            logger.error("Split by sheet failed | file={}", orgFilePath.getFileName(), e);
            throw e;
        }
    }


    /**
     * Splits Excel file by column values.
     * Groups rows based on unique values in the specified column and writes each group to a separate file.
     *
     * @param sheetName  the name of the sheet to split
     * @param columnName the name of the column to group by
     */
    private void doSplitByColumn(String sheetName, String columnName) {
        logger.debug("Starting split by column | sheet={}, column={}", sheetName, columnName);
        NoModelDataListener noModelDataListener = new NoModelDataListener();
        Map<String, Map<Integer, String>> excelFileAnalysisResultMap = (Map<String, Map<Integer, String>>) config.get("excelFileAnalysisResultMap");
        Map<Integer, String> headers = excelFileAnalysisResultMap.get(sheetName);
        // Get target column index
        AtomicInteger targetColumnIndex = new AtomicInteger(-1);
        headers.forEach((k, v) -> {
            if (v.equals(columnName)) {
                targetColumnIndex.set(k);
            }
        });
        // Read Excel By sheetName
        try (ExcelReader excelReader = FesodSheet.read(orgFilePath.toFile()).build()) {
            ReadSheet sheet = FesodSheet.readSheet(sheetName).registerReadListener(noModelDataListener).build();
            excelReader.read(sheet);
            List<Map<Integer, Object>> cachedDataList = noModelDataListener.getCachedDataList();
            // GroupBy target index by value
            Map<Object, List<Map<Integer, Object>>> group = cachedDataList.stream().collect(Collectors.groupingBy(row -> {
                Object val = row.getOrDefault(targetColumnIndex.get(), null);
                return ExcelUtil.normalizeOrInvalid(val);
            }));
            int total = group.keySet().size();
            AtomicInteger current = new AtomicInteger(0);
            group.forEach((k, v) -> {
                List<List<Object>> rows = buildRows(headers, v);
                Path resolve = outputPath.resolve(FileNameUtil.getFileName(orgFilePath.getFileName().toString()) + "_" + k.toString() + ".xlsx");
                FesodSheet.write(resolve.toFile())
                        .sheet(sheetName)
                        .head(buildHeaders(headers))
                        .doWrite(rows);
                current.set(current.get() + 1);
                publish(current.get() * 100 / total);
            });
            noModelDataListener.clear();
            logger.info("Split by column completed | file={}, groups={}", orgFilePath.getFileName(), total);
        } catch (Exception e) {
            logger.error("Split by column failed | file={}", orgFilePath.getFileName(), e);
            throw e;
        }
    }

    private void doComplexSplit() {
        logger.debug("Starting complex split | taskId={}", config.get("taskId"));
        try (SqlSession sqlSession = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = sqlSession.getMapper(ComplexSplitConfigMapper.class);
            List<ComplexSplitConfigEntity> splitConfigs = mapper.selectAllByTaskId((String) config.get("taskId"));
            if (splitConfigs == null || splitConfigs.isEmpty()) {
                logger.error("Complex split failed: no config found for taskId={}", config.get("taskId"));
                throw new RuntimeException("Empty Config");
            } else {
                logger.debug("Complex split loaded {} configs", splitConfigs.size());
                for (ComplexSplitConfigEntity splitConfig : splitConfigs) {
                    NoModelDataListener noModelDataListener = new NoModelDataListener();
                    try (ExcelReader excelReader = FesodSheet.read(orgFilePath.toFile()).build()) {
                        ReadSheet sheet = FesodSheet.readSheet(splitConfig.getSheetName()).headRowNumber(splitConfig.getHeaderIndex()).registerReadListener(noModelDataListener).build();
                        excelReader.read(sheet);
                        List<Map<Integer, Object>> cachedDataList = noModelDataListener.getCachedDataList();
                        // GroupBy target index by value
                        Map<Object, List<Map<Integer, Object>>> group = cachedDataList.stream().collect(Collectors.groupingBy(row -> {
                            Object val = row.getOrDefault(splitConfig.getColumnIndex() - 1, null);
                            return ExcelUtil.normalizeOrInvalid(val);
                        }));
                        // write to file
                        group.forEach((k, v) -> {
                            String splitFileName = FileNameUtil.getFileName(orgFilePath.getFileName().toString()) + "_" + k.toString() + ".xlsx";
                            try {
                                ExcelUtil.appendSheet(orgFilePath.toString(), outputPath.resolve(splitFileName).toString(), splitConfig.getSheetName(), splitConfig.getHeaderIndex() - 1);
                                ExcelUtil.appendDataRowsByPoi(orgFilePath.toString(), outputPath.resolve(splitFileName).toString(), splitConfig.getSheetName(), splitConfig.getHeaderIndex(), v);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                        noModelDataListener.clear();
                    }
                }
                logger.info("Complex split completed | file={}, configs={}", orgFilePath.getFileName(), splitConfigs.size());
            }
        } catch (Exception e) {
            logger.error("Complex split failed | file={}", orgFilePath.getFileName(), e);
            throw e;
        }
    }

    /**
     * Converts header map to the format required by FesodSheet for writing.
     *
     * @param headMap map of column index to header name
     * @return list of header lists for Excel writing
     */
    // Convert Map<Integer, String> header to List<List<String>> format required by EasyExcel
    private static List<List<String>> buildHeaders(Map<Integer, String> headMap) {
        List<List<String>> headers = new ArrayList<>();
        // Sort by column index to ensure column order
        new TreeMap<>(headMap).forEach((index, name) -> {
            headers.add(Collections.singletonList(name));
        });
        return headers;
    }

    /**
     * Converts raw data rows to the format required by FesodSheet for writing.
     *
     * @param headMap  map of column index to header name
     * @param dataList list of row data maps
     * @return list of rows in correct column order
     */
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

    private static List<List<Object>> buildRows(List<Map<Integer, Object>> dataList) {
        List<List<Object>> result = new ArrayList<>(dataList.size());
        for (Map<Integer, Object> rowMap : dataList) {
            if (rowMap == null || rowMap.isEmpty()) {
                result.add(new ArrayList<>());
                continue;
            }
            int maxCol = rowMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<Object> row = new ArrayList<>(maxCol + 1);
            for (int col = 0; col <= maxCol; col++) {
                row.add(rowMap.get(col));
            }
            result.add(row);
        }
        return result;
    }

    /**
     * Validates that the configuration has been properly set.
     *
     * @return true if configuration is valid, false otherwise
     */
    private boolean configValidation() {
        try {
            config.get("model");
            return true;
        } catch (Exception e) {
            return false;
        }
    }


}
