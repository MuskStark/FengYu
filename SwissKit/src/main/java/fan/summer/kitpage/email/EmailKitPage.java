package fan.summer.kitpage.email;

import fan.summer.annoattion.SwissKitPage;
import fan.summer.api.KitPage;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.email.EmailMassSentConfigEntity;
import fan.summer.database.entity.email.EmailSentLogEntity;
import fan.summer.database.entity.setting.email.EmailTagEntity;
import fan.summer.database.mapper.email.EmailMassSentConfigMapper;
import fan.summer.database.mapper.email.EmailSentLogMapper;
import fan.summer.database.mapper.setting.email.EmailTagMapper;
import fan.summer.kitpage.email.second.MassSentConfigView;
import fan.summer.kitpage.email.second.ViewEmailSentLogView;
import fan.summer.kitpage.email.worker.EmailSentWorker;
import net.miginfocom.swing.MigLayout;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Email Tool Page
 * Provides email sending functionality with support for single and mass email sending.
 *
 * @author summer
 * @version 1.00
 * @date 2026/2/26
 */
@SwissKitPage(menuName = "Email", menuTooltip = "Email", order = 2)
public class EmailKitPage implements KitPage {
    private static final Logger log = LoggerFactory.getLogger(EmailKitPage.class);
    private String taskId;

    /**
     * Creates a new EmailKitPage and initializes all UI components.
     */
    public EmailKitPage() {
        initComponents();
    }

    /**
     * Returns the main panel for this email page.
     *
     * @return the email JPanel
     */
    @Override
    public JPanel getPanel() {
        return emailPanel;
    }

    /**
     * Handles the mass send checkbox state change.
     * Enables or disables mass send configuration buttons and text fields.
     * When enabled, To and Cc fields are disabled as recipients are loaded from configuration.
     *
     * @param e the action event
     */
    private void massSentCheckBoxActionListener(ActionEvent e) {
        if (massSentCheckBox.isSelected()) {
            taskId = "MassTask-" + UUID.randomUUID();
            log.debug("Mass send mode enabled, taskId:{}", taskId);
            setMassSentConfigBt.setEnabled(true);
            viewSentConfigBt.setEnabled(true);
//            toText.setText("");
//            toText.setEnabled(false);
//            ccText.setText("");
//            ccText.setEnabled(false);
        } else {
            log.debug("Mass send mode disabled");
            taskId = null;
//            toText.setText("");
//            toText.setEnabled(true);
//            ccText.setText("");
//            ccText.setEnabled(true);
            setMassSentConfigBt.setEnabled(false);
            viewSentConfigBt.setEnabled(false);
        }
    }

    /**
     * Opens the mass send configuration dialog for the current task.
     * Allows user to set To/Cc tags and attachment folder for mass email sending.
     *
     * @param e the action event triggered by setMassSentConfigBt
     */
    private void setMassSentConfigBtAction(ActionEvent e) {
        if (taskId != null) {
            new MassSentConfigView(emailPanel, taskId).setVisible(true);
        }
    }

