/*
 * Created by JFormDesigner on Mon Mar 09 22:10:10 CST 2026
 */

package fan.summer.kitpage.setting.second;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.setting.email.EmailTagEntity;
import fan.summer.database.mapper.setting.email.EmailTagMapper;
import fan.summer.ui.components.GradientProgressBar;
import net.miginfocom.swing.MigLayout;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Dialog window for managing email tags.
 * Allows users to view, add, and update email tags stored in the database.
 * Tags can be used to categorize email addresses in the address book.
 *
 * @author phoebej
 */
public class EmailTagsView extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(EmailTagsView.class);

    /** ID of the tag being updated (null for new tag creation) */
    private Long needUpdateId;

    /**
     * Creates a new EmailTagsView dialog.
     *
     * @param panel the parent panel to determine the window ancestor
     */
    public EmailTagsView(JPanel panel) {
        super(SwingUtilities.getWindowAncestor(panel));
        ;
        initComponents();
    }

    /**
     * Opens the tag view and loads all existing tags from database.
     * Displays tags in a table with ID and Tag columns.
     * Uses SwingWorker for background database query.
     */
    public void openTagView() {
        new SwingWorker<List<EmailTagEntity>, Void>() {
            @Override
            protected List<EmailTagEntity> doInBackground() throws Exception {
                log.debug("Loading email tags from database");
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    EmailTagMapper mapper = session.getMapper(EmailTagMapper.class);
                    return mapper.selectAll();
                }
            }

            @Override
            protected void done() {
                List<EmailTagEntity> emailTagEntities = null;
                try {
                    emailTagEntities = get();
                    log.debug("Loaded {} email tags", emailTagEntities.size());
                    List<Object[]> rowData = new ArrayList<>();
                    for (EmailTagEntity emailTagEntity : emailTagEntities) {
                        rowData.add(new Object[]{emailTagEntity.getId(), emailTagEntity.getTag()});
                    }
                    if (!rowData.isEmpty()) {
                        String[] columns = {"ID", "Tag"};
                        DefaultTableModel model = new DefaultTableModel(columns, 0) {
                            @Override
                            public boolean isCellEditable(int row, int column) {
                                return column > 99;
                            }
                        };
                        for (Object[] row : rowData) {
                            model.addRow(row);
                        }
                        tagTable.setModel(model);
                    }
                    if (!EmailTagsView.this.isVisible()) {
                        EmailTagsView.this.setVisible(true); // Show only on first open
                    }

                } catch (InterruptedException ex) {
                    log.error("Interrupted while loading email tags", ex);
                    throw new RuntimeException(ex);
                } catch (ExecutionException ex) {
                    log.error("Failed to load email tags", ex);
                    throw new RuntimeException(ex);
                }

            }
        }.execute();
    }

    /**
     * Handles add/update tag button action.
     * If button text is "AddNewTag", inserts a new tag to database.
     * If button text is "Update", updates the existing tag with needUpdateId.
     *
     * @param e the action event
     */
    private void addTagBtAction(ActionEvent e) {
        if (addTagBt.getText().equals("AddNewTag")) {
            new SwingWorker<Void, Void>() {
                private Exception error;

                @Override
                protected Void doInBackground() throws Exception {
                    SwingUtilities.invokeLater(() -> {
                        progressBar1.setValue(0);
                        progressBar1.setStringPainted(true);
                        progressBar1.setString("Please wait...");
                        progressBar1.setVisible(true);
                    });
                    if (tagField.getText() != null && !tagField.getText().isEmpty()) {
                        log.debug("Inserting new tag: {}", tagField.getText());
                        try (SqlSession session = DatabaseInit.getSqlSession()) {
                            EmailTagMapper mapper = session.getMapper(EmailTagMapper.class);
                            EmailTagEntity emailTagEntity = new EmailTagEntity();
                            emailTagEntity.setTag(tagField.getText());
                            mapper.insert(emailTagEntity);
                            session.commit();
                            log.info("Successfully inserted tag: {}", tagField.getText());
                        } catch (Exception ex) {
                            error = ex;
                            log.error("Failed to insert tag: {}", tagField.getText(), ex);
                            throw ex;
                        }
                    } else {
                        throw new RuntimeException("Tag field is empty!");
                    }

                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        progressBar1.setValue(100);
                        progressBar1.setString("Done");
                        openTagView();
                    } catch (Exception ex) {
                        progressBar1.setString("Error: " + ex.getMessage());
                        JOptionPane.showMessageDialog(EmailTagsView.this,
                                "Failed to add tag: " + ex.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        } else if (addTagBt.getText().equals("Update")) {
            // Update Tags
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    log.debug("Updating tag id={} with value: {}", needUpdateId, tagField.getText());
                    try (SqlSession session = DatabaseInit.getSqlSession()) {
                        EmailTagMapper mapper = session.getMapper(EmailTagMapper.class);
                        EmailTagEntity emailTagEntity = new EmailTagEntity();
                        emailTagEntity.setId(needUpdateId);
                        emailTagEntity.setTag(tagField.getText());
                        mapper.update(emailTagEntity);
                        session.commit();
                        log.info("Successfully updated tag id={}", needUpdateId);
                    }
                    return null;
                }

                @Override
                protected void done() {
                    needUpdateId = null;
                    openTagView();
                    tagField.setText("");
                    addTagBt.setText("AddNewTag");
                }
            }.execute();
        }
    }

    /**
     * Handles close button action - hides the dialog.
     *
     * @param e the action event
     */
    private void closeBtAction(ActionEvent e) {
        this.setVisible(false);
    }

    /**
     * Creates custom UI components for the dialog.
     * Called by JFormDesigner during component initialization.
     */
    private void createUIComponents() {
        progressBar1 = new GradientProgressBar();
    }

    /**
     * Handles mouse click events on the tag table.
     * Double-click on a row enables edit mode for that tag.
     *
     * @param e the mouse event
     */
    private void tagTableMouseClicked(MouseEvent e) {
        if (e.getClickCount() >= 2) {
            // Convert click point to row index
            int row = tagTable.rowAtPoint(e.getPoint());
            if (row >= 0) {
                // Select the clicked row for visual feedback
                tagTable.setRowSelectionInterval(row, row);
                // Open editor dialog for this row, passing table reference and row index
                Object value = tagTable.getValueAt(row, 0);
                if (value != null) {
                    needUpdateId = Long.parseLong(value.toString());
                }
                Object tag = tagTable.getValueAt(row, 1);
                if (needUpdateId != null && tag != null) {
                    addTagBt.setText("Update");
                    tagField.setText(tag.toString());
                    log.debug("Selected tag for update: id={}, tag={}", needUpdateId, tag);
                }
            }
        }
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        createUIComponents();

        dialogPane = new JPanel();
        contentPanel = new JPanel();
        scrollPane1 = new JScrollPane();
        tagTable = new JTable();
        label1 = new JLabel();
        tagField = new JTextField();
        addTagBt = new JButton();
        closeBt = new JButton();

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
                    "[113,fill]" +
                    "[324,fill]" +
                    "[404,fill]",
                    // rows
                    "[]" +
                    "[]" +
                    "[]" +
                    "[]" +
                    "[]"));

                //======== scrollPane1 ========
                {

                    //---- tagTable ----
                    tagTable.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseClicked(MouseEvent e) {
                            tagTableMouseClicked(e);
                        }
                    });
                    scrollPane1.setViewportView(tagTable);
                }
                contentPanel.add(scrollPane1, "cell 1 0 3 1");

                //---- label1 ----
                label1.setText("Tag");
                contentPanel.add(label1, "cell 1 1,alignx center,growx 0");
                contentPanel.add(tagField, "cell 2 1");

                //---- addTagBt ----
                addTagBt.setText("AddNewTag");
                addTagBt.addActionListener(e -> addTagBtAction(e));
                contentPanel.add(addTagBt, "cell 3 1");
                contentPanel.add(progressBar1, "cell 0 2 4 1");

                //---- closeBt ----
                closeBt.setText("Close");
                closeBt.addActionListener(e -> closeBtAction(e));
                contentPanel.add(closeBt, "cell 1 3 3 1");
            }
            dialogPane.add(contentPanel, BorderLayout.CENTER);
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
    private JTable tagTable;
    private JLabel label1;
    private JTextField tagField;
    private JButton addTagBt;
    private JProgressBar progressBar1;
    private JButton closeBt;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
