package fan.summer.ui.sidebar;

import fan.summer.api.KitPage;
import fan.summer.utils.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Side menu bar component.
 * Dynamically generates menu items based on registered KitPage implementations.
 * Provides navigation between different tool pages in the application.
 *
 * @author summer
 */
public class SideMenuBar extends JPanel {

    private static final int MENU_WIDTH = 160;
    private static final Color SELECTED_BG = new Color(0x2D, 0x2D, 0x2D);
    private static final Color SELECTED_TEXT = new Color(0xBB, 0x86, 0xFC);
    private static final Color HOVER_BG = new Color(0xE8, 0xE8, 0xE8);

    private final List<KitPage> pages;
    private final List<JLabel> menuItems;
    private final JPanel contentPanel;
    private int selectedIndex;

    private final JLabel titleLabel;
    private final JPanel menuContainer;

    /**
     * Create side menu bar
     *
     * @param pages        List of pages
     * @param contentPanel Content panel (for switching pages)
     */
    public SideMenuBar(List<KitPage> pages, JPanel contentPanel) {
        this.pages = pages;
        this.contentPanel = contentPanel;
        this.menuItems = new ArrayList<>();
        this.selectedIndex = -1;

        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(MENU_WIDTH, 0));
        setBackground(UIUtils.LIGHT_GRAY);

        // Title
        titleLabel = new JLabel("Swiss Kit", SwingConstants.CENTER);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
        titleLabel.setForeground(UIUtils.TEXT_COLOR);
        titleLabel.setBorder(new EmptyBorder(20, 10, 20, 10));

        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.add(titleLabel, BorderLayout.NORTH);
        titlePanel.setBackground(UIUtils.LIGHT_GRAY);

        // Menu container - use BorderLayout for top alignment without filling
        menuContainer = new JPanel(new BorderLayout(0, 0));
        menuContainer.setBackground(UIUtils.LIGHT_GRAY);

        // Build menu items
        rebuildMenu();

        // Footer copyright
        JLabel footerLabel = new JLabel("© 2025 Summer", SwingConstants.CENTER);
        footerLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        footerLabel.setForeground(new Color(0x90, 0x90, 0x90));
        footerLabel.setBorder(new EmptyBorder(10, 0, 10, 0));

        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.add(footerLabel, BorderLayout.SOUTH);
        footerPanel.setBackground(UIUtils.LIGHT_GRAY);

        // Assemble
        add(titlePanel, BorderLayout.NORTH);
        add(menuContainer, BorderLayout.CENTER);
        add(footerPanel, BorderLayout.SOUTH);
    }

    /**
     * Rebuild menu (for dynamic menu updates)
     */
    public void rebuildMenu() {
        menuContainer.removeAll();
        menuItems.clear();

        // Create a vertical panel for menu items
        JPanel menuItemsPanel = new JPanel();
        menuItemsPanel.setLayout(new BoxLayout(menuItemsPanel, BoxLayout.Y_AXIS));
        menuItemsPanel.setBackground(UIUtils.LIGHT_GRAY);

        for (int i = 0; i < pages.size(); i++) {
            KitPage page = pages.get(i);
            JLabel menuItem = createMenuItem(page, i);

            // Wrap in centered panel
            JPanel menuItemPanel = new JPanel(new GridBagLayout());
            menuItemPanel.setBackground(UIUtils.LIGHT_GRAY);
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            menuItemPanel.add(menuItem, gbc);

            menuItems.add(menuItem);
            menuItemsPanel.add(menuItemPanel);
        }

        // Add to NORTH position for top alignment without filling
        menuContainer.add(menuItemsPanel, BorderLayout.NORTH);

        menuContainer.revalidate();
        menuContainer.repaint();
    }

    /**
     * Create menu item
     */
    private JLabel createMenuItem(KitPage page, int index) {
        String menuName = page.getMenuName();
        Icon menuIcon = page.getMenuIcon();
        String tooltip = page.getMenuTooltip();

        JLabel label = new JLabel(menuName, menuIcon, SwingConstants.CENTER);
        label.setFont(new Font("SansSerif", Font.PLAIN, 13));
        label.setForeground(UIUtils.TEXT_COLOR);
        label.setBackground(UIUtils.LIGHT_GRAY);
        label.setOpaque(true);
        label.setBorder(new EmptyBorder(12, 10, 12, 10));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setFocusable(true);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.CENTER);

        if (tooltip != null) {
            label.setToolTipText(tooltip);
        }

        // Click event
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                selectPage(index);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                if (index != selectedIndex) {
                    label.setBackground(HOVER_BG);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (index != selectedIndex) {
                    label.setBackground(UIUtils.LIGHT_GRAY);
                }
            }
        });

        return label;
    }

    /**
     * Select page
     */
    public void selectPage(int index) {
        if (index < 0 || index >= pages.size()) {
            return;
        }

        // Update selected state
        int previousIndex = selectedIndex;
        selectedIndex = index;

        // Update menu item styles
        if (previousIndex >= 0 && previousIndex < menuItems.size()) {
            JLabel prevLabel = menuItems.get(previousIndex);
            prevLabel.setBackground(UIUtils.LIGHT_GRAY);
            prevLabel.setForeground(UIUtils.TEXT_COLOR);
        }

        JLabel selectedLabel = menuItems.get(index);
        selectedLabel.setBackground(SELECTED_BG);
        selectedLabel.setForeground(SELECTED_TEXT);

        // Switch content panel
        if (contentPanel != null) {
            contentPanel.removeAll();
            contentPanel.add(pages.get(index).getPanel(), BorderLayout.CENTER);
            contentPanel.revalidate();
            contentPanel.repaint();
        }
    }

    /**
     * Get current selected page index
     */
    public int getSelectedIndex() {
        return selectedIndex;
    }

    /**
     * Set title
     */
    public void setTitle(String title) {
        titleLabel.setText(title);
    }

    /**
     * Add page
     */
    public void addPage(KitPage page) {
        pages.add(page);
        JLabel menuItem = createMenuItem(page, pages.size() - 1);
        menuItems.add(menuItem);
        menuContainer.add(menuItem);
        menuContainer.revalidate();
        menuContainer.repaint();
    }

    /**
     * Remove page
     */
    public void removePage(int index) {
        if (index >= 0 && index < pages.size()) {
            pages.remove(index);
            menuItems.get(index).setVisible(false);
            menuContainer.remove(index);
            menuItems.remove(index);
            menuContainer.revalidate();
            menuContainer.repaint();
        }
    }

    /**
     * Set page list (will rebuild menu)
     */
    public void setPages(List<KitPage> newPages) {
        pages.clear();
        pages.addAll(newPages);
        rebuildMenu();
    }
}
