package fan.summer.kitpage.excel.worker;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import fan.summer.database.mapper.excel.ComplexSplitConfigMapper;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.nio.file.Path;
import java.util.List;

/**
 * Worker for setting complex split configuration.
 * Saves Excel file split configuration to database for later use.
 * Executes the database insert operation in a background thread.
 *
 * @author phoebej
 * @version 1.00
 * @date 2026/3/3
 */
public class SetComplexSplitConfigWorker extends SwingWorker<Void, Integer> {
    private static final Logger log = LoggerFactory.getLogger(SetComplexSplitConfigWorker.class);

    private final JPanel fatherPanel;
    private final JProgressBar progressBar;
    private final JButton startBtn;
    private final Path excelFilePath;
    private final String splitTaskId;
    private final String selectedSheetName;
    private final int headerIndexValue;
    private final int columnIndexValue;
    private final int selectedComboIndex;
    // Swing components accessed only on EDT via invokeLater
    private final JComboBox comboBox;
    private final JTextField headerRowIndexField;
    private final JTextField columnRowIndexField;

    /** Flag indicating if an error occurred during execution */
    private boolean isError;

    /**
     * Creates a new SetComplexSplitConfigWorker.
     * Reads Swing component values on the EDT (constructor) to avoid EDT violations in doInBackground.
     *
     * @param fatherPanel     the parent panel for dialog positioning
     * @param progressBar     the progress bar to update during operation
     * @param startBtn        the start button to disable during operation
     * @param excelFilePath   the path to the Excel file being configured
     * @param splitTaskId     the task ID for this configuration
     * @param comboBox        the combo box containing sheet names
     * @param headerRowIndex  the header row index input field
     * @param columnRowIndex  the column row index input field
     */
    public SetComplexSplitConfigWorker(JPanel fatherPanel, JProgressBar progressBar, JButton startBtn, Path excelFilePath, String splitTaskId, JComboBox comboBox, JTextField headerRowIndex, JTextField columnRowIndex) {
        this.fatherPanel = fatherPanel;
        this.progressBar = progressBar;
        this.startBtn = startBtn;
        this.excelFilePath = excelFilePath;
        this.splitTaskId = splitTaskId;
        // Read Swing component values on the EDT (constructor runs on EDT)
        this.comboBox = comboBox;
        this.headerRowIndexField = headerRowIndex;
        this.columnRowIndexField = columnRowIndex;
        Object selectedItem = comboBox.getSelectedItem();
        this.selectedSheetName = selectedItem != null ? selectedItem.toString() : "";
        this.headerIndexValue = Integer.parseInt(headerRowIndex.getText());
        this.columnIndexValue = Integer.parseInt(columnRowIndex.getText());
        this.selectedComboIndex = comboBox.getSelectedIndex();
    }


    /**
     * Performs the database insert operation in background thread.
     * Creates and saves a new configuration entity with the provided parameters.
     *
     * @return null (no return value needed)
     * @throws Exception if database operation fails
     */
    @Override
    protected Void doInBackground() throws Exception {
        isError = false;
        SwingUtilities.invokeLater(() -> startBtn.setEnabled(false));
        log.debug("Setting config for taskId: {}, file: {}", splitTaskId, excelFilePath.getFileName());
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(0);
            progressBar.setStringPainted(true);
        });
        try (SqlSession sqlSession = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = sqlSession.getMapper(ComplexSplitConfigMapper.class);
            ComplexSplitConfigEntity entity = new ComplexSplitConfigEntity();
            entity.setTaskId(this.splitTaskId);
            entity.setFieldName(excelFilePath.getFileName().toString());
            entity.setSheetName(this.selectedSheetName);
            entity.setHeaderIndex(this.headerIndexValue);
            entity.setColumnIndex(this.columnIndexValue);
            mapper.insert(entity);
            sqlSession.commit();
            log.info("Successfully saved config for taskId: {}, file: {}", splitTaskId, excelFilePath.getFileName());
            SwingUtilities.invokeLater(() -> {
                comboBox.removeItemAt(selectedComboIndex);
                headerRowIndexField.setText("");
                columnRowIndexField.setText("");
            });
            int progress = (int) (100 * 100.0 / 100);
            publish(progress);
        } catch (Exception ex) {
            isError = true;
            log.error("Failed to save config for taskId: {}", splitTaskId, ex);
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showConfirmDialog(
                        fatherPanel,
                        "Info: " + ex.getMessage(),
                        "Set Config Error",
                        JOptionPane.DEFAULT_OPTION
                );
            });
        }
        return null;
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
            startBtn.setEnabled(true);
            if (isError) {
                progressBar.setValue(0);
                progressBar.setString("");
            } else {
                progressBar.setString("Set Config Complete");
                log.debug("Set config operation completed successfully");
            }
        } catch (Exception e) {
            log.error("Error in done() callback", e);
            startBtn.setEnabled(true);
        }
    }
}