    /**
     * Queries and displays the saved mass send configuration for the current task.
     * Shows To/Cc tags, attachment settings, and folder path in a dialog.
     *
     * @param e the action event triggered by viewSentConfigBt
     */
    private void viewSentConfigBtAction(ActionEvent e) {
        if (taskId == null) {
            return;
        }

        new SwingWorker<Void, Void>() {
            private EmailMassSentConfigEntity config;
            private List<EmailTagEntity> tagList;

            @Override
            protected Void doInBackground() throws Exception {
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    EmailMassSentConfigMapper mapper = session.getMapper(EmailMassSentConfigMapper.class);
                    EmailTagMapper tagMapper = session.getMapper(EmailTagMapper.class);
                    tagList = tagMapper.selectAll();
                    config = mapper.selectByTaskId(taskId);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    StringBuilder message = new StringBuilder();
                    String toTagName = null;
                    String ccTagName = null;
                    if (config != null && tagList != null) {
                    for (EmailTagEntity tag : tagList) {
                        if (config.getToTag() != null && tag.getId().toString().equals(config.getToTag())) {
                            toTagName = tag.getTag();
                        }
                        if (config.getCcTag() != null && tag.getId().toString().equals(config.getCcTag())) {
                            ccTagName = tag.getTag();
                        }
                    }
                    }
                    message.append("<html><body>");
                    message.append("<p><b>Task ID:</b> ").append(taskId).append("</p>");
                    message.append("<p><b>To Tag:</b> ").append(toTagName).append("</p>");
                    message.append("<p><b>Cc Tag:</b> ").append(ccTagName).append("</p>");
                    message.append("<p><b>Send Attachment:</b> ").append(config != null && config.isSentAtt() ? "Yes" : "No").append("</p>");
                    message.append("<p><b>Attachment Folder:</b> ").append(config != null && config.getAttFolderPath() != null ? config.getAttFolderPath() : "N/A").append("</p>");
                    message.append("</body></html>");

                    if (config == null) {
                        JOptionPane.showMessageDialog(emailPanel, "No configuration found for this task", "Info", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(emailPanel, message.toString(), "Mass Sent Configuration", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception ex) {
                    log.error("Failed to load config for taskId: {}", taskId, ex);
                    JOptionPane.showMessageDialog(emailPanel, "Failed to load config: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Triggers the email sending worker with the current subject, body, and configuration.
     * Executes in background to avoid blocking the UI.
     *
     * @param e the action event triggered by sentButton
     */
    private void sentBtAction(ActionEvent e) {
        new EmailSentWorker(subject.getText(), body.getText(), taskId, massSentCheckBox.isSelected(), progressBar1).execute();
    }

    /**
     * Handles the view sent log button action.
     * Loads all email sent logs from database and displays them in a dialog.
     */
    private void viewSentLogBtAction(ActionEvent e) {
        log.debug("View sent log button clicked");
        new SwingWorker<List<EmailSentLogEntity>, Void>() {
            @Override
            protected List<EmailSentLogEntity> doInBackground() throws Exception {
                log.debug("Loading email sent logs from database");
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    EmailSentLogMapper mapper = session.getMapper(EmailSentLogMapper.class);
                    return mapper.selectAll();
                }
            }

            @Override
            protected void done() {
                try {
                    List<EmailSentLogEntity> logs = get();
                    if (logs == null || logs.isEmpty()) {
                        log.info("No email sent logs found");
                        JOptionPane.showMessageDialog(emailPanel,
                                "Empty Email Sent Log",
                                "Info",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        log.info("Loaded {} email sent logs", logs.size());
                        List<Object[]> rowData = new ArrayList<>();
                        for (EmailSentLogEntity logEntry : logs) {
                            rowData.add(new Object[]{logEntry.getId(), logEntry.getSubject(), logEntry.getTo(), logEntry.getCc(), logEntry.getBcc(), logEntry.getContent(), logEntry.getAttachment(), logEntry.getSendTime(), logEntry.isSuccess()});
                        }
                        new ViewEmailSentLogView(emailPanel).updateTable(rowData).setVisible(true);
                    }
                } catch (Exception ex) {
                    log.error("Failed to load email sent logs", ex);
                    JOptionPane.showMessageDialog(emailPanel,
                            "Error:" + ex.getMessage(),
                            "Warning",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        emailPanel = new JPanel();
        emailTitle = new JLabel();
        subject = new JTextField();
        body = new JTextArea();
        massSentCheckBox = new JCheckBox();
        setMassSentConfigBt = new JButton();
        viewSentConfigBt = new JButton();
        sentButton = new JButton();
        viewSentLogBt = new JButton();
        progressBar1 = new JProgressBar();

        //======== emailPanel ========
        {
            emailPanel.setLayout(new MigLayout(
                "insets 0,hidemode 3,gap 10 5",
                // columns
                "[fill]" +
                "[grow,fill]" +
                "[grow,fill]" +
                "[fill]",
                // rows
                "[fill]" +
                "[grow,fill]" +
                "[]" +
                "[]" +
                "[]" +
                "[]" +
                "[fill]"));

            //---- emailTitle ----
            emailTitle.setText("Subject:");
            emailPanel.add(emailTitle, "cell 0 0,align left center,grow 0 0");
            emailPanel.add(subject, "cell 1 0 3 1,aligny center,grow 100 0");

            //---- body ----
            body.setLineWrap(false);
            body.setText("");
            emailPanel.add(body, "cell 0 1 4 1,grow");

            //---- massSentCheckBox ----
            massSentCheckBox.setText("MassSent");
            massSentCheckBox.addActionListener(e -> massSentCheckBoxActionListener(e));
            emailPanel.add(massSentCheckBox, "cell 0 2");

            //---- setMassSentConfigBt ----
            setMassSentConfigBt.setText("MassSendConfig");
            setMassSentConfigBt.setEnabled(false);
            setMassSentConfigBt.addActionListener(e -> setMassSentConfigBtAction(e));
            emailPanel.add(setMassSentConfigBt, "cell 1 2");

            //---- viewSentConfigBt ----
            viewSentConfigBt.setText("ViewSentConfig");
            viewSentConfigBt.setEnabled(false);
            viewSentConfigBt.addActionListener(e -> viewSentConfigBtAction(e));
            emailPanel.add(viewSentConfigBt, "cell 2 2");

            //---- sentButton ----
            sentButton.setText("Sent");
            sentButton.addActionListener(e -> sentBtAction(e));
            emailPanel.add(sentButton, "cell 0 3 4 1");

            //---- viewSentLogBt ----
            viewSentLogBt.setText("ViewSentLog");
            viewSentLogBt.addActionListener(e -> viewSentLogBtAction(e));
            emailPanel.add(viewSentLogBt, "cell 0 4 4 1");
            emailPanel.add(progressBar1, "cell 0 5 4 1");
        }
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel emailPanel;
    private JLabel emailTitle;
    private JTextField subject;
    private JTextArea body;
    private JCheckBox massSentCheckBox;
    private JButton setMassSentConfigBt;
    private JButton viewSentConfigBt;
    private JButton sentButton;
    private JButton viewSentLogBt;
    private JProgressBar progressBar1;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
