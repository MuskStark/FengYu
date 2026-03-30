/*
 * Created by JFormDesigner on Thu Mar 19 10:27:32 CST 2026
 */

package plugin.swisskit.hpl.ui;


import fan.summer.annoattion.SwissKitPage;
import fan.summer.api.KitPage;
import fan.summer.ui.components.GradientProgressBar;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import plugin.swisskit.hpl.util.ConfigLoader;
import plugin.swisskit.hpl.worker.HappyLearningWorker;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Happy Learning plugin page for SwissKitJ.
 * <p>
 * This plugin provides automated learning functionality with configurable settings.
 * Users can upload configuration files and set passkeys for authentication.
 * </p>
 *
 * @author summer
 */
@SwissKitPage(menuName = "HappyLearn", menuTooltip = "HappyLearn", order = 6)
public class HappyLearning implements KitPage {

    private static final Logger log = LoggerFactory.getLogger(HappyLearning.class);

    /**
     * Authentication key for the learning plugin.swisskit.hpl.service
     */
    private String key;

    /**
     * Current learning plugin.swisskit.hpl.worker instance
     */
    private HappyLearningWorker currentWorker;

    /**
     * Constructs a new HappyLearning page and initializes UI components.
     */
    public HappyLearning() {
        initComponents();
        majorSubjiectPB.setStringPainted(true);
        electiveSubjectPB.setStringPainted(true);
        stopBt.setEnabled(false);

        // Show config file name in configFilePath if config file exists
        Path configFile = Path.of(ConfigLoader.CONFIG_DIR, "netschool-headers.json");
        if (configFile.toFile().exists()) {
            configFilePath.setText(configFile.toAbsolutePath().toString());
        }

        // Mutual exclusion for checkboxes
        onlyMajorCheckBox.addItemListener(e -> {
            if (onlyMajorCheckBox.isSelected()) {
                onlyElectiveCheckBox.setSelected(false);
            }
        });
        onlyElectiveCheckBox.addItemListener(e -> {
            if (onlyElectiveCheckBox.isSelected()) {
                onlyMajorCheckBox.setSelected(false);
            }
        });
    }

    /**
     * Returns the main panel of this page.
     *
     * @return the learning panel
     */
    @Override
    public JPanel getPanel() {
        return learningPanel;
    }

    /**
     * Handles the upload button action.
     * Opens a file chooser dialog to select a config file, then copies it to the config directory.
     *
     * @param e the action event
     */
    private void uploadBtAction(ActionEvent e) {
        File configDir = Path.of(ConfigLoader.CONFIG_DIR).toFile();
        configDir.mkdirs();
        try {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

            // Set config file filter
            fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Config Files (*.json)", "json"
            ));
            fileChooser.setDialogTitle("Select Config File");
            int result = fileChooser.showOpenDialog(learningPanel);
            if (result == JFileChooser.APPROVE_OPTION) {
                configFilePath.setText(fileChooser.getSelectedFile().getAbsolutePath());
            }

