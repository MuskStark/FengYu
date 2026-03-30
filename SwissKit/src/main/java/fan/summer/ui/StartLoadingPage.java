/*
 * Created by JFormDesigner on Tue Mar 03 15:53:51 CST 2026
 */

package fan.summer.ui;

import java.awt.*;
import javax.swing.*;
import net.miginfocom.swing.*;

/**
 * Splash screen window displayed during application initialization.
 * Shows a loading message while the database and UI are being initialized.
 *
 * @author summer
 */
public class StartLoadingPage {
    /**
     * Creates a new StartLoadingPage and initializes the splash screen components.
     */
    public StartLoadingPage() {
        initComponents();
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        startLoading = new JWindow();
        panel1 = new JPanel();
        label1 = new JLabel();

        //======== startLoading ========
        {
            var startLoadingContentPane = startLoading.getContentPane();
            startLoadingContentPane.setLayout(new MigLayout(
                "hidemode 3",
                // columns
                "[fill]" +
                "[fill]" +
                "[fill]" +
                "[fill]" +
                "[fill]" +
                "[fill]" +
                "[fill]" +
                "[fill]" +
                "[fill]" +
                "[fill]" +
                "[fill]",
                // rows
                "[]" +
                "[]" +
                "[]" +
                "[]" +
                "[]"));

            //======== panel1 ========
            {
                panel1.setLayout(new MigLayout(
                    "hidemode 3",
                    // columns
                    "[47,fill]" +
                    "[244,fill]",
                    // rows
                    "[]" +
                    "[]" +
                    "[]"));

                //---- label1 ----
                label1.setText("Swisskit Loading.....");
                label1.setForeground(new Color(0xff8e53));
                panel1.add(label1, "cell 0 1 2 1,align center center,grow 0 0");
            }
            startLoadingContentPane.add(panel1, "cell 0 0 11 5,grow");
            startLoading.pack();
            startLoading.setLocationRelativeTo(startLoading.getOwner());
        }
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    /**
     * Returns the JWindow instance for display control.
     *
     * @return the splash screen JWindow
     */
    public JWindow getWindow() {
        return startLoading;
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JWindow startLoading;
    private JPanel panel1;
    private JLabel label1;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
