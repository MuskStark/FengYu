package fan.summer.ui.home;

import com.formdev.flatlaf.FlatIntelliJLaf;
import fan.summer.annoattion.SwissKitPage;
import fan.summer.plugin.PluginLoader;
import fan.summer.scaner.SwissKitPageScaner;
import fan.summer.ui.sidebar.SideMenuBar;
import fan.summer.utils.AppInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    private static HomePage instance;

    private SideMenuBar sideMenuBar;
    private SwissKitPageScaner.ScannedPages scannedPages;

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
        scannedPages = SwissKitPageScaner.scan();

        // Content panel
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        contentPanel.setBackground(Color.WHITE);

        // Side menu bar
        sideMenuBar = new SideMenuBar(scannedPages, contentPanel);

        // Set singleton instance for hot-deploy coordination
        instance = this;

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

        logger.info("Application started with {} pages", scannedPages.totalCount());
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

    /**
     * Returns the singleton HomePage instance.
     *
     * @return the HomePage instance, or null if not yet initialized
     */
    public static HomePage getInstance() {
        return instance;
    }

    /**
     * Refreshes the sidebar menu after plugin hot-deploy/reload/uninstall.
     *
     * @param newPluginPages KitPages from deployed/reloaded plugin (pass null for uninstall)
     */
    public void refreshSidebar(List<Object> newPluginPages) {
        if (sideMenuBar == null) {
            return;
        }

        if (newPluginPages != null && !newPluginPages.isEmpty()) {
            // Deploy/reload: merge new plugin pages into current plugin list
            List<Object> currentPlugins = new ArrayList<>(scannedPages.pluginPages());
            List<String> newClassNames = newPluginPages.stream()
                    .map(p -> p.getClass().getName())
                    .collect(Collectors.toList());

            currentPlugins.removeIf(p -> newClassNames.contains(p.getClass().getName()));
            currentPlugins.addAll(newPluginPages);

            // Filter disabled
            currentPlugins.removeIf(p -> !PluginLoader.isPageEnabled(p));

            // Rebuild ScannedPages with merged plugins
            SwissKitPageScaner.ScannedPages merged = new SwissKitPageScaner.ScannedPages(
                    scannedPages.builtinPages(), currentPlugins);
            SwissKitPageScaner.applySavedOrder(merged);
            scannedPages = merged;
        } else {
            // Uninstall or refresh: re-scan everything
            scannedPages = SwissKitPageScaner.scan();
        }

        sideMenuBar.setPages(scannedPages);
    }

    /**
     * @deprecated Use {@link #refreshSidebar(List)} instead.
     */
    @Deprecated
    public void refreshSidebar() {
        refreshSidebar(null);
    }
}
