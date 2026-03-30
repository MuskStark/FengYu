/*
 * Created by JFormDesigner on Wed Mar 18 15:33:05 CST 2026
 */

package fan.summer.kitpage.email.second;

import fan.summer.utils.ui.TableUtil;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * Dialog for viewing email sent logs.
 * Displays a table with email sending history including recipients, content, and status.
 *
 * @author summer
 */
public class ViewEmailSentLogView extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(ViewEmailSentLogView.class);

    /** Column names for the email sent log table */
    private final String[] columns = {"ID", "Subject", "To", "Cc", "Bcc", "Content", "Attachment", "Send Time", "Success"};

    /**
     * Creates a new ViewEmailSentLogView dialog.
     *
     * @param panel the parent panel used to find the parent window
     */
    public ViewEmailSentLogView(JPanel panel) {
        super(SwingUtilities.getWindowAncestor(panel));
        log.debug("Initializing ViewEmailSentLogView dialog");
        initComponents();
    }

    /**
     * Updates the table with email sent log data.
     *
     * @param rowData list of row data arrays to display in the table
     * @return this dialog instance for method chaining
     */
    public ViewEmailSentLogView updateTable(List<Object[]> rowData) {
        log.debug("Updating table with {} rows of email sent log data", rowData.size());
        TableUtil.initTable(shentLog, columns, rowData, 99);
        return this;
    }

    /**
     * Handles the OK button click action.
     * Closes the dialog.
     *
     * @param e the action event
     */
    private void okBtAction(ActionEvent e) {
        log.debug("OK button clicked, closing dialog");
        dispose();
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        dialogPane = new JPanel();
        contentPanel = new JPanel();
        scrollPane1 = new JScrollPane();
        shentLog = new JTable();
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
                    "[569,fill]",
                    // rows
                    "[302]" +
                    "[]" +
                    "[]" +
                    "[]"));

                //======== scrollPane1 ========
                {
                    scrollPane1.setViewportView(shentLog);
                }
                contentPanel.add(scrollPane1, "cell 0 0,aligny center,grow 100 0");
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

                //---- okButton ----
                okButton.setText("OK");
                okButton.addActionListener(e -> okBtAction(e));
                buttonBar.add(okButton, "cell 0 0");
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
    private JTable shentLog;
    private JPanel buttonBar;
    private JButton okButton;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
