/*
 * Created by JFormDesigner on Thu Mar 05 23:18:59 CST 2026
 */

package fan.summer.kitpage.setting;

import fan.summer.annoattion.SwissKitPage;
import fan.summer.scaner.SwissKitPageScaner;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.setting.email.EmailAddressBookEntity;
import fan.summer.database.entity.setting.email.SwissKitSettingEmailEntity;
import fan.summer.database.mapper.setting.email.SwissKitSettingEmailMapper;
import fan.summer.kitpage.setting.second.EmailAddressBookView;
import fan.summer.kitpage.setting.worker.second.QueryAllEmailInfoCallBack;
import fan.summer.kitpage.setting.worker.second.QueryAllEmailInfoWorker;
import fan.summer.plugin.PluginLoader;
import fan.summer.plugin.PluginManager;
import fan.summer.plugin.dto.PluginUpdateInfo;
import fan.summer.ui.home.HomePage;
import fan.summer.utils.EmailUtil;
import fan.summer.utils.ui.TableUtil;
import net.miginfocom.swing.MigLayout;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Settings page for application configuration.
 * Includes email server settings, address book management, and plugin installation.
 *
 * @author phoebej
 */
@SwissKitPage(menuName = "Setting", menuTooltip = "Setting", order = 99999)
public class SettingKitPage {
    private static final Logger log = LoggerFactory.getLogger(SettingKitPage.class);

    /**
     * Cached email address book data
     */
    private List<EmailAddressBookEntity> dataBaseInfo;

    /**
     * Creates a new SettingKitPage and initializes all UI components, settings, and plugin list.
     */
    public SettingKitPage() {
        initComponents();
        initSettingPageInfo();
        refreshPluginList();
    }

    /**
     * Returns the main panel for this settings page.
     *
     * @return the settings JPanel
     */
    public JPanel getPanel() {
        return settingPanle;
    }

