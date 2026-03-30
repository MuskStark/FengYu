/*
 * Created by JFormDesigner on Mon Mar 09 10:15:27 CST 2026
 */

package plugin.swisskitj;

import java.awt.event.*;
import java.nio.file.Path;
import javax.swing.*;

import fan.summer.annoattion.SwissKitPage;
import fan.summer.api.KitPage;
import fan.summer.ui.components.GradientProgressBar;
import net.miginfocom.swing.*;

/**
 * @author summer
 */
@SwissKitPage(menuName = "QccToExcel", menuTooltip = "QccToExcel", order = 88)
public class QccToExcel implements KitPage {
    public QccToExcel() {
        initComponents();
    }

    private void qccToexcelAction(ActionEvent e) {
        Path path = Path.of(textField2.getText(), "result.xlsx");
        new QccToExcelWorker(textField1.getText(), path.toString(), progressBar1).execute();
    }

    private void choiceQccfile(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setDialogTitle("Select Output Directory");
        int result = fileChooser.showOpenDialog(qccToExcel);
        if (result == JFileChooser.APPROVE_OPTION) {
            textField1.setText(fileChooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void choiceOutputAction(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Select Output Directory");
        int result = fileChooser.showOpenDialog(qccToExcel);
        if (result == JFileChooser.APPROVE_OPTION) {
            textField2.setText(fileChooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void createUIComponents() {
        progressBar1 = new GradientProgressBar();
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        createUIComponents();

        qccToExcel = new JPanel();
        button1 = new JButton();
        textField1 = new JTextField();
        button3 = new JButton();
        textField2 = new JTextField();
        button2 = new JButton();

        //======== qccToExcel ========
        {
            qccToExcel.setLayout(new MigLayout(
                "hidemode 3",
                // columns
                "[fill]" +
                "[263,fill]",
                // rows
                "[]" +
                "[]" +
                "[]" +
                "[]"));

            //---- button1 ----
            button1.setText("ChoiceFile");
            button1.addActionListener(e -> choiceQccfile(e));
            qccToExcel.add(button1, "cell 0 0");

            //---- textField1 ----
            textField1.setEditable(false);
            qccToExcel.add(textField1, "cell 1 0");

            //---- button3 ----
            button3.setText("ChoiceOutPutPath");
            button3.addActionListener(e -> choiceOutputAction(e));
            qccToExcel.add(button3, "cell 0 1");

            //---- textField2 ----
            textField2.setEditable(false);
            qccToExcel.add(textField2, "cell 1 1");

            //---- button2 ----
            button2.setText("QccToExcel");
            button2.addActionListener(e -> qccToexcelAction(e));
            qccToExcel.add(button2, "cell 0 2 2 1");
            qccToExcel.add(progressBar1, "cell 0 3 2 1");
        }
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    @Override
    public JPanel getPanel() {
        return qccToExcel;
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel qccToExcel;
    private JButton button1;
    private JTextField textField1;
    private JButton button3;
    private JTextField textField2;
    private JButton button2;
    private JProgressBar progressBar1;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
