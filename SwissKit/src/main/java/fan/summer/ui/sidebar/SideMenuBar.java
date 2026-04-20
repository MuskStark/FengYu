package fan.summer.ui.sidebar;

import fan.summer.scaner.SwissKitPageScaner;
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
 * Click to navigate, drag to reorder.
 *
 * @author summer
 */
public class SideMenuBar extends JPanel {

    private static final int MENU_WIDTH = 160;
    private static final Color SELECTED_BG = new Color(0x2D, 0x2D, 0x2D);
    private static final Color SELECTED_TEXT = new Color(0xBB, 0x86, 0xFC);
    private static final Color HOVER_BG = new Color(0xE8, 0xE8, 0xE8);
    private static final Color DRAG_HIGHLIGHT = new Color(0xCC, 0xCC, 0xDD);

    /**
     * Minimum pixel movement to be considered a drag (not a click)
     */
    private static final int DRAG_THRESHOLD = 8;

    private List<Object> pages;
    private final List<JLabel> menuItems;
    private final JPanel contentPanel;
    private int selectedIndex = -1;

    private final JLabel titleLabel;
    private final JPanel menuContainer;
    private JPanel menuItemsPanel;

    // ── Drag state ────────────────────────────────────────────────────────────
    private int draggedItemIndex = -1;
    private Point dragStartPoint = null;
    private boolean isDragging = false;

    public SideMenuBar(List<Object> pages, JPanel contentPanel) {
        this.pages = pages;
        this.contentPanel = contentPanel;
        this.menuItems = new ArrayList<>();

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

        menuContainer = new JPanel(new BorderLayout());
        menuContainer.setBackground(UIUtils.LIGHT_GRAY);

        rebuildMenu();

        JLabel footerLabel = new JLabel("© 2025 Summer", SwingConstants.CENTER);
        footerLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        footerLabel.setForeground(new Color(0x90, 0x90, 0x90));
        footerLabel.setBorder(new EmptyBorder(10, 0, 10, 0));

        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.add(footerLabel, BorderLayout.SOUTH);
        footerPanel.setBackground(UIUtils.LIGHT_GRAY);

        add(titlePanel, BorderLayout.NORTH);
        add(menuContainer, BorderLayout.CENTER);
        add(footerPanel, BorderLayout.SOUTH);
    }

    // ── Menu construction ─────────────────────────────────────────────────────

    public void rebuildMenu() {
        menuContainer.removeAll();
        menuItems.clear();

        menuItemsPanel = new JPanel();
        menuItemsPanel.setLayout(new BoxLayout(menuItemsPanel, BoxLayout.Y_AXIS));
        menuItemsPanel.setBackground(UIUtils.LIGHT_GRAY);

        for (int i = 0; i < pages.size(); i++) {
            JLabel item = createMenuItem(pages.get(i), i);
            menuItems.add(item);
            menuItemsPanel.add(wrapWithDragSupport(item, i));
        }

        menuContainer.add(menuItemsPanel, BorderLayout.NORTH);
        menuContainer.revalidate();
        menuContainer.repaint();
    }

    private JLabel createMenuItem(Object page, int index) {
        String menuName = SwissKitPageScaner.getMenuName(page);
        String iconPath = SwissKitPageScaner.getMenuIconPath(page);
        Icon menuIcon = iconPath != null && !iconPath.isEmpty() ? new ImageIcon(iconPath) : null;
        JLabel label = new JLabel(menuName, menuIcon, SwingConstants.CENTER);
        label.setFont(new Font("SansSerif", Font.PLAIN, 13));
        label.setForeground(UIUtils.TEXT_COLOR);
        label.setBackground(UIUtils.LIGHT_GRAY);
        label.setOpaque(true);
        label.setBorder(new EmptyBorder(12, 10, 12, 10));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.putClientProperty("pageIndex", index);

        String tip = SwissKitPageScaner.getMenuTooltip(page);
        if (tip != null) label.setToolTipText(tip);

        return label;
    }

    /**
     * Wrap a menu label in a full-width panel and attach all mouse logic.
     * Key fix: listeners go on BOTH the panel AND the label so events
     * are never swallowed by the child component.
     */
    private JPanel wrapWithDragSupport(JLabel label, int index) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(UIUtils.LIGHT_GRAY);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(label, gbc);

