package fan.summer.kitpage.excel.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.ss.usermodel.*;

import javax.swing.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SwingWorker for analyzing Excel files in background thread.
 * Extracts sheet names and column headers from Excel files using Apache POI.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/1
 */
public class ExcelAnalysisWorker extends SwingWorker<Map<String, Map<Integer, String>>, Integer> {
    private static final Logger logger = LoggerFactory.getLogger(ExcelAnalysisWorker.class);

    private final ExcelAnalysisCallback callback;
    private final Path filePath;
    private final JProgressBar progressBar;
    private final JButton startBtn;

    /**
     * Constructor - Initialize the Excel analysis plugin.swisskit.hpl.worker
     *
     * @param filePath    the path to the Excel file to analyze
     * @param progressBar the progress bar to update during analysis
     * @param startBtn    the start button to enable/disable during analysis
     * @param callback    the callback interface to notify of success or failure
     */
    public ExcelAnalysisWorker(Path filePath, JProgressBar progressBar, JButton startBtn, ExcelAnalysisCallback callback) {
        this.filePath = filePath;
        this.progressBar = progressBar;
        this.startBtn = startBtn;
        this.callback = callback;
    }

    /**
     * Perform the Excel file analysis in a background thread
     * Retrieves all sheet names and their headers using Apache POI and Apache FESOD
     *
     * @return a map where keys are sheet names and values are lists of header strings
     * @throws Exception if the file cannot be read or parsed
     */
    @Override
    protected Map<String, Map<Integer, String>> doInBackground() throws Exception {
        logger.info("Starting Excel file analysis | file={}", filePath.getFileName());
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(0);
            progressBar.setStringPainted(true);
        });

        Map<String, Map<Integer, String>> result = new LinkedHashMap<>();

        // 1. Get all sheet names
        List<String> sheetNames = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            int totalSheets = workbook.getNumberOfSheets();
            for (int i = 0; i < totalSheets; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();

                Map<Integer, String> headers = new LinkedHashMap<>();
                Row headerRow = sheet.getRow(0); // Get the first row directly

                if (headerRow != null) {
                    for (Cell cell : headerRow) {
                        headers.put(cell.getColumnIndex(), cell.getStringCellValue());
                    }
                }
                result.put(sheetName, headers);
                int progress = (int) ((i + 1) * 100.0 / totalSheets);
                publish(progress);
            }
            logger.info("Excel analysis completed | file={}, sheets={}", filePath.getFileName(), totalSheets);
        } catch (Exception e) {
            logger.error("Excel analysis failed | file={}", filePath.getFileName(), e);
            throw e;
        }

        return result;
    }

    /**
     * Process intermediate results from doInBackground in the EDT thread
     * Updates the progress bar with the latest progress value
     *
     * @param chunks a list of progress values published by doInBackground
     */
    @Override
    protected void process(List<Integer> chunks) {
        // EDT thread: Update progress bar
        int latestProgress = chunks.get(chunks.size() - 1);
        progressBar.setValue(latestProgress);
        progressBar.setString("Parsing... " + latestProgress + "%");
    }

    /**
     * Called when the background task is complete in the EDT thread
     * Updates the UI with success or failure message and invokes the appropriate callback
     */
    @Override
    protected void done() {
        // EDT thread: Task completion cleanup
        try {
            Map<String, Map<Integer, String>> result = get(); // Get return value from doInBackground
            progressBar.setString("Parsing completed! Total " + result.size() + " sheets");
            startBtn.setEnabled(true);
            // Post-process result, such as displaying in a table...
            callback.onSuccess(result);
        } catch (Exception e) {
            progressBar.setString("Parsing failed: " + e.getMessage());
            startBtn.setEnabled(false);
            callback.onFailure(e);
        }
    }
}