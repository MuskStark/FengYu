package fan.summer.kitpage.excel.worker;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import fan.summer.database.mapper.excel.ComplexSplitConfigMapper;
import fan.summer.kitpage.excel.second.ConfigView;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * SwingWorker that loads and displays the complex split configuration for a given task.
 * Executes database query on background thread and updates UI on Event Dispatch Thread.
 *
 * @author phoebej
 * @version 1.00
 * @Date 2026/3/4
 */
public class ShowConfigViewWorker extends SwingWorker<List<Object[]>, Void> {
    private static final Logger logger = LoggerFactory.getLogger(ShowConfigViewWorker.class);

    private final JPanel jpanel;
    private final String taskId;

    /**
     * Constructs a new ShowConfigViewWorker.
     *
     * @param jpanel the parent JPanel for displaying the configuration view
     * @param taskId the unique identifier of the task whose config to display
     */
    public ShowConfigViewWorker(JPanel jpanel, String taskId) {
        this.jpanel = jpanel;
        this.taskId = taskId;
    }

    /**
     * Background task that queries the database for configuration records.
     * Runs on a background thread to avoid blocking the UI.
     *
     * @return list of Object arrays containing [fieldName, sheetName, headerIndex, columnIndex], or null if empty/error
     * @throws Exception if database access fails
     */
    @Override
    protected List<Object[]> doInBackground() throws Exception {
        List<Object[]> rowDatas = new ArrayList<>();
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
            List<ComplexSplitConfigEntity> complexSplitConfigEntities = mapper.selectAllByTaskId(taskId);
            if (complexSplitConfigEntities.isEmpty()) {
                return null;
            } else {
                for (ComplexSplitConfigEntity entity : complexSplitConfigEntities) {
                    rowDatas.add(new Object[]{entity.getFieldName(), entity.getSheetName(), entity.getHeaderIndex(), entity.getColumnIndex()});
                }
            }

        } catch (Exception e) {
            logger.error("Failed to load config for taskId: {}", taskId, e);
            throw new RuntimeException(e);
        }
        return rowDatas;
    }

    /**
     * Called on the Event Dispatch Thread after background processing completes.
     * Displays the configuration view or an error/info message.
     */
    @Override
    protected void done() {
        try {
            List<Object[]> objects = get();
            if (objects != null && !objects.isEmpty()) {
                new ConfigView(jpanel, taskId).setTableModel(objects).setVisible(true);
            } else {
                JOptionPane.showConfirmDialog(
                        jpanel,
                        "Info: Empty Config",
                        "ViewConfig Error",
                        JOptionPane.DEFAULT_OPTION
                );
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error loading config view for taskId: {}", taskId, e);
            JOptionPane.showConfirmDialog(
                    jpanel,
                    "Info: " + e.getMessage(),
                    "ViewConfig Error",
                    JOptionPane.DEFAULT_OPTION
            );
            throw new RuntimeException(e);
        }
    }
}
