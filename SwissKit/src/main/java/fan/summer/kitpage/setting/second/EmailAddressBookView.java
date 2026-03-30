/*
 * Created by JFormDesigner on Sat Mar 07 15:01:31 CST 2026
 */

package fan.summer.kitpage.setting.second;

import fan.summer.database.entity.setting.email.EmailAddressBookEntity;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Dialog window for displaying and managing email address book entries.
 * Shows a table with email address, nickname, and tags columns.
 * Allows adding new addresses via the AddNewAddress button.
 *
 * @author phoebej
 */
public class EmailAddressBookView extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(EmailAddressBookView.class);

    private JPanel pannel;
    private List<EmailAddressBookEntity> entities;

    public EmailAddressBookView(JPanel panel) {
        super(SwingUtilities.getWindowAncestor(panel));
        this.pannel = panel;
        initComponents();
    }

    /**
     * Initializes the table with the provided row data and entities.
     * Creates a non-editable table with Id, Address, NickName, and Tags columns.
     * Double-clicking a row opens the edit dialog.
     *
     * @param rowData list of row data arrays containing [id, address, nickname, tags]
     * @param entities list of email address book entities corresponding to each row
     * @return this EmailAddressBookView instance for method chaining
     */
    public EmailAddressBookView initTable(List<Object[]> rowData, List<EmailAddressBookEntity> entities) {
        this.entities = entities;
        String[] columns = {"Id", "Address", "NickName", "Tags"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column > 99;
            }
        };
        for (Object[] row : rowData) {
            model.addRow(row);
        }
        addressInfoTable.setModel(model);
        addressInfoTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = addressInfoTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < entities.size()) {
                        EmailAddressBookEntity entity = entities.get(row);
                        log.debug("Double-clicked row {}, opening edit view for: {}", row, entity.getEmailAddress());
                        new AddAddressView(pannel, EmailAddressBookView.this, entity).initTagsCompBox().setVisible(true);
                    }
                }
            }
        });
        return this;
    }

    /**
     * Handles add address button action - opens the add address dialog.
     *
     * @param e the action event
     */
    private void addAddressBtAction(ActionEvent e) {
        log.debug("Opening add address view");
        new AddAddressView(pannel, this).initTagsCompBox().setVisible(true);

    }

    /**
     * Handles close button action - hides the dialog.
     *
     * @param e the action event
     */
    private void closeBtAction(ActionEvent e) {
        log.debug("Closing address book view");
        this.setVisible(false);
    }

    /**
     * Handles modify tags button action - opens the tag management view.
     *
     * @param e the action event
     */
    private void modifyTagsBtAction(ActionEvent e) {
        log.debug("Opening tags view");
        new EmailTagsView(pannel).openTagView();

    }


    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        scrollPane1 = new JScrollPane();
        addressInfoTable = new JTable();
        panel1 = new JPanel();
        addAddressBt = new JButton();
        modifyTagsBt = new JButton();
        closeBt = new JButton();

        //======== this ========
        var contentPane = getContentPane();
        contentPane.setLayout(new MigLayout(
            "fill,hidemode 3",
            // columns
            "[fill]" +
            "[fill]",
            // rows
            "[]" +
            "[]" +
            "[]"));

        //======== scrollPane1 ========
        {
            scrollPane1.setViewportView(addressInfoTable);
        }
        contentPane.add(scrollPane1, "cell 0 0 2 1");

        //======== panel1 ========
        {
            panel1.setLayout(new MigLayout(
                "fill,hidemode 3",
                // columns
                "[fill]" +
                "[fill]",
                // rows
                "[]" +
                "[]" +
                "[]" +
                "[]" +
                "[]"));

            //---- addAddressBt ----
            addAddressBt.setText("AddNewAddress");
            addAddressBt.addActionListener(e -> addAddressBtAction(e));
            panel1.add(addAddressBt, "cell 0 0 2 1");

            //---- modifyTagsBt ----
            modifyTagsBt.setText("ModifyTags");
            modifyTagsBt.addActionListener(e -> modifyTagsBtAction(e));
            panel1.add(modifyTagsBt, "cell 0 1 2 1");

            //---- closeBt ----
            closeBt.setText("Close");
            closeBt.addActionListener(e -> closeBtAction(e));
            panel1.add(closeBt, "cell 0 2 2 1");
        }
        contentPane.add(panel1, "cell 0 1 2 2");
        pack();
        setLocationRelativeTo(getOwner());
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JScrollPane scrollPane1;
    private JTable addressInfoTable;
    private JPanel panel1;
    private JButton addAddressBt;
    private JButton modifyTagsBt;
    private JButton closeBt;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
