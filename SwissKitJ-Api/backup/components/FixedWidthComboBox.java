package fan.summer.ui.components;

import javax.swing.*;
import java.awt.*;

/**
 * A JComboBox with a fixed width constraint.
 * Useful for creating consistent layout in forms where combo boxes should not stretch.
 *
 * @author summer
 * @since 1.0.0
 */
public class FixedWidthComboBox extends JComboBox {

    /**
     * The fixed width value for this combo box.
     */
    private final int fixedWidth;

    /**
     * Creates a FixedWidthComboBox with the specified width.
     *
     * @param fixedWidth the fixed width in pixels
     */
    public FixedWidthComboBox(int fixedWidth) {
        this.fixedWidth = fixedWidth;
    }

    /**
     * Returns the preferred size with a fixed width.
     *
     * @return dimension with fixed width and dynamic height
     */
    @Override
    public Dimension getPreferredSize() {
        // Use parent class for height calculation, fixed width
        return new Dimension(fixedWidth, super.getPreferredSize().height);
    }

    /**
     * Returns the maximum size (same as preferred).
     *
     * @return dimension with fixed width and dynamic height
     */
    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    /**
     * Returns the minimum size (same as preferred).
     *
     * @return dimension with fixed width and dynamic height
     */
    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }
}