package fan.summer.kitpage.excel.worker;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import fan.summer.database.mapper.excel.ComplexSplitConfigMapper;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;

/**
 * Worker for clearing complex split configuration from database.
 * Executes the deletion operation in a background thread to avoid blocking the EDT.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/4
 */
public class ClearComplexSplitConfigWorker extends SwingWorker<Void, Integer> {
    private static final Logger log = LoggerFactory.getLogger(ClearComplexSplitConfigWorker.class);

    private final JPanel fatherPanel;
    private final String taskId;
    private final JButton button;
    private final JProgressBar progressBar;

    /** Flag indicating if an error occurred during execution */
    private boolean isError;

    /**
     * Creates a new ClearComplexSplitConfigWorker.
     *
     * @param fatherPanel  the parent panel for dialog positioning
     * @param taskId       the task ID whose configurations should be deleted
     * @param button       the button to disable during operation
     * @param progressBar  the progress bar to update during operation
     */
    public ClearComplexSplitConfigWorker(JPanel fatherPanel, String taskId, JButton button, JProgressBar progressBar) {
        this.fatherPanel = fatherPanel;
        this.taskId = taskId;
        this.button = button;
        this.progressBar = progressBar;
    }

    /**
     * Performs the database deletion operation in background thread.
     * Deletes all configuration records matching the task ID.
     *
     * @return null (no return value needed)
     * @throws Exception if database operation fails
     */
    @Override
    protected Void doInBackground() throws Exception {
        isError = false;
        log.debug("Starting to clear config for taskId: {}", taskId);
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(0);
            progressBar.setStringPainted(true);
        });
        SwingUtilities.invokeLater(() -> button.setEnabled(false));
        try(SqlSession session = DatabaseInit.getSqlSession()){
            ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
            mapper.deleteAllByTaskId(taskId);
            session.commit();
            log.info("Successfully cleared config for taskId: {}", taskId);
            int process = 100;
            publish(process);
        }catch (Exception e){
            isError = true;
            log.error("Failed to clear config for taskId: {}", taskId, e);
            JOptionPane.showConfirmDialog(
                    fatherPanel,
                    "Info: " + e.getMessage(),
                    "Delete Config Error",
                    JOptionPane.DEFAULT_OPTION
            );
        }

        return null;
    }

    /**
     * Processes progress updates from doInBackground in the EDT.
     * Updates the progress bar with the latest progress value.
     *
     * @param chunks a list of progress values published by doInBackground
     */
    @Override
    protected void process(List<Integer> chunks) {
        int latestProgress = chunks.get(chunks.size() - 1);
        progressBar.setValue(latestProgress);
        progressBar.setString("Deleting... " + latestProgress + "%");
    }

    /**
     * Called when the background task is complete in the EDT.
     * Updates the UI with success or failure message and re-enables the button.
     */
    @Override
    protected void done() {
        try {
            button.setEnabled(true);
            if (isError) {
                progressBar.setValue(0);
                progressBar.setString("");
            } else {
                progressBar.setString("Delete Config Complete");
                log.debug("Clear config operation completed successfully");
            }
        } catch (Exception e) {
            log.error("Error in done() callback", e);
            button.setEnabled(true);
        }
    }


}
