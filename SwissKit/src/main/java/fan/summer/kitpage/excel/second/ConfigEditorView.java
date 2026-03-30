/*
 * Created by JFormDesigner on Fri Mar 06 09:21:49 CST 2026
 */

package fan.summer.kitpage.excel.second;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import fan.summer.database.mapper.excel.ComplexSplitConfigMapper;
import net.miginfocom.swing.MigLayout;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for editing complex split configuration details.
 * Allows users to modify header index and column index for Excel file splitting.
 *
 * @author summer
 */
public class ConfigEditorView extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(ConfigEditorView.class);

    private String taskId;
    private JTable table;
    private ConfigView configView;

    /**
     * Creates a new ConfigEditorView dialog.
     * 
     * @param panel the parent panel to determine the window ancestor
     * @param configView the parent config view to update after save
     * @param table the table containing the configuration data
     * @param row the row index in the table to edit
     * @param taskId the task ID for the configuration
     */
    public ConfigEditorView(JPanel panel, ConfigView configView, JTable table, int row, String taskId) {
        super(SwingUtilities.getWindowAncestor(panel));
        initComponents();
        this.taskId = taskId;
        this.table = table;
        this.configView = configView;
        fileNameText.setText(objToString(table.getValueAt(row, 0)));
        sheetNameText.setText(objToString(table.getValueAt(row, 1)));
        headerIndexText.setText(objToString(table.getValueAt(row, 2)));
        columnIndex.setText(objToString(table.getValueAt(row, 3)));
    }

    /**
     * Handles the update button action.
     * Saves the modified configuration to database and refreshes the table view.
     */
    private void updateBtActionListener(ActionEvent e) {
        log.debug("Updating config for taskId: {}, file: {}", taskId, fileNameText.getText());
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
            ComplexSplitConfigEntity complexSplitConfigEntity = new ComplexSplitConfigEntity();
            complexSplitConfigEntity.setTaskId(taskId);
            complexSplitConfigEntity.setFieldName(fileNameText.getText());
            complexSplitConfigEntity.setSheetName(sheetNameText.getText());
            complexSplitConfigEntity.setHeaderIndex(Integer.parseInt(headerIndexText.getText()));
            complexSplitConfigEntity.setColumnIndex(Integer.parseInt(columnIndex.getText()));
            mapper.update(complexSplitConfigEntity);
            session.commit();
            log.info("Successfully updated config for taskId: {}", taskId);
            // update table
            List<ComplexSplitConfigEntity> complexSplitConfigEntities = mapper.selectAllByTaskId(taskId);
            List<Object[]> rowDatas = new ArrayList<>();
            for (ComplexSplitConfigEntity entity : complexSplitConfigEntities) {
                rowDatas.add(new Object[]{entity.getFieldName(), entity.getSheetName(), entity.getHeaderIndex(), entity.getColumnIndex()});
            }
            configView.setTableModel(rowDatas);
            this.setVisible(false);
        } catch (Exception ex) {
            log.error("Failed to update config for taskId: {}", taskId, ex);
        }
    }

    /**
     * Converts an object to string, returning empty string if null.
     */
    private String objToString(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        dialogPane = new JPanel();
        contentPanel = new JPanel();
        fileName = new JLabel();
        fileNameText = new JTextField();
        label2 = new JLabel();
        sheetNameText = new JTextField();
        label3 = new JLabel();
        headerIndexText = new JTextField();
        label4 = new JLabel();
        columnIndex = new JTextField();
        buttonBar = new JPanel();
        updateButton = new JButton();

        //======== this ========
        var contentPane = getContentPane();
        contentPane.setLayout(new BorderLayout());

        //======== dialogPane ========
        {
            dialogPane.setLayout(new BorderLayout());

            //======== contentPanel ========
            {
                contentPanel.setLayout(new MigLayout(
                    "insets dialog,hidemode 3",
                    // columns
                    "[fill]" +
                    "[fill]" +
                    "[fill]" +
                    "[274,fill]",
                    // rows
                    "[]" +
                    "[]" +
                    "[]" +
                    "[]"));

                //---- fileName ----
                fileName.setText("FileName");
                contentPanel.add(fileName, "cell 0 0");

                //---- fileNameText ----
                fileNameText.setEditable(false);
                contentPanel.add(fileNameText, "cell 1 0 3 1");

                //---- label2 ----
                label2.setText("SheetName");
                contentPanel.add(label2, "cell 0 1");

                //---- sheetNameText ----
                sheetNameText.setEditable(false);
                contentPanel.add(sheetNameText, "cell 1 1 3 1");

                //---- label3 ----
                label3.setText("HeaderIndex");
                contentPanel.add(label3, "cell 0 2");
                contentPanel.add(headerIndexText, "cell 1 2 3 1");

                //---- label4 ----
                label4.setText("SplitByColumnIndex");
                contentPanel.add(label4, "cell 0 3");
                contentPanel.add(columnIndex, "cell 1 3 3 1");
            }
            dialogPane.add(contentPanel, BorderLayout.CENTER);

            //======== buttonBar ========
            {
                buttonBar.setLayout(new MigLayout(
                    "insets dialog,alignx right",
                    // columns
                    "[button,fill]",
                    // rows
                    null));

                //---- updateButton ----
                updateButton.setText("Update");
                updateButton.addActionListener(e -> updateBtActionListener(e));
                buttonBar.add(updateButton, "cell 0 0");
            }
            dialogPane.add(buttonBar, BorderLayout.SOUTH);
        }
        contentPane.add(dialogPane, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(getOwner());
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel dialogPane;
    private JPanel contentPanel;
    private JLabel fileName;
    private JTextField fileNameText;
    private JLabel label2;
    private JTextField sheetNameText;
    private JLabel label3;
    private JTextField headerIndexText;
    private JLabel label4;
    private JTextField columnIndex;
    private JPanel buttonBar;
    private JButton updateButton;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