            Path source = Path.of(configFilePath.getText());
            if (!Files.exists(source)) {
                throw new IOException("Config file not found: " + source);
            }
            Path target = configDir.toPath().resolve(source.toFile().getName());
            log.debug("Installing config from {} to {}", source, target);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Successfully installed config: {}", source.toFile().getName());
            JOptionPane.showMessageDialog(null,
                    "✅ Installed:" + Path.of(configFilePath.getText()).toFile().getName() + "（Need ReOpen SwissKitJ）",
                    "Plugin Install Success",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            log.error("Failed to install config: {}", configFilePath.getText(), ex);
            JOptionPane.showMessageDialog(null,
                    "Error:" + ex.getMessage(),
                    "Plugin Install Error",
                    JOptionPane.ERROR_MESSAGE);
        }

    }

    /**
     * Handles the set passkey button action.
     * Validates and stores the passkey for authentication.
     *
     * @param e the action event
     */
    private void setPassKeyBtAction(ActionEvent e) {
        if (passKey.getText().isEmpty()) {
            log.warn("[UI] Passkey is empty, showing error dialog");
            JOptionPane.showMessageDialog(null,
                    "PASSKEY IS EMPTY",
                    "PASSKEY EMPTY Error",
                    JOptionPane.ERROR_MESSAGE);

        } else {
            this.key = passKey.getText();
            JOptionPane.showMessageDialog(null,
                    "PASSKEY SET SUCCESS",
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE);
            log.info("[UI] Passkey set successfully，value:{}", this.key);
        }

    }

    /**
     * Handles the start button action.
     * Launches the learning plugin.swisskit.hpl.worker based on selected course type options.
     * <ul>
     *   <li>If "OnlyMajorSubject" is selected: learns only MajorSubject courses</li>
     *   <li>If "OnlyElectiveSubject" is selected: learns only ElectiveSubject courses</li>
     *   <li>If neither is selected: auto-learning mode (prioritizes ElectiveSubject if not completed)</li>
     * </ul>
     *
     * @param e the action event
     */
    private void startBtAction(ActionEvent e) {
        if (currentWorker != null && !currentWorker.isDone()) {
            log.warn("[UI] Start button clicked but learning is already running");
            JOptionPane.showMessageDialog(null,
                    "Learning is already running",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (key == null || key.isEmpty()) {
            log.warn("[UI] Start button clicked but passkey is not set");
            JOptionPane.showMessageDialog(null,
                    "Please set PASSKEY first",
                    "PASSKEY Not Set",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Determine lesson type based on checkbox selection
        String lessonType;
        if (onlyMajorCheckBox.isSelected()) {
            lessonType = "MajorSubject";
        } else if (onlyElectiveCheckBox.isSelected()) {
            lessonType = "ElectiveSubject";
        } else {
            lessonType = "Auto";
        }

        log.info("[UI] Starting learning plugin.swisskit.hpl.worker, lessonType: {}", lessonType);
        statusLabel.setText("LearningStatus: Learning...");
        currentWorker = new HappyLearningWorker(key, majorSubjiectPB, electiveSubjectPB,
                "Auto".equals(lessonType) ? null : lessonType, startBt, stopBt,
                subjectIdTextField, subjectName, statusLabel, classHoursField);
        currentWorker.execute();
        startBt.setEnabled(false);
        stopBt.setEnabled(true);
        log.info("[UI] Learning plugin.swisskit.hpl.worker started, UI buttons updated");
    }

    private void stopBtAction(ActionEvent e) {
        if (currentWorker != null && !currentWorker.isDone()) {
            log.info("[UI] Stop button clicked, cancelling plugin.swisskit.hpl.worker");
            currentWorker.cancel(true);
            statusLabel.setText("LearningStatus: Stopped");
            startBt.setEnabled(true);
            stopBt.setEnabled(false);
            log.info("[UI] Worker cancelled, UI buttons updated");
        }
    }

    private void createUIComponents() {
        majorSubjiectPB = new GradientProgressBar();
        electiveSubjectPB = new GradientProgressBar();
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        createUIComponents();

        learningPanel = new JPanel();
        label4 = new JLabel();
        configFilePath = new JTextField();
        uploadBt = new JButton();
        label1 = new JLabel();
        passKey = new JTextField();
        setPassKeyBt = new JButton();
        label2 = new JLabel();
        label3 = new JLabel();
        electiveSubjectPB = new JProgressBar();
        panel1 = new JPanel();
        onlyMajorCheckBox = new JCheckBox();
        onlyElectiveCheckBox = new JCheckBox();
        startBt = new JButton();
        stopBt = new JButton();
        statusLabel = new JLabel();
        subjectInfo = new JPanel();
        label5 = new JLabel();
        subjectIdTextField = new JTextField();
        label7 = new JLabel();
        classHoursField = new JTextField();
        label6 = new JLabel();
        subjectName = new JTextField();
        skipClassBt = new JButton();

        //======== learningPanel ========
        {
            learningPanel.setLayout(new MigLayout(
                "hidemode 3",
                // columns
                "[fill]" +
                "[417,fill]" +
                "[fill]",
                // rows
                "[]" +
                "[]" +
                "[23]" +
                "[27]" +
                "[]" +
                "[]" +
                "[]" +
                "[]" +
                "[]"));

            //---- label4 ----
            label4.setText("ConfigFile");
            learningPanel.add(label4, "cell 0 0");

            //---- configFilePath ----
            configFilePath.setEditable(false);
            learningPanel.add(configFilePath, "cell 1 0");

            //---- uploadBt ----
            uploadBt.setText("UploadConfig");
            uploadBt.addActionListener(e -> uploadBtAction(e));
            learningPanel.add(uploadBt, "cell 2 0");

            //---- label1 ----
            label1.setText("PassKey");
            learningPanel.add(label1, "cell 0 1");
            learningPanel.add(passKey, "cell 1 1");

            //---- setPassKeyBt ----
            setPassKeyBt.setText("SetPassKey");
            setPassKeyBt.addActionListener(e -> setPassKeyBtAction(e));
            learningPanel.add(setPassKeyBt, "cell 2 1");

            //---- label2 ----
            label2.setText("MajorSubject");
            learningPanel.add(label2, "cell 0 2");
            learningPanel.add(majorSubjiectPB, "cell 1 2 2 1");

            //---- label3 ----
            label3.setText("ElectiveSubject");
            learningPanel.add(label3, "cell 0 3");
            learningPanel.add(electiveSubjectPB, "cell 1 3 2 1");

            //======== panel1 ========
            {
                panel1.setLayout(new MigLayout(
                    "hidemode 3",
                    // columns
                    "[fill]" +
                    "[fill]",
                    // rows
                    "[]" +
                    "[]" +
                    "[]"));

                //---- onlyMajorCheckBox ----
                onlyMajorCheckBox.setText("OnlyMajorSubject");
                panel1.add(onlyMajorCheckBox, "cell 0 0");

                //---- onlyElectiveCheckBox ----
                onlyElectiveCheckBox.setText("OnlyElectiveSubject");
                panel1.add(onlyElectiveCheckBox, "cell 1 0");
            }
            learningPanel.add(panel1, "cell 0 4 3 1");

            //---- startBt ----
            startBt.setText("StartHappy");
            startBt.addActionListener(e -> startBtAction(e));
            learningPanel.add(startBt, "cell 1 5");

            //---- stopBt ----
            stopBt.setText("UnHappy");
            stopBt.addActionListener(e -> stopBtAction(e));
            learningPanel.add(stopBt, "cell 1 6");

            //---- statusLabel ----
            statusLabel.setText("LearningStatus: Idle");
            learningPanel.add(statusLabel, "cell 0 7 3 1,align center center,grow 0 0");

            //======== subjectInfo ========
            {
                subjectInfo.setLayout(new MigLayout(
                    "hidemode 3",
                    // columns
                    "[fill]" +
                    "[153,fill]" +
                    "[fill]" +
                    "[fill]" +
                    "[245,fill]",
                    // rows
                    "[]" +
                    "[]" +
                    "[]" +
                    "[]" +
                    "[]"));

                //---- label5 ----
                label5.setText("SubjectId\uff1a");
                subjectInfo.add(label5, "cell 0 0");

                //---- subjectIdTextField ----
                subjectIdTextField.setEditable(false);
                subjectInfo.add(subjectIdTextField, "cell 1 0 2 1");

                //---- label7 ----
                label7.setText("ClassHours");
                subjectInfo.add(label7, "cell 3 0");

                //---- classHoursField ----
                classHoursField.setEditable(false);
                subjectInfo.add(classHoursField, "cell 4 0");

                //---- label6 ----
                label6.setText("SubjectName:");
                subjectInfo.add(label6, "cell 0 1");

                //---- subjectName ----
                subjectName.setEditable(false);
                subjectInfo.add(subjectName, "cell 1 1 4 1");

                //---- skipClassBt ----
                skipClassBt.setText("SkipClass");
                skipClassBt.setEnabled(false);
                subjectInfo.add(skipClassBt, "cell 0 2 5 1");
            }
            learningPanel.add(subjectInfo, "cell 0 8 3 1");
        }
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel learningPanel;
    private JLabel label4;
    private JTextField configFilePath;
    private JButton uploadBt;
    private JLabel label1;
    private JTextField passKey;
    private JButton setPassKeyBt;
    private JLabel label2;
    private JProgressBar majorSubjiectPB;
    private JLabel label3;
    private JProgressBar electiveSubjectPB;
    private JPanel panel1;
    private JCheckBox onlyMajorCheckBox;
    private JCheckBox onlyElectiveCheckBox;
    private JButton startBt;
    private JButton stopBt;
    private JLabel statusLabel;
    private JPanel subjectInfo;
    private JLabel label5;
    private JTextField subjectIdTextField;
    private JLabel label7;
    private JTextField classHoursField;
    private JLabel label6;
    private JTextField subjectName;
    private JButton skipClassBt;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