    /**
     * Initializes the settings page with data from database.
     * Queries the latest email settings and populates the UI fields.
     * This method is called when the settings page is loaded.
     */
    private void initSettingPageInfo() {
        log.debug("Initializing settings page info from database");

        // Check if database is initialized before attempting to query
        if (!DatabaseInit.isInitialized()) {
            log.warn("Database not initialized yet, skipping settings load");
            return;
        }

        new SwingWorker<SwissKitSettingEmailEntity, Void>() {
            @Override
            protected SwissKitSettingEmailEntity doInBackground() throws Exception {
                log.debug("Querying latest email settings from database");
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    SwissKitSettingEmailMapper mapper = session.getMapper(SwissKitSettingEmailMapper.class);
                    SwissKitSettingEmailEntity entity = mapper.selectLatest();
                    log.debug("Email settings query result: {}", entity != null ? "found" : "not found");
                    return entity;
                }
            }

            @Override
            protected void done() {
                try {
                    SwissKitSettingEmailEntity entity = get();
                    if (entity != null) {
                        // Populate UI fields with retrieved data
                        textField1.setText(entity.getSmtpAddress());
                        textField2.setText(String.valueOf(entity.getSmtpPort()));
                        textField3.setText(entity.getEmail());
                        textField4.setText(entity.getPassword());
                        textField5.setText(entity.getFromAddress());
                        isTsl.setSelected(entity.getNeedTLS());
                        isSsl.setSelected(entity.getNeedSSL());
                        log.info("Email settings loaded successfully: smtpAddress={}, smtpPort={}",
                                entity.getSmtpAddress(), entity.getSmtpPort());
                    } else {
                        log.debug("No existing email settings found in database");
                    }
                } catch (Exception e) {
                    log.error("Failed to load email settings from database", e);
                }
            }
        }.execute();
    }

    /**
     * Opens the email address book view dialog.
     * Queries all email addresses from database and displays them in a table.
     */
    private void openAddressBookBtActionListener(ActionEvent e) {
        log.debug("Opening address book view");
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
                    new EmailAddressBookView(settingPanle).initTable(rowData, dataBaseInfo).setVisible(true);
                } else {
                    new EmailAddressBookView(settingPanle).setVisible(true);
                }
            }

            @Override
            public void onFailure(Exception e) {
                log.error("Failed to load email addresses", e);
                JOptionPane.showMessageDialog(settingTable,
                        "Can Not Find Any Email Address!",
                        "Warning",
                        JOptionPane.WARNING_MESSAGE);
            }
        }).execute();


    }

    /**
     * Handles the plugin file selection action.
     * Opens a file chooser dialog for selecting a JAR plugin file.
     *
     * @param e the action event
     */
    private void choicePluginBtAction(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setDialogTitle("Select Plugin Jar");
        int result = fileChooser.showOpenDialog(settingTable);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            String fileName = selectedFile.getName().toLowerCase();

            if (!fileName.endsWith(".jar")) {
                log.warn("Invalid file type selected: {}", fileName);
                JOptionPane.showMessageDialog(settingTable,
                        "Please select a valid JAR file!",
                        "Invalid File Type",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            log.debug("Selected plugin file: {}", selectedFile.getAbsolutePath());
            pluginPath.setText(selectedFile.getAbsolutePath());
        }
    }

    /**
     * Handles the plugin deploy action.
     * Copies the selected JAR to plugin directory and loads it immediately (hot-deploy).
     *
     * @param e the action event
     */
    private void deployPluginBtAction(ActionEvent e) {
        String path = pluginPath.getText();
        if (path == null || path.isEmpty()) {
            JOptionPane.showMessageDialog(settingTable,
                    "Please select a plugin JAR first",
                    "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File source = new File(path);
        try {
            File canonical = source.getCanonicalFile();
            if (!canonical.exists() || !canonical.getName().toLowerCase().endsWith(".jar")) {
                JOptionPane.showMessageDialog(settingTable,
                        "Invalid JAR file selected",
                        "Invalid File", JOptionPane.ERROR_MESSAGE);
                return;
            }
            // Prevent path traversal attacks
            if (!canonical.getParentFile().equals(source.getParentFile())) {
                JOptionPane.showMessageDialog(settingTable,
                        "Invalid JAR file path: path traversal not allowed",
                        "Invalid File", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(settingTable,
                    "Invalid JAR file path",
                    "Invalid File", JOptionPane.ERROR_MESSAGE);
            return;
        }

        new SwingWorker<List<Object>, Void>() {
            @Override
            protected List<Object> doInBackground() throws Exception {
                File pluginDir = Path.of(PluginLoader.PLUGIN_DIR).toFile();
                pluginDir.mkdirs();

                Path target = pluginDir.toPath().resolve(source.getName());
                log.debug("Copying plugin from {} to {}", source, target);
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied plugin to: {}", target);

                return PluginLoader.deployPlugin(target.toFile());
            }

            @Override
            protected void done() {
                try {
                    List<Object> newPages = get();
                    SwingUtilities.invokeLater(() -> {
                        HomePage.getInstance().refreshSidebar(newPages);
                        refreshPluginList();
                    });

                    if (!newPages.isEmpty()) {
                        String names = newPages.stream()
                                .map(p -> SwissKitPageScaner.getMenuName(p))
                                .collect(Collectors.joining(", "));
                        JOptionPane.showMessageDialog(settingTable,
                                "Deployed successfully:\n" + names,
                                "Deploy Success", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(settingTable,
                                "Plugin loaded but no visible KitPages found.\nCheck that the JAR contains a valid KitPage implementation.",
                                "Warning", JOptionPane.WARNING_MESSAGE);
                    }
                } catch (Exception ex) {
                    log.error("Deploy failed", ex);
                    JOptionPane.showMessageDialog(settingTable,
                            "Deploy failed: " + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Handles the plugin hot-reload action.
     * Reloads the selected plugin JAR without restarting the application.
     *
     * @param e the action event
     */
    private void reloadPluginBtAction(ActionEvent e) {
        int selectedRow = installedPluginTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(settingTable,
                    "Please select a plugin to reload",
                    "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String pluginName = (String) installedPluginTable.getValueAt(selectedRow, 0);

        int confirm = JOptionPane.showConfirmDialog(settingTable,
                "Hot-reload plugin: " + pluginName + "?\nThe sidebar will update immediately.",
                "Confirm Reload", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            new SwingWorker<List<Object>, Void>() {
                @Override
                protected List<Object> doInBackground() throws Exception {
                    return PluginLoader.reloadPlugin(pluginName);
                }

                @Override
                protected void done() {
                    try {
                        List<Object> newPages = get();
                        SwingUtilities.invokeLater(() -> {
                            HomePage.getInstance().refreshSidebar(newPages);
                            refreshPluginList();
                        });

                        String names = newPages.stream()
                                .map(p -> SwissKitPageScaner.getMenuName(p))
                                .collect(Collectors.joining(", "));
                        JOptionPane.showMessageDialog(settingTable,
                                "Reloaded successfully:\n" + names,
                                "Reload Success", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        log.error("Reload failed", ex);
                        JOptionPane.showMessageDialog(settingTable,
                                "Reload failed: " + ex.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }
    }

    /**
     * Handles the plugin uninstall action.
     * Deletes the selected plugin JAR file from the plugins directory.
     *
     * @param e the action event
     */
    private void uninstallPluginBtAction(ActionEvent e) {
        int selectedRow = installedPluginTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(settingTable,
                    "Please select a plugin to uninstall",
                    "No Selection",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String pluginName = (String) installedPluginTable.getValueAt(selectedRow, 0);
        int confirm = JOptionPane.showConfirmDialog(settingTable,
                "Uninstall plugin: " + pluginName + "?",
                "Confirm Uninstall", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            // Disable button during uninstall
            uninstallPluginBt.setEnabled(false);

            // Use SwingWorker to handle background deletion with GC
            new SwingWorker<Boolean, Void>() {
                @Override
                protected Boolean doInBackground() {
                    // Step 1: Refresh sidebar to remove plugin page references
                    SwingUtilities.invokeLater(() -> HomePage.getInstance().refreshSidebar());
                    SwingUtilities.invokeLater(() -> refreshPluginList());

                    // Step 2: Close the ClassLoader and trigger GC
                    PluginLoader.unloadPlugin(pluginName);
                    PluginManager.unregisterPlugin(pluginName);

                    // Step 3: Retry deletion with GC between attempts
                    File pluginFile = new File(PluginLoader.PLUGIN_DIR, pluginName);
                    log.info("Attempting to delete plugin file: {}", pluginFile.getAbsolutePath());
                    log.info("File exists before delete: {}", pluginFile.exists());

                    for (int i = 0; i < 5; i++) {
                        boolean deleteResult = pluginFile.delete();
                        boolean existsAfter = pluginFile.exists();
                        log.info("Delete attempt {}: delete()={}, exists()={}", i + 1, deleteResult, existsAfter);

                        if (deleteResult && !existsAfter) {
                            log.info("Plugin file deleted successfully on attempt {}", i + 1);
                            return true;
                        }

                        // Wait and suggest GC for Windows file handle release
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        System.gc();
                    }

                    // Final check: verify file is actually deleted
                    boolean finalExists = pluginFile.exists();
                    log.info("Final exists check: {}", finalExists);
                    return !finalExists;
                }

                @Override
                protected void done() {
                    uninstallPluginBt.setEnabled(true);
                    boolean deleted;
                    try {
                        deleted = get();
                    } catch (Exception e) {
                        deleted = false;
                    }
                    SwingUtilities.invokeLater(() -> {
                        HomePage.getInstance().refreshSidebar();
                        refreshPluginList();
                    });

                    if (deleted) {
                        JOptionPane.showMessageDialog(settingTable,
                                "Plugin uninstalled successfully.",
                                "Uninstall Success",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(settingTable,
                                "Failed to delete plugin file. Please restart the application and try again.",
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }
    }

    /**
     * Refreshes the installed plugins table with current plugin directory contents.
     */
    private void refreshPluginList() {
        List<File> plugins = PluginLoader.getInstalledPlugins();
        List<Object[]> rowData = new ArrayList<>();
        for (File plugin : plugins) {
            String size;
            long bytes = plugin.length();
            if (bytes < 1024) {
                size = bytes + " B";
            } else if (bytes < 1024 * 1024) {
                size = String.format("%.1f KB", bytes / 1024.0);
            } else {
                size = String.format("%.1f MB", bytes / (1024.0 * 1024.0));
            }

            PluginLoader.PluginState state = PluginLoader.getPluginState(plugin.getName());
            String version = state != null && state.getPluginVersion() != null ? state.getPluginVersion() : "N/A";
            boolean isEnabled = state == null || state.isEnabled();
            String status = isEnabled ? "Enabled" : "Disabled";

            rowData.add(new Object[]{plugin.getName(), version, status, size});
        }
        String[] columns = {"Plugin Name", "Version", "Status", "Size"};
        TableUtil.initTable(installedPluginTable, columns, rowData, 2);
    }

    /**
     * Handles the plugin enable/disable toggle action.
     *
     * @param e the action event
     */
    private void enableDisablePluginBtAction(ActionEvent e) {
        int selectedRow = installedPluginTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(settingTable,
                    "Please select a plugin to enable/disable",
                    "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String jarName = (String) installedPluginTable.getValueAt(selectedRow, 0);
        PluginLoader.PluginState state = PluginLoader.getPluginState(jarName);
        boolean currentlyEnabled = state == null || state.isEnabled();

        String action = currentlyEnabled ? "disable" : "enable";
        int confirm = JOptionPane.showConfirmDialog(settingTable,
                (currentlyEnabled ? "Disable" : "Enable") + " plugin: " + jarName + "?",
                "Confirm " + action, JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            new SwingWorker<Boolean, Void>() {
                @Override
                protected Boolean doInBackground() throws Exception {
                    if (currentlyEnabled) {
                        return PluginLoader.disablePlugin(jarName);
                    } else {
                        return PluginLoader.enablePlugin(jarName);
                    }
                }

                @Override
                protected void done() {
                    boolean success;
                    try {
                        success = get();
                    } catch (Exception ex) {
                        success = false;
                    }

                    if (success) {
                        SwingUtilities.invokeLater(() -> {
                            HomePage.getInstance().refreshSidebar();
                            refreshPluginList();
                        });
                        JOptionPane.showMessageDialog(settingTable,
                                "Plugin " + (currentlyEnabled ? "disabled" : "enabled") + " successfully.",
                                "Success", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(settingTable,
                                "Failed to " + action + " plugin.",
                                "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }
    }

    /**
     * Handles the check updates action.
     *
     * @param e the action event
     */
    private void checkUpdatesBtAction(ActionEvent e) {
        checkUpdatesBt.setEnabled(false);

        new SwingWorker<List<PluginUpdateInfo>, Void>() {
            @Override
            protected List<PluginUpdateInfo> doInBackground() throws Exception {
                return PluginManager.checkAllForUpdates();
            }

            @Override
            protected void done() {
                checkUpdatesBt.setEnabled(true);
                List<PluginUpdateInfo> updates;
                try {
                    updates = get();
                } catch (Exception ex) {
                    updates = new ArrayList<>();
                }

                refreshPluginList();

                if (updates.isEmpty()) {
                    JOptionPane.showMessageDialog(settingTable,
                            "All plugins are up to date!",
                            "Check Complete", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    StringBuilder msg = new StringBuilder();
                    msg.append(updates.size()).append(" plugin(s) have updates available:\n\n");
                    for (PluginUpdateInfo update : updates) {
                        msg.append(update.getPluginName())
                           .append(": ").append(update.getCurrentVersion())
                           .append(" -> ").append(update.getLatestVersion())
                           .append("\n");
                    }
                    JOptionPane.showMessageDialog(settingTable,
                            msg.toString(),
                            "Updates Found", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Validates and saves the email server settings to the database.
     * Reads SMTP address, port, email address, and password from the form fields.
     *
     * @param e the action event triggered by saveBtAction
     */
    private void saveBtAction(ActionEvent e) {
        // Validate required fields before saving
        String smtpAddress = textField1.getText().trim();
        String smtpPortStr = textField2.getText().trim();
        String email = textField3.getText().trim();
        String password = textField4.getText().trim();
        String fromAddress = textField5.getText().trim();

        // Check if required fields are empty
        if (smtpAddress.isEmpty()) {
            JOptionPane.showMessageDialog(settingTable,
                    "ServerUrl cannot be empty!",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            textField1.requestFocusInWindow();
            return;
        }

        if (smtpPortStr.isEmpty()) {
            JOptionPane.showMessageDialog(settingTable,
                    "ServerPort cannot be empty!",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            textField2.requestFocusInWindow();
            return;
        }

        // Validate port is a valid number
        int smtpPort;
        try {
            smtpPort = Integer.parseInt(smtpPortStr);
            if (smtpPort <= 0 || smtpPort > 65535) {
                JOptionPane.showMessageDialog(settingTable,
                        "ServerPort must be a valid port number (1-65535)!",
                        "Validation Error",
                        JOptionPane.ERROR_MESSAGE);
                textField2.requestFocusInWindow();
                return;
            }
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(settingTable,
                    "ServerPort must be a valid number!",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            textField2.requestFocusInWindow();
            return;
        }

        if (email.isEmpty()) {
            JOptionPane.showMessageDialog(settingTable,
                    "UserName cannot be empty!",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            textField3.requestFocusInWindow();
            return;
        }

        if (password.isEmpty()) {
            JOptionPane.showMessageDialog(settingTable,
                    "PassWord cannot be empty!",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            textField4.requestFocusInWindow();
            return;
        }

        if (fromAddress.isEmpty()) {
            JOptionPane.showMessageDialog(settingTable,
                    "FromAddress cannot be empty!",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            textField5.requestFocusInWindow();
            return;
        }

        // Validate TLS and SSL are not both selected
        if (isTsl.isSelected() && isSsl.isSelected()) {
            JOptionPane.showMessageDialog(settingTable,
                    "TSL and SSL cannot be both selected!",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // All validations passed, proceed with database save
        log.debug("Starting to save email settings");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (SqlSession session = DatabaseInit.getSqlSession()) {
                    SwissKitSettingEmailMapper mapper = session.getMapper(SwissKitSettingEmailMapper.class);

                    // Delete all existing records first to keep only latest data
                    log.debug("Deleting existing email settings from database");
                    mapper.deleteAll();

                    // Create entity with validated values
                    SwissKitSettingEmailEntity entity = new SwissKitSettingEmailEntity();
                    entity.setEmail(email);
                    entity.setPassword(password);
                    entity.setSmtpAddress(smtpAddress);
                    entity.setSmtpPort(smtpPort);
                    entity.setNeedTLS(isTsl.isSelected());
                    entity.setNeedSSL(isSsl.isSelected());
                    entity.setFromAddress(fromAddress);

                    // Insert new record
                    log.debug("Inserting new email settings: smtpAddress={}, smtpPort={}, email={}, fromAddress={}, needTLS={}, needSSL={}",
                            smtpAddress, smtpPort, email, fromAddress, isTsl.isSelected(), isSsl.isSelected());
                    mapper.insert(entity);
                    session.commit();

                    log.info("Email settings saved successfully: smtpAddress={}, smtpPort={}, email={}",
                            smtpAddress, smtpPort, email);
                } catch (Exception e) {
                    log.error("Failed to save email settings to database", e);
                    throw e;
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    JOptionPane.showMessageDialog(settingTable,
                            "Email settings saved successfully!",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    log.error("Error in save completion", e);
                    JOptionPane.showMessageDialog(settingTable,
                            "Failed to save email settings: " + e.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();

    }

    /**
     * Sends a test email to the configured email address using current SMTP settings.
     * Validates that settings are saved before attempting to send.
     *
     * @param e the action event triggered by sentTestEmailBt
     */
    private void sentTestEmailBtAction(ActionEvent e) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            SwissKitSettingEmailMapper mapper = session.getMapper(SwissKitSettingEmailMapper.class);
            SwissKitSettingEmailEntity swissKitSettingEmailEntity = mapper.selectLatest();
            EmailUtil.sendEmail(EmailUtil.EmailMessage.builder().to(swissKitSettingEmailEntity.getFromAddress()).subject("SwisskitTestMail").textBody("SwisskitTestMail").build());
        } catch (EmailUtil.EmailException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        settingPanle = new JPanel();
        settingTable = new JTabbedPane();
        email = new JPanel();
        label1 = new JLabel();
        label6 = new JLabel();
        label2 = new JLabel();
        textField1 = new JTextField();
        label3 = new JLabel();
        textField2 = new JTextField();
        label4 = new JLabel();
        textField3 = new JTextField();
        label5 = new JLabel();
        textField4 = new JTextField();
        label7 = new JLabel();
        textField5 = new JTextField();
        isTsl = new JCheckBox();
        isSsl = new JCheckBox();
        sentTestEmailBt = new JButton();
        saveBtAction = new JButton();
        button3 = new JButton();
        plugin = new JPanel();
        pluginScrollPane = new JScrollPane();
        installedPluginTable = new JTable();
        choicePluginBt = new JButton();
        pluginPath = new JTextField();
        deployPluginBt = new JButton();
        reloadPluginBt = new JButton();
        uninstallPluginBt = new JButton();
        enableDisablePluginBt = new JButton();
        checkUpdatesBt = new JButton();

        //======== settingPanle ========
        {
            settingPanle.setMinimumSize(new Dimension(310, 379));
            settingPanle.setLayout(new MigLayout(
                "hidemode 3",
                // columns
                "[fill]" +
                "[592,fill]",
                // rows
                "[]" +
                "[]" +
                "[428]"));

            //======== settingTable ========
            {

                //======== email ========
                {
                    email.setLayout(new MigLayout(
                        "hidemode 3",
                        // columns
                        "[78,fill]" +
                        "[fill]" +
                        "[431,fill]",
                        // rows
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[345]"));

                    //---- label1 ----
                    label1.setText("Protocol");
                    email.add(label1, "cell 0 0");

                    //---- label6 ----
                    label6.setText("SMTP");
                    email.add(label6, "cell 1 0");

                    //---- label2 ----
                    label2.setText("ServerUrl");
                    email.add(label2, "cell 0 1");
                    email.add(textField1, "cell 1 1 2 1");

                    //---- label3 ----
                    label3.setText("ServerPort");
                    email.add(label3, "cell 0 2");
                    email.add(textField2, "cell 1 2 2 1");

                    //---- label4 ----
                    label4.setText("UserName");
                    email.add(label4, "cell 0 3");
                    email.add(textField3, "cell 1 3 2 1");

                    //---- label5 ----
                    label5.setText("PassWord");
                    email.add(label5, "cell 0 4");
                    email.add(textField4, "cell 1 4 2 1");

                    //---- label7 ----
                    label7.setText("FromAddress");
                    email.add(label7, "cell 0 5");
                    email.add(textField5, "cell 1 5 2 1");

                    //---- isTsl ----
                    isTsl.setText("TSL");
                    email.add(isTsl, "cell 0 6");

                    //---- isSsl ----
                    isSsl.setText("SSL");
                    email.add(isSsl, "cell 1 6");

                    //---- sentTestEmailBt ----
                    sentTestEmailBt.setText("SentTestEmail");
                    sentTestEmailBt.addActionListener(e -> sentTestEmailBtAction(e));
                    email.add(sentTestEmailBt, "cell 0 8 3 1");

                    //---- saveBtAction ----
                    saveBtAction.setText("Save");
                    saveBtAction.addActionListener(e -> saveBtAction(e));
                    email.add(saveBtAction, "cell 0 9 3 1");

                    //---- button3 ----
                    button3.setText("OpenAddressBook");
                    button3.addActionListener(e -> openAddressBookBtActionListener(e));
                    email.add(button3, "cell 0 12 3 1");
                }
                settingTable.addTab("Email", email);

                //======== plugin ========
                {
                    plugin.setLayout(new MigLayout(
                        "hidemode 3",
                        // columns
                        "[fill]" +
                        "[465,fill]",
                        // rows
                        "[200:200,fill]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]" +
                        "[]"));

                    //======== pluginScrollPane ========
                    {
                        pluginScrollPane.setViewportView(installedPluginTable);
                    }
                    plugin.add(pluginScrollPane, "cell 0 0 2 1,grow");

                    //---- choicePluginBt ----
                    choicePluginBt.setText("ChoicePlugin");
                    choicePluginBt.addActionListener(e -> choicePluginBtAction(e));
                    plugin.add(choicePluginBt, "cell 0 1");

                    //---- pluginPath ----
                    pluginPath.setEditable(false);
                    plugin.add(pluginPath, "cell 1 1");

                    //---- deployPluginBt ----
                    deployPluginBt.setText("Deploy");
                    deployPluginBt.addActionListener(e -> deployPluginBtAction(e));
                    plugin.add(deployPluginBt, "cell 0 2 2 1");

                    //---- enableDisablePluginBt ----
                    enableDisablePluginBt.setText("Enable/Disable");
                    enableDisablePluginBt.addActionListener(e -> enableDisablePluginBtAction(e));
                    plugin.add(enableDisablePluginBt, "cell 0 3 2 1");

                    //---- checkUpdatesBt ----
                    checkUpdatesBt.setText("CheckUpdates");
                    checkUpdatesBt.addActionListener(e -> checkUpdatesBtAction(e));
                    plugin.add(checkUpdatesBt, "cell 0 4 2 1");

                    //---- reloadPluginBt ----
                    reloadPluginBt.setText("Reload");
                    reloadPluginBt.addActionListener(e -> reloadPluginBtAction(e));
                    plugin.add(reloadPluginBt, "cell 0 5 2 1");

                    //---- uninstallPluginBt ----
                    uninstallPluginBt.setText("Uninstall");
                    uninstallPluginBt.addActionListener(e -> uninstallPluginBtAction(e));
                    plugin.add(uninstallPluginBt, "cell 0 6 2 1");
                }
                settingTable.addTab("Plugin", plugin);
            }
            settingPanle.add(settingTable, "cell 0 0 2 3,aligny top,growy 0");
        }
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel settingPanle;
    private JTabbedPane settingTable;
    private JPanel email;
    private JLabel label1;
    private JLabel label6;
    private JLabel label2;
    private JTextField textField1;
    private JLabel label3;
    private JTextField textField2;
    private JLabel label4;
    private JTextField textField3;
    private JLabel label5;
    private JTextField textField4;
    private JLabel label7;
    private JTextField textField5;
    private JCheckBox isTsl;
    private JCheckBox isSsl;
    private JButton sentTestEmailBt;
    private JButton saveBtAction;
    private JButton button3;
    private JPanel plugin;
    private JScrollPane pluginScrollPane;
    private JTable installedPluginTable;
    private JButton choicePluginBt;
    private JTextField pluginPath;
    private JButton deployPluginBt;
    private JButton reloadPluginBt;
    private JButton uninstallPluginBt;
    private JButton enableDisablePluginBt;
    private JButton checkUpdatesBt;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
