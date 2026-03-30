package fan.summer.ui.home;

import com.formdev.flatlaf.FlatIntelliJLaf;
import fan.summer.api.KitPage;
import fan.summer.scaner.SwissKitPageScaner;
import fan.summer.ui.sidebar.SideMenuBar;
import fan.summer.utils.AppInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URL;
import java.util.List;

/**
 * Home page class that serves as the main application container.
 * Manages the application window, side menu bar, and automatic page discovery.
 *
 * @author phoebej
 * @version 1.00
 * @date 2026/3/1
 */
public class HomePage {

    private static final Logger logger = LoggerFactory.getLogger(HomePage.class);

    private SideMenuBar sideMenuBar;
    private List<KitPage> pages;

    /**
     * Initializes and displays the main application window.
     * Sets up the FlatLaf theme, discovers all KitPage plugins, creates the side menu,
     * and displays the first registered page by default.
     * Should be called once from the EDT after DatabaseInit completes successfully.
     */
    public void init() {
        FlatIntelliJLaf.setup();

        UIManager.put("ProgressBar.arc", 10);
        UIManager.put("ProgressBar.foreground", new Color(64, 158, 255));
        UIManager.put("ProgressBar.background", new Color(240, 240, 240));
        UIManager.put("ProgressBar.selectionForeground", Color.WHITE);
        UIManager.put("ProgressBar.selectionBackground", Color.GRAY);

        JFrame frame = new JFrame(AppInfo.getFullName());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);

        // Set application icon
        setAppIcon(frame);

        // Initialize pages using KitPageScanner
        pages = SwissKitPageScaner.scan();

        // Content panel
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        contentPanel.setBackground(Color.WHITE);

        // Side menu bar
        sideMenuBar = new SideMenuBar(pages, contentPanel);

        // Main panel
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(sideMenuBar, BorderLayout.WEST);
        mainPanel.add(contentPanel, BorderLayout.CENTER);

        frame.add(mainPanel);

        // Select the first page by default
        sideMenuBar.selectPage(0);

        frame.setSize(new Dimension(900, 600));
        frame.setMinimumSize(new Dimension(800, 500));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        
        logger.info("Application started with {} pages", pages.size());
    }

    /**
     * Set application icon
     * Place icon file in resources directory, supports: icon.png, icon.jpg, app.png
     */
    private void setAppIcon(JFrame frame) {
        String[] iconPaths = {"/icon.png", "/icon.jpg", "/app.png"};

        for (String path : iconPaths) {
            URL url = getClass().getResource(path);
            if (url != null) {
                frame.setIconImage(new ImageIcon(url).getImage());
                return;
            }
        }
        // Use default when icon file not found
        logger.info("Application icon not found. Please add icon.png to resources directory");
    }
}
