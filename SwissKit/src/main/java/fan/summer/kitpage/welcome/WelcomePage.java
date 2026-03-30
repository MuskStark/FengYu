package fan.summer.kitpage.welcome;

import fan.summer.annoattion.SwissKitPage;
import fan.summer.api.KitPage;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

/**
 * Welcome page with quick access to documentation
 *
 * @author phoebej
 * @version 1.00
 * @date 2026/3/1
 */
@SwissKitPage(order = 0)
public class WelcomePage implements KitPage {
    private static final Logger logger = LoggerFactory.getLogger(WelcomePage.class);

    /**
     * Creates a new WelcomePage and initializes the documentation link listener.
     * Clicking the documentation label will open the SwissKitJ user manual in the browser.
     */
    public WelcomePage() {
        initComponents();
        doc.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new URI("https://muskstark.github.io/SwissKitJ"));
                } catch (Exception ex) {
                    logger.error("Failed to open documentation URL", ex);
                }
            }
        });
    }

    /**
     * Returns the main panel for this welcome page.
     *
     * @return the welcome JPanel
     */
    @Override
    public JPanel getPanel() {
        return welcome;
    }

    /**
     * Returns the menu display name for this page.
     *
     * @return menu name with emoji prefix
     */
    @Override
    public String getMenuName() {
        return "🏠 Welcome";
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        welcome = new JPanel();
        var label1 = new JLabel();
        var label2 = new JLabel();
        doc = new JLabel();

        //======== welcome ========
        {
            welcome.setBackground(Color.white);
            welcome.setForeground(Color.white);
            welcome.setLayout(new MigLayout(
                "insets 0,hidemode 3,align center center,gap 10 5",
                // columns
                "[501,fill]",
                // rows
                "[131,fill]" +
                "[92,fill]" +
                "[fill]"));

            //---- label1 ----
            label1.setFont(new Font(Font.SANS_SERIF, label1.getFont().getStyle(), 22));
            label1.setText("Welcome to Swiss Kit Toolbox");
            welcome.add(label1, "cell 0 0,align center bottom,grow 0 0");

            //---- label2 ----
            label2.setFont(new Font(Font.SANS_SERIF, label2.getFont().getStyle(), 16));
            label2.setText("Select a tool from the left menu");
            label2.setVerticalAlignment(SwingConstants.BOTTOM);
            welcome.add(label2, "cell 0 1,align center top,grow 0 0");

            //---- doc ----
            doc.setBackground(new Color(0x69aea0));
            doc.setFont(new Font(Font.SANS_SERIF, doc.getFont().getStyle(), doc.getFont().getSize()));
            doc.setForeground(new Color(0xd9b11b));
            doc.setText("<html><a href=''>Document</a></html>");
            welcome.add(doc, "cell 0 2,align center center,grow 0 0");
        }
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel welcome;
    private JLabel doc;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
