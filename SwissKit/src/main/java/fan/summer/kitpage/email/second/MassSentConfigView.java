/*
 * Created by JFormDesigner on Wed Mar 11 11:06:02 CST 2026
 */

package fan.summer.kitpage.email.second;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.email.EmailMassSentConfigEntity;
import fan.summer.database.entity.setting.email.EmailTagEntity;
import fan.summer.database.mapper.email.EmailMassSentConfigMapper;
import fan.summer.database.mapper.setting.email.EmailTagMapper;
import fan.summer.kitpage.setting.dto.TagComBoxItemDto;
import net.miginfocom.swing.MigLayout;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.io.File;
import java.util.List;

/**
 * Mass sent config view dialog for configuring email mass sending settings.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/11
 */
public class MassSentConfigView extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(MassSentConfigView.class);

    // Current task ID for associating with configuration
    private final String taskId;

    /**
     * Creates the mass sent config dialog.
     *
     * @param panel  Parent panel for getting the top-level window
     * @param taskId Task ID for associating with configuration
     */
    public MassSentConfigView(JPanel panel, String taskId) {
        super(SwingUtilities.getWindowAncestor(panel));
        this.taskId = taskId;
        initComponents();
        loadTags();
        loadConfig();
    }

    /**
     * Validates form inputs.
     * Checks if required fields are filled and if attachment folder is selected when required.
     *
     * @return true if validation passes, false otherwise
     */
    private boolean validateForm() {
        // Validate To tag is required
        if (toTagcomboBox.getSelectedItem() == null || toTagcomboBox.getSelectedItem().toString().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a To tag", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        // Validate attachment folder is selected when checkbox is enabled
        if (attachmentByTagCheckBox.isSelected()) {
            String attPath = attPathField.getText();
            if (attPath == null || attPath.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please select an attachment folder", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return false;
            }
        }

        return true;
    }

    /**
     * Save button action event. Saves configuration to database in background.
     */
    private void saveBtAction(ActionEvent e) {
        if (!validateForm()) {
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    EmailMassSentConfigMapper mapper = session.getMapper(EmailMassSentConfigMapper.class);

                    // Build config entity
                    EmailMassSentConfigEntity config = new EmailMassSentConfigEntity();
                    config.setTaskId(taskId);
                    TagComBoxItemDto toSelectedItem = (TagComBoxItemDto) toTagcomboBox.getSelectedItem();
                    if (toSelectedItem != null) {
                        config.setToTag(toSelectedItem.getId().toString());
                    }
                    if (ccTagComboBox.getSelectedItem() != null) {
                        TagComBoxItemDto selectedItem = (TagComBoxItemDto) ccTagComboBox.getSelectedItem();
                        config.setCcTag(selectedItem.getId().toString());
                    } else {
                        config.setCcTag(null);
                    }
                    config.setSentAtt(attachmentByTagCheckBox.isSelected());
                    config.setAttFolderPath(attachmentByTagCheckBox.isSelected() ? attPathField.getText() : null);

                    // Insert or update config
                    mapper.upsert(config);
                    session.commit();
                    log.info("Mass sent config saved for taskId: {}", taskId);
                }

                return null;
            }

            @Override
            protected void done() {
                JOptionPane.showMessageDialog(MassSentConfigView.this, "Configuration saved successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
                dispose();
            }
        }.execute();
    }

    /**
     * Close button action event. Closes the dialog.
     */
    private void closedBtAction(ActionEvent e) {
        dispose();
    }

    /**
     * Attachment checkbox state changed event. Controls enabled state of attachment path field and selection button.
     */
    private void attachmentByTagCheckBoxItemStateChanged(ItemEvent e) {
        choiceAttFolderBt.setEnabled(attachmentByTagCheckBox.isSelected());
        if (!attachmentByTagCheckBox.isSelected()) {
            attPathField.setText("");
        }
    }

    /**
     * Select attachment folder button action event. Opens folder selection dialog.
     */
    private void choiceAttFolderBtAction(ActionEvent e) {
        JFileChooser folderChooser = new JFileChooser();
        folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        folderChooser.setDialogTitle("Select Attachment Folder");

        int result = folderChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFolder = folderChooser.getSelectedFile();
            attPathField.setText(selectedFolder.getAbsolutePath());
            log.debug("Attachment folder selected: {}", selectedFolder.getAbsolutePath());
        }
    }

    /**
     * Loads tags from database into combo boxes.
     */
    private void loadTags() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailTagMapper tagMapper = session.getMapper(EmailTagMapper.class);
            List<EmailTagEntity> tags = tagMapper.selectAll();

            toTagcomboBox.removeAllItems();
            ccTagComboBox.removeAllItems();

            for (EmailTagEntity tag : tags) {
                TagComBoxItemDto tagComBoxItemDto = new TagComBoxItemDto(tag.getId(), tag.getTag());
                toTagcomboBox.addItem(tagComBoxItemDto);
                ccTagComboBox.addItem(tagComBoxItemDto);
            }
            toTagcomboBox.setSelectedIndex(-1);
            ccTagComboBox.setSelectedIndex(-1);
        } catch (Exception ex) {
            log.error("Failed to load tags", ex);
            JOptionPane.showMessageDialog(this, "Failed to load tags: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Loads existing config for current task from database and populates the UI.
     */
    private void loadConfig() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailMassSentConfigMapper mapper = session.getMapper(EmailMassSentConfigMapper.class);
            EmailMassSentConfigEntity config = mapper.selectByTaskId(taskId);

            if (config != null) {
                toTagcomboBox.setSelectedItem(config.getToTag());
                ccTagComboBox.setSelectedItem(config.getCcTag());
                attachmentByTagCheckBox.setSelected(config.isSentAtt());
                attPathField.setText(config.getAttFolderPath() != null ? config.getAttFolderPath() : "");
            }
        } catch (Exception ex) {
            log.error("Failed to load config for taskId: {}", taskId, ex);
        }
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        label1 = new JLabel();
        toTagcomboBox = new JComboBox();
        label2 = new JLabel();
        ccTagComboBox = new JComboBox();
        attachmentByTagCheckBox = new JCheckBox();
        choiceAttFolderBt = new JButton();
        attPathField = new JTextField();
        panel1 = new JPanel();
        saveBt = new JButton();
        closedBt = new JButton();

        //======== this ========
        var contentPane = getContentPane();
        contentPane.setLayout(new MigLayout(
            "hidemode 3",
            // columns
            "[fill]" +
            "[42,fill]" +
            "[90,fill]" +
            "[287,fill]",
            // rows
            "[]" +
            "[]" +
            "[]" +
            "[]" +
            "[]"));

        //---- label1 ----
        label1.setText("To");
        contentPane.add(label1, "cell 1 0");
        contentPane.add(toTagcomboBox, "cell 2 0 2 1");

        //---- label2 ----
        label2.setText("Cc");
        contentPane.add(label2, "cell 1 1");
        contentPane.add(ccTagComboBox, "cell 2 1 2 1");

        //---- attachmentByTagCheckBox ----
        attachmentByTagCheckBox.setText("AddAttachmentByTag");
        attachmentByTagCheckBox.addItemListener(e -> attachmentByTagCheckBoxItemStateChanged(e));
        contentPane.add(attachmentByTagCheckBox, "cell 1 2 2 1");

        //---- choiceAttFolderBt ----
        choiceAttFolderBt.setText("ChoiceAttachmentFolder");
        choiceAttFolderBt.setEnabled(false);
        choiceAttFolderBt.addActionListener(e -> choiceAttFolderBtAction(e));
        contentPane.add(choiceAttFolderBt, "cell 1 3 2 1");

        //---- attPathField ----
        attPathField.setEditable(false);
        contentPane.add(attPathField, "cell 3 3");

        //======== panel1 ========
        {
            panel1.setLayout(new MigLayout(
                "fillx,hidemode 3,align center center",
                // columns
                "[492,fill]",
                // rows
                "[]" +
                "[]" +
                "[]"));

            //---- saveBt ----
            saveBt.setText("Save");
            saveBt.addActionListener(e -> saveBtAction(e));
            panel1.add(saveBt, "cell 0 0");

            //---- closedBt ----
            closedBt.setText("Closed");
            closedBt.addActionListener(e -> closedBtAction(e));
            panel1.add(closedBt, "cell 0 1");
        }
        contentPane.add(panel1, "cell 0 4 4 1");
        pack();
        setLocationRelativeTo(getOwner());
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JLabel label1;                    // "To" label
    private JComboBox toTagcomboBox;           // To tag combo box
    private JLabel label2;                    // "Cc" label
    private JComboBox ccTagComboBox;           // Cc tag combo box
    private JCheckBox attachmentByTagCheckBox; // Checkbox for adding attachments by tag
    private JButton choiceAttFolderBt;         // Select attachment folder button
    private JTextField attPathField;            // Attachment folder path text field
    private JPanel panel1;                      // Button panel
    private JButton saveBt;                     // Save button
    private JButton closedBt;                   // Close button
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
