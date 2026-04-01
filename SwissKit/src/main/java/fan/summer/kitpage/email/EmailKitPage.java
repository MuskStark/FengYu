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
import javax.swing.text.html.HTMLEditorKit;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.StringWriter;
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
        setupRichTextEditor();
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
        new EmailSentWorker(subject.getText(), getEditorHtmlContent(), taskId, massSentCheckBox.isSelected(), progressBar1).execute();
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
        body = new JEditorPane();
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
                "[]" +
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
            body.setContentType("text/html");
            emailPanel.add(body, "cell 0 2 4 1,grow");

            //---- massSentCheckBox ----
            massSentCheckBox.setText("MassSent");
            massSentCheckBox.addActionListener(e -> massSentCheckBoxActionListener(e));
            emailPanel.add(massSentCheckBox, "cell 0 3");

            //---- setMassSentConfigBt ----
            setMassSentConfigBt.setText("MassSendConfig");
            setMassSentConfigBt.setEnabled(false);
            setMassSentConfigBt.addActionListener(e -> setMassSentConfigBtAction(e));
            emailPanel.add(setMassSentConfigBt, "cell 1 3");

            //---- viewSentConfigBt ----
            viewSentConfigBt.setText("ViewSentConfig");
            viewSentConfigBt.setEnabled(false);
            viewSentConfigBt.addActionListener(e -> viewSentConfigBtAction(e));
            emailPanel.add(viewSentConfigBt, "cell 2 3");

            //---- sentButton ----
            sentButton.setText("Sent");
            sentButton.addActionListener(e -> sentBtAction(e));
            emailPanel.add(sentButton, "cell 0 4 4 1");

            //---- viewSentLogBt ----
            viewSentLogBt.setText("ViewSentLog");
            viewSentLogBt.addActionListener(e -> viewSentLogBtAction(e));
            emailPanel.add(viewSentLogBt, "cell 0 5 4 1");
            emailPanel.add(progressBar1, "cell 0 6 4 1");
        }
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel emailPanel;
    private JLabel emailTitle;
    private JTextField subject;
    private JEditorPane body;
    private JCheckBox massSentCheckBox;
    private JButton setMassSentConfigBt;
    private JButton viewSentConfigBt;
    private JButton sentButton;
    private JButton viewSentLogBt;
    private JProgressBar progressBar1;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on

    // Formatting toolbar components
    private JPanel formattingToolbar;
    private JButton boldButton;
    private JButton italicButton;
    private JButton underlineButton;
    private JComboBox<String> fontFamilyCombo;
    private JComboBox<Integer> fontSizeCombo;
    private JButton textColorButton;
    private JButton alignLeftButton;
    private JButton alignCenterButton;
    private JButton alignRightButton;

    /**
     * Sets up the rich text editor after initComponents().
     * Creates the formatting toolbar and configures the JEditorPane with HTMLEditorKit.
     */
    private void setupRichTextEditor() {
        createFormattingToolbar();
        configureEditorKit();
    }

    /**
     * Creates the formatting toolbar with Bold, Italic, Underline, Font, Size, and Color controls.
     */
    private void createFormattingToolbar() {
        formattingToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));

        // Font family combo box
        fontFamilyCombo = new JComboBox<>(new String[]{"SansSerif", "Serif", "Monospaced", "Dialog"});
        fontFamilyCombo.setSelectedItem("SansSerif");
        fontFamilyCombo.addActionListener(e -> applyFontStyle());
        formattingToolbar.add(fontFamilyCombo);

        // Font size combo box
        fontSizeCombo = new JComboBox<>(new Integer[]{9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36});
        fontSizeCombo.setSelectedItem(12);
        fontSizeCombo.addActionListener(e -> applyFontStyle());
        formattingToolbar.add(fontSizeCombo);

        formattingToolbar.add(Box.createHorizontalStrut(5));

        // Bold button
        boldButton = new JButton("B");
        boldButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        boldButton.addActionListener(e -> toggleBold());
        formattingToolbar.add(boldButton);

        // Italic button
        italicButton = new JButton("I");
        italicButton.setFont(new Font("SansSerif", Font.ITALIC, 12));
        italicButton.addActionListener(e -> toggleItalic());
        formattingToolbar.add(italicButton);

        // Underline button
        underlineButton = new JButton("U");
        underlineButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
        underlineButton.addActionListener(e -> toggleUnderline());
        formattingToolbar.add(underlineButton);

        formattingToolbar.add(Box.createHorizontalStrut(5));

        // Text color button
        textColorButton = new JButton("Color");
        textColorButton.addActionListener(e -> changeTextColor());
        formattingToolbar.add(textColorButton);

        formattingToolbar.add(Box.createHorizontalStrut(5));

        // Alignment buttons using StyledEditorKit actions
        alignLeftButton = new JButton("L");
        alignLeftButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
        alignLeftButton.addActionListener(new javax.swing.text.StyledEditorKit.AlignmentAction("Left", javax.swing.text.StyleConstants.ALIGN_LEFT));
        formattingToolbar.add(alignLeftButton);

        alignCenterButton = new JButton("C");
        alignCenterButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
        alignCenterButton.addActionListener(new javax.swing.text.StyledEditorKit.AlignmentAction("Center", javax.swing.text.StyleConstants.ALIGN_CENTER));
        formattingToolbar.add(alignCenterButton);

        alignRightButton = new JButton("R");
        alignRightButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
        alignRightButton.addActionListener(new javax.swing.text.StyledEditorKit.AlignmentAction("Right", javax.swing.text.StyleConstants.ALIGN_RIGHT));
        formattingToolbar.add(alignRightButton);

        // Add toolbar to panel above the body editor
        emailPanel.add(formattingToolbar, "cell 0 1 4 1,aligny top,grow 0 0");
    }

    /**
     * Configures the JEditorPane with HTMLEditorKit and default styles.
     */
    private void configureEditorKit() {
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        body.setFont(new Font("SansSerif", Font.PLAIN, 12));

        // Set initial document style to preserve spaces
        javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) body.getDocument();
        javax.swing.text.SimpleAttributeSet attrs = new javax.swing.text.SimpleAttributeSet();
        attrs.addAttribute(javax.swing.text.html.CSS.Attribute.WHITE_SPACE, "pre-wrap");
        doc.getStyleSheet().addRule("body { white-space: pre-wrap; }");
    }

    /**
     * Applies the selected font family and size to the current selection.
     */
    private void applyFontStyle() {
        String fontFamily = (String) fontFamilyCombo.getSelectedItem();
        Integer fontSize = (Integer) fontSizeCombo.getSelectedItem();

        if (body.getSelectionEnd() > body.getSelectionStart()) {
            javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) body.getDocument();
            javax.swing.text.Style style = doc.addStyle("temp", null);
            javax.swing.text.StyleConstants.setFontFamily(style, fontFamily);
            javax.swing.text.StyleConstants.setFontSize(style, fontSize);
            doc.setCharacterAttributes(body.getSelectionStart(), body.getSelectionEnd() - body.getSelectionStart(), style, true);
        }
    }

    /**
     * Toggles bold formatting on the current selection.
     */
    private void toggleBold() {
        int start = body.getSelectionStart();
        int end = body.getSelectionEnd();
        if (start == end) return;

        javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) body.getDocument();
        javax.swing.text.Style style = doc.addStyle("bold", null);
        Boolean currentBold = javax.swing.text.StyleConstants.isBold(doc.getCharacterElement(start).getAttributes());
        javax.swing.text.StyleConstants.setBold(style, !currentBold);
        doc.setCharacterAttributes(start, end - start, style, true);
    }

    /**
     * Toggles italic formatting on the current selection.
     */
    private void toggleItalic() {
        int start = body.getSelectionStart();
        int end = body.getSelectionEnd();
        if (start == end) return;

        javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) body.getDocument();
        javax.swing.text.Style style = doc.addStyle("italic", null);
        Boolean currentItalic = javax.swing.text.StyleConstants.isItalic(doc.getCharacterElement(start).getAttributes());
        javax.swing.text.StyleConstants.setItalic(style, !currentItalic);
        doc.setCharacterAttributes(start, end - start, style, true);
    }

    /**
     * Toggles underline formatting on the current selection.
     */
    private void toggleUnderline() {
        int start = body.getSelectionStart();
        int end = body.getSelectionEnd();
        if (start == end) return;

        javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) body.getDocument();
        javax.swing.text.Style style = doc.addStyle("underline", null);
        Boolean currentUnderline = javax.swing.text.StyleConstants.isUnderline(doc.getCharacterElement(start).getAttributes());
        javax.swing.text.StyleConstants.setUnderline(style, !currentUnderline);
        doc.setCharacterAttributes(start, end - start, style, true);
    }

    /**
     * Opens a color chooser dialog and applies the selected color to the current selection.
     */
    private void changeTextColor() {
        Color color = JColorChooser.showDialog(body, "Choose Text Color", Color.BLACK);
        if (color != null) {
            int start = body.getSelectionStart();
            int end = body.getSelectionEnd();
            if (start == end) return;

            javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) body.getDocument();
            javax.swing.text.Style style = doc.addStyle("color", null);
            javax.swing.text.StyleConstants.setForeground(style, color);
            doc.setCharacterAttributes(start, end - start, style, true);
        }
    }

    /**
     * Extracts HTML content from the editor for email sending.
     * Uses HTMLEditorKit to properly get the HTML content including multi-line text and formatting.
     * Removes the outer html and body tags to get just the body content.
     *
     * @return HTML string suitable for email body
     */
    private String getEditorHtmlContent() {
        String html;
        try {
            HTMLEditorKit kit = (HTMLEditorKit) body.getEditorKit();
            StringWriter writer = new StringWriter();
            kit.write(writer, body.getDocument(), 0, body.getDocument().getLength());
            html = writer.toString();
        } catch (Exception e) {
            log.error("Failed to get HTML content from editor, falling back to getText()", e);
            html = body.getText();
        }
        if (html.startsWith("<html>")) {
            int bodyStart = html.toLowerCase().indexOf("<body");
            if (bodyStart != -1) {
                bodyStart = html.indexOf(">", bodyStart) + 1;
                int bodyEnd = html.toLowerCase().indexOf("</body>");
                if (bodyEnd != -1) {
                    html = html.substring(bodyStart, bodyEnd).trim();
                }
            }
        }
        return html;
    }
}