        // Shared handler — attached to both panel and label
        MouseAdapter handler = new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                dragStartPoint = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), menuItemsPanel);
                draggedItemIndex = index;
                isDragging = false;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                boolean wasDragging = isDragging;
                int dragFromIndex = draggedItemIndex;
                Point releasePoint = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), menuItemsPanel);

                // Reset state before any UI change
                dragStartPoint = null;
                draggedItemIndex = -1;
                isDragging = false;
                clearDragHighlight();

                if (wasDragging && dragFromIndex >= 0) {
                    int dropIndex = getDropIndex(releasePoint);
                    if (dropIndex != dragFromIndex && dropIndex != dragFromIndex + 1) {
                        reorderPages(dragFromIndex, dropIndex);
                    }
                } else {
                    // Plain click → navigate
                    selectPage(index);
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                if (!isDragging && index != selectedIndex) {
                    label.setBackground(HOVER_BG);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!isDragging && index != selectedIndex) {
                    label.setBackground(UIUtils.LIGHT_GRAY);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStartPoint == null) return;

                Point current = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), menuItemsPanel);
                int dx = Math.abs(current.x - dragStartPoint.x);
                int dy = Math.abs(current.y - dragStartPoint.y);

                if (!isDragging && (dx > DRAG_THRESHOLD || dy > DRAG_THRESHOLD)) {
                    isDragging = true;
                }

                if (isDragging) {
                    updateDragHighlight(current, draggedItemIndex);
                }
            }
        };

        panel.addMouseListener(handler);
        panel.addMouseMotionListener(handler);
        // Also attach to label so clicks/drags on the text are captured
        label.addMouseListener(handler);
        label.addMouseMotionListener(handler);

        return panel;
    }

    // ── Drag visuals ──────────────────────────────────────────────────────────

    private void updateDragHighlight(Point mouseInPanel, int draggedIndex) {
        int dropIndex = getDropIndex(mouseInPanel);

        for (int i = 0; i < menuItems.size(); i++) {
            JLabel lbl = menuItems.get(i);
            if (i == draggedIndex) {
                lbl.setBackground(DRAG_HIGHLIGHT);
            } else if (i == selectedIndex) {
                lbl.setBackground(SELECTED_BG);
            } else {
                lbl.setBackground(UIUtils.LIGHT_GRAY);
            }
        }

        // Highlight insertion target
        if (dropIndex >= 0 && dropIndex < menuItems.size() && dropIndex != draggedIndex) {
            menuItems.get(dropIndex).setBackground(DRAG_HIGHLIGHT);
        }
    }

    private void clearDragHighlight() {
        for (int i = 0; i < menuItems.size(); i++) {
            menuItems.get(i).setBackground(i == selectedIndex ? SELECTED_BG : UIUtils.LIGHT_GRAY);
        }
    }

    // ── Drop index calculation ─────────────────────────────────────────────────

    /**
     * Returns the index at which the dragged item should be inserted.
     * Compares mouse Y against the midpoint of each row.
     */
    private int getDropIndex(Point locationInPanel) {
        int count = menuItemsPanel.getComponentCount();
        if (count == 0) return 0;

        int y = locationInPanel.y;
        int cumulative = 0;

        for (int i = 0; i < count; i++) {
            Component comp = menuItemsPanel.getComponent(i);
            int h = comp.getHeight();
            // Insert before this row when cursor is in its upper half
            if (y < cumulative + h / 2) {
                return i;
            }
            cumulative += h;
        }
        return count; // insert at end
    }

    // ── Reorder logic ─────────────────────────────────────────────────────────

    private void reorderPages(int fromIndex, int toIndex) {
        // Clamp to valid insertion range
        toIndex = Math.max(0, Math.min(toIndex, pages.size()));

        // Remember which page was selected
        Object selectedPage = (selectedIndex >= 0 && selectedIndex < pages.size())
                ? pages.get(selectedIndex) : null;

        Object moved = pages.remove(fromIndex);
        // Adjust insertion index after removal
        int insertAt = (toIndex > fromIndex) ? toIndex - 1 : toIndex;
        insertAt = Math.max(0, Math.min(insertAt, pages.size()));
        pages.add(insertAt, moved);

        SwissKitPageScaner.saveMenuOrder(pages);
        rebuildMenu();

        // Restore selection by page identity, not stale index
        if (selectedPage != null) {
            int newIdx = pages.indexOf(selectedPage);
            if (newIdx >= 0) {
                selectedIndex = -1; // force refresh
                selectPage(newIdx);
                return;
            }
        }
        selectPage(0);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void selectPage(int index) {
        if (index < 0 || index >= pages.size()) return;

        // Deselect previous
        if (selectedIndex >= 0 && selectedIndex < menuItems.size()) {
            menuItems.get(selectedIndex).setBackground(UIUtils.LIGHT_GRAY);
            menuItems.get(selectedIndex).setForeground(UIUtils.TEXT_COLOR);
        }

        selectedIndex = index;
        menuItems.get(index).setBackground(SELECTED_BG);
        menuItems.get(index).setForeground(SELECTED_TEXT);

        if (contentPanel != null) {
            contentPanel.removeAll();
            contentPanel.add(SwissKitPageScaner.getPanel(pages.get(index)), BorderLayout.CENTER);
            contentPanel.revalidate();
            contentPanel.repaint();
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setTitle(String title) {
        titleLabel.setText(title);
    }

    public void addPage(Object page) {
        pages.add(page);
        rebuildMenu();
        SwissKitPageScaner.saveMenuOrder(pages);
    }

    public void removePage(int index) {
        if (index >= 0 && index < pages.size()) {
            pages.remove(index);
            rebuildMenu();
            SwissKitPageScaner.saveMenuOrder(pages);
        }
    }

    public void setPages(List<Object> newPages) {
        this.pages = newPages;
        rebuildMenu();
    }
}