/*
 * Created by JFormDesigner on Wed Mar 04 22:42:59 CST 2026
 */

package fan.summer.kitpage.excel.second;

import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;


/**
 * Configuration View Dialog.
 * Displays saved complex split configurations in a table format.
 * Allows users to view and edit configurations by double-clicking rows.
 *
 * @author phoebej
 */
public class ConfigView extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(ConfigView.class);

    private String taskId;

    /**
     * Constructor - Creates the configuration view dialog
     *
     * @param panel Parent panel for dialog positioning
     */
    public ConfigView(JPanel panel, String taskId) {
        super(SwingUtilities.getWindowAncestor(panel));
        initComponents();
        this.taskId = taskId;
    }

    /**
     * Sets the table model with configuration data
     * Columns: FileName, SheetName, HeaderIndex, SplitBYColumnIndex
     * Only columns with index > 4 are editable
     *
     * @param rowDatas List of row data arrays containing configuration values
     * @return This ConfigView instance for method chaining
     */
    public ConfigView setTableModel(List<Object[]> rowDatas) {
        String[] columns = {"FileName", "SheetName", "HeaderIndex", "SplitBYColumnIndex"};
        // Override isCellEditable to make columns beyond index 4 editable
        // First few columns (file info) remain non-editable for data integrity
        DefaultTableModel defaultTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column > 4;
            }
        };
        for (Object[] row : rowDatas) {
            defaultTableModel.addRow(row);
        }
        this.configInfo.setModel(defaultTableModel);
        return this;
    }

    /**
     * Handles OK button action - closes the dialog
     *
     * @param e ActionEvent triggered by button click
     */
    private void okBtAction(ActionEvent e) {
        this.setVisible(false);
    }

    /**
     * Handles mouse click events on the configuration table.
     * Opens editor dialog when user double-clicks on a row.
     *
     * @param e MouseEvent containing click coordinates and information
     */
    private void configInfoMouseClicked(MouseEvent e) {
        // Check for double-click (>=2 to handle any multi-click scenario)
        if (e.getClickCount() >= 2) {
            // Convert click point to row index
            int row = configInfo.rowAtPoint(e.getPoint());
            if (row >= 0) {
                // Select the clicked row for visual feedback
                configInfo.setRowSelectionInterval(row, row);
                log.debug("Opening editor for row {}", row);
                // Open editor dialog for this row, passing table reference and row index
                new ConfigEditorView(contentPanel, this, configInfo, row, taskId).setVisible(true);
            }
        }
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        dialogPane = new JPanel();
        contentPanel = new JPanel();
        scrollPane1 = new JScrollPane();
        configInfo = new JTable();
        buttonBar = new JPanel();
        okButton = new JButton();

        //======== this ========
        var contentPane = getContentPane();
        contentPane.setLayout(new BorderLayout());

        //======== dialogPane ========
        {
            dialogPane.setLayout(new BorderLayout());

            //======== contentPanel ========
            {
                contentPanel.setLayout(new MigLayout(
                    "fill,insets dialog,hidemode 3,align center center",
                    // columns
                    "[fill]" +
                    "[fill]",
                    // rows
                    "[]" +
                    "[]" +
                    "[]"));

                //======== scrollPane1 ========
                {

                    //---- configInfo ----
                    configInfo.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseClicked(MouseEvent e) {
                            configInfoMouseClicked(e);
                        }
                    });
                    scrollPane1.setViewportView(configInfo);
                }
                contentPanel.add(scrollPane1, "cell 0 0");
            }
            dialogPane.add(contentPanel, BorderLayout.CENTER);

            //======== buttonBar ========
            {
                buttonBar.setLayout(new MigLayout(
                    "insets dialog,alignx right",
                    // columns
                    "[button,fill]" +
                    "[button,fill]",
                    // rows
                    null));

                //---- okButton ----
                okButton.setText("OK");
                okButton.addActionListener(e -> okBtAction(e));
                buttonBar.add(okButton, "cell 1 0");
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
    private JScrollPane scrollPane1;
    private JTable configInfo;
    private JPanel buttonBar;
    private JButton okButton;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
