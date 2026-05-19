package fan.summer.utils;

import javax.swing.*;
import java.awt.*;

/**
 * UI common utility class providing reusable UI components and constants.
 * Contains color definitions and factory methods for common UI elements.
 *
 * @author summer
 * @version 1.00
 */
public class UIUtils {

    /** Primary accent color for the application */
    public static final Color PRIMARY_COLOR = new Color(0xBB, 0x86, 0xFC);

    /** Default text color */
    public static final Color TEXT_COLOR = new Color(0x60, 0x60, 0x60);

    /** Light gray background color */
    public static final Color LIGHT_GRAY = new Color(0xF3, 0xF3, 0xF3);

    /** Divider/border color */
    public static final Color DIVIDER_COLOR = new Color(0xE0, 0xE0, 0xE0);

    /**
     * Creates a section panel with a title label above the content.
     *
     * @param title   the section title
     * @param content the content component
     * @return a JPanel containing the title and content
     */
    public static JPanel createSectionPanel(String title, JComponent content) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);
        
        JLabel label = new JLabel(title);
        label.setFont(new Font("SansSerif", Font.PLAIN, 13));
        label.setForeground(TEXT_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        panel.add(label);
        panel.add(Box.createVerticalStrut(8));
        panel.add(content);
        
        return panel;
    }
    
    /**
     * Creates a file selection panel with a text field and button.
     *
     * @param label     the label text (not currently used)
     * @param textField the text field for displaying the selected path
     * @param button    the button for opening file chooser
     * @return a JPanel with the file picker components
     */
    public static JPanel createFilePickerPanel(String label, JTextField textField, JButton button) {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.add(textField, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }

    /**
     * Creates a folder selection panel with a non-editable text field and button.
     *
     * @param label     the label text (not currently used)
     * @param textField the text field for displaying the selected folder path
     * @param button    the button for opening folder chooser
     * @return a JPanel with the folder picker components
     */
    public static JPanel createFolderPickerPanel(String label, JTextField textField, JButton button) {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        textField.setEditable(false);
        panel.add(textField, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }

    /**
     * Creates a page title label with bold font styling.
     *
     * @param title the title text
     * @return a JLabel styled as a page title
     */
    public static JLabel createPageTitle(String title) {
        JLabel label = new JLabel(title);
        label.setFont(new Font("SansSerif", Font.BOLD, 18));
        return label;
    }

    /**
     * Creates a scrollable text area with monospaced font.
     *
     * @param textArea the text area to wrap
     * @param rows     the preferred number of rows
     * @param cols     the preferred number of columns (not currently used)
     * @return a JScrollPane containing the text area
     */
    public static JScrollPane createScrollableTextArea(JTextArea textArea, int rows, int cols) {
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(0, textArea.getFont().getSize() * rows + 20));
        return scrollPane;
    }

    /**
     * Creates a progress bar with string painting enabled but initially hidden.
     *
     * @return a new JProgressBar instance
     */
    public static JProgressBar createProgressBar() {
        JProgressBar progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        return progressBar;
    }

    /**
     * Creates a panel with buttons centered horizontally.
     *
     * @param buttons the buttons to add to the panel
     * @return a JPanel with centered buttons
     */
    public static JPanel createCenterButtonPanel(JButton... buttons) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        for (JButton btn : buttons) {
            panel.add(btn);
        }
        return panel;
    }

    /**
     * Creates a vertical spacing component.
     *
     * @param size the height of the spacing in pixels
     * @return a Component representing the vertical space
     */
    public static Component createVerticalStrut(int size) {
        return Box.createVerticalStrut(size);
    }

    /**
     * Creates a horizontal spacing component.
     *
     * @param size the width of the spacing in pixels
     * @return a Component representing the horizontal space
     */
    public static Component createHorizontalStrut(int size) {
        return Box.createHorizontalStrut(size);
    }
}
