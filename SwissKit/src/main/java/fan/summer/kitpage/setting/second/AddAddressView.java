/*
 * Created by JFormDesigner on Sun Mar 08 00:03:23 CST 2026
 */

package fan.summer.kitpage.setting.second;

import com.alibaba.fastjson2.JSON;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.setting.email.EmailAddressBookEntity;
import fan.summer.database.entity.setting.email.EmailTagEntity;
import fan.summer.database.mapper.setting.email.EmailAddressBookMapper;
import fan.summer.database.mapper.setting.email.EmailTagMapper;
import fan.summer.kitpage.setting.dto.TagComBoxItemDto;
import fan.summer.kitpage.setting.worker.second.QueryAllEmailInfoCallBack;
import fan.summer.kitpage.setting.worker.second.QueryAllEmailInfoWorker;
import fan.summer.utils.StringUtil;
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
 * Dialog window for adding a new email address entry.
 * Validates email format before saving to database.
 *
 * @author phoebej
 */
public class AddAddressView extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(AddAddressView.class);
    private List<String> tags = new ArrayList<>();
    private List<String> chosedTagName = new ArrayList<>();
    private List<EmailAddressBookEntity> dataBaseInfo;
    private EmailAddressBookView emailAddressBookView;
    private boolean comboBoxReady = false;
    private EmailAddressBookEntity editEntity;

    public AddAddressView(JPanel panel, EmailAddressBookView emailAddressBookView) {
        super(SwingUtilities.getWindowAncestor(panel));
        this.emailAddressBookView = emailAddressBookView;
        this.editEntity = null;
        initComponents();
    }

    public AddAddressView(JPanel panel, EmailAddressBookView emailAddressBookView, EmailAddressBookEntity entity) {
        super(SwingUtilities.getWindowAncestor(panel));
        this.emailAddressBookView = emailAddressBookView;
        this.editEntity = entity;
        initComponents();
        prefillData(entity);
    }

    /**
     * Prefills the form fields with data from an existing entity for editing.
     *
     * @param entity the email address book entity to edit
     */
    private void prefillData(EmailAddressBookEntity entity) {
        addressField.setText(entity.getEmailAddress());
        nicknameField.setText(entity.getNickname());
        try {
            List<String> tagNames = JSON.parseArray(entity.getTags(), String.class);
            if (tagNames != null) {
                tagsField.setText(JSON.toJSONString(tagNames));
                chosedTagName.addAll(tagNames);
            }
        } catch (Exception e) {
            log.debug("No tags to prefill", e);
        }
    }

    public AddAddressView initTagsCompBox() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                comboBoxReady = false;
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    EmailTagMapper mapper = session.getMapper(EmailTagMapper.class);
                    List<EmailTagEntity> emailTagEntities = mapper.selectAll();
                    comboBox1.removeAllItems();
                    if (emailTagEntities != null && !emailTagEntities.isEmpty()) {
                        for (EmailTagEntity entity : emailTagEntities) {
                            comboBox1.addItem(new TagComBoxItemDto(entity.getId(), entity.getTag()));
                        }
                        comboBox1.setSelectedIndex(-1);
                        comboBoxReady = true;
                    }
                }
                return null;
            }
        }.execute();
        return this;
    }

    /**
     * Handles the save button action.
     * Validates the email address and saves/updates it to the database.
     *
     * @param e the action event
     */
    private void insertBtAction(ActionEvent e) {
        if (!StringUtil.checkEmail(addressField.getText())) {
            return;
        }
        boolean isEdit = editEntity != null;
        log.debug("{} email address: {}", isEdit ? "Updating" : "Inserting", addressField.getText());
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            EmailAddressBookMapper mapper = session.getMapper(EmailAddressBookMapper.class);
            EmailAddressBookEntity emailAddressBookEntity = new EmailAddressBookEntity();
            if (isEdit) {
                emailAddressBookEntity.setId(editEntity.getId());
            }
            emailAddressBookEntity.setEmailAddress(addressField.getText());
            emailAddressBookEntity.setNickname(nicknameField.getText());
            emailAddressBookEntity.setTags(JSON.toJSONString(tags));
            if (isEdit) {
                mapper.update(emailAddressBookEntity);
                log.info("Successfully updated email address: {}", addressField.getText());
            } else {
                mapper.insert(emailAddressBookEntity);
                log.info("Successfully inserted email address: {}", addressField.getText());
            }
            session.commit();
            new QueryAllEmailInfoWorker(new QueryAllEmailInfoCallBack() {
                @Override
                public void onSuccess(List<EmailAddressBookEntity> emailAddressBookEntities) {
                    dataBaseInfo = emailAddressBookEntities;
                    log.debug("Loaded {} email addresses", emailAddressBookEntities.size());
                    if (dataBaseInfo != null && !dataBaseInfo.isEmpty()) {
                        List<Object[]> rowData = new ArrayList<>();
                        for (EmailAddressBookEntity info : dataBaseInfo) {
                            rowData.add(new Object[]{info.getId(), info.getEmailAddress(), info.getNickname(), info.getTags()});
                        }
                        emailAddressBookView.initTable(rowData, dataBaseInfo);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    log.error("Failed to load email addresses", e);
                    JOptionPane.showMessageDialog(contentPanel,
                            "Can Not Find Any Email Address!",
                            "Warning",
                            JOptionPane.WARNING_MESSAGE);
                }
            }).execute();
            this.setVisible(false);
        } catch (Exception ex) {
            log.error("Failed to {} email address: {}", isEdit ? "update" : "insert", addressField.getText(), ex);
            this.setVisible(false);
            JOptionPane.showMessageDialog(this,
                    (isEdit ? "Can Not Update" : "Can Not Insert") + " Email Address :" + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Handles tag selection from the combo box.
     * Adds the selected tag to the tag list and removes it from the dropdown.
     *
     * @param e the action event triggered by comboBox1
     */
    private void tagChoiceComBoxAction(ActionEvent e) {
        if (comboBoxReady) {
            TagComBoxItemDto tag = (TagComBoxItemDto) comboBox1.getSelectedItem();
            if (tag != null) {
                tags.add(tag.getId().toString());
                chosedTagName.add(tag.getTag());
                tagsField.setText(JSON.toJSONString(chosedTagName));
                comboBox1.removeItem(comboBox1.getSelectedIndex());
            }
        }
    }

    /**
     * Clears all selected tags and resets the tags field.
     *
     * @param e the action event triggered by reset button
     */
    private void resetBtAction(ActionEvent e) {
        tagsField.setText("");
        tags.clear();
        chosedTagName.clear();
    }

    /**
     * Closes the add address dialog window.
     *
     * @param e the action event triggered by closeButton
     */
    private void closeBtAction(ActionEvent e) {
        this.setVisible(false);
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        dialogPane = new JPanel();
        contentPanel = new JPanel();
        label1 = new JLabel();
        addressField = new JTextField();
        label2 = new JLabel();
        nicknameField = new JTextField();
        label3 = new JLabel();
        tagsField = new JTextField();
        button1 = new JButton();
        label4 = new JLabel();
        comboBox1 = new JComboBox();
        buttonBar = new JPanel();
        okButton = new JButton();
        closeButton = new JButton();

        //======== this ========
        var contentPane = getContentPane();
        contentPane.setLayout(new BorderLayout());

        //======== dialogPane ========
        {
            dialogPane.setLayout(new BorderLayout());

            //======== contentPanel ========
            {
                contentPanel.setLayout(new MigLayout(
                    "insets dialog,hidemode 3,align center top",
                    // columns
                    "[80,fill]" +
                    "[217,fill]" +
                    "[fill]",
                    // rows
                    "[]" +
                    "[]" +
                    "[]" +
                    "[]"));

                //---- label1 ----
                label1.setText("Address");
                contentPanel.add(label1, "cell 0 0");
                contentPanel.add(addressField, "cell 1 0");

                //---- label2 ----
                label2.setText("NickName");
                contentPanel.add(label2, "cell 0 1");
                contentPanel.add(nicknameField, "cell 1 1");

                //---- label3 ----
                label3.setText("Tags");
                contentPanel.add(label3, "cell 0 2");

                //---- tagsField ----
                tagsField.setEditable(false);
                contentPanel.add(tagsField, "cell 1 2");

                //---- button1 ----
                button1.setText("Reset");
                button1.addActionListener(e -> resetBtAction(e));
                contentPanel.add(button1, "cell 2 2");

                //---- label4 ----
                label4.setText("ChoiceTag");
                contentPanel.add(label4, "cell 0 3");

                //---- comboBox1 ----
                comboBox1.addActionListener(e -> tagChoiceComBoxAction(e));
                contentPanel.add(comboBox1, "cell 1 3");
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
                okButton.setText("Insert");
                okButton.addActionListener(e -> insertBtAction(e));
                buttonBar.add(okButton, "cell 0 0");

                //---- closeButton ----
                closeButton.setText("Close");
                closeButton.addActionListener(e -> closeBtAction(e));
                buttonBar.add(closeButton, "cell 1 0");
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
    private JLabel label1;
    private JTextField addressField;
    private JLabel label2;
    private JTextField nicknameField;
    private JLabel label3;
    private JTextField tagsField;
    private JButton button1;
    private JLabel label4;
    private JComboBox comboBox1;
    private JPanel buttonBar;
    private JButton okButton;
    private JButton closeButton;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
