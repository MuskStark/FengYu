/*
 * Created by JFormDesigner on Tue Apr 21 11:15:25 CST 2026
 */

package fan.summer.ui.sidebar;

import com.jgoodies.forms.factories.*;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;

/**
 * @author summer
 */
public class SideMenuBarUi extends JPanel {
    public SideMenuBarUi() {
        initComponents();
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        DefaultComponentFactory compFactory = DefaultComponentFactory.getInstance();
        label1 = new JLabel();
        separator1 = compFactory.createSeparator("Plugin");
        panel1 = new JPanel();
        label2 = new JLabel();

        //======== this ========
        setLayout(new MigLayout(
            "hidemode 3",
            // columns
            "[120,fill]",
            // rows
            "[]" +
            "[149]" +
            "[]" +
            "[456]" +
            "[]"));

        //---- label1 ----
        label1.setText("SwissKitJ");
        add(label1, "cell 0 0,alignx center,growx 0");
        add(separator1, "cell 0 2,align center center,grow 0 0");

        //======== panel1 ========
        {
            panel1.setLayout(new MigLayout(
                "hidemode 3,aligny top",
                // columns
                "[fill]" +
                "[fill]" +
                "[fill]",
                // rows
                "[]" +
                "[41]" +
                "[107]"));
        }
        add(panel1, "cell 0 3");

        //---- label2 ----
        label2.setText("Summer");
        add(label2, "cell 0 4,alignx center,growx 0");
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JLabel label1;
    private JComponent separator1;
    private JPanel panel1;
    private JLabel label2;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
