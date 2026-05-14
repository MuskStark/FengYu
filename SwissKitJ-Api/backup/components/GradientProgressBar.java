package fan.summer.ui.components;

import javax.swing.*;
import java.awt.*;

/**
 * A gradient progress bar component with smooth animation effects.
 * <p>
 * This component extends {@link JProgressBar} and provides the following features:
 * <ul>
 *   <li>Gradient color effect from blue to purple for the progress bar</li>
 *   <li>Smooth animation with easing effect when value changes</li>
 *   <li>Rounded corners for a modern appearance</li>
 *   <li>Glossy highlight effect on the progress bar</li>
 *   <li>Optional text display in the center of the progress bar</li>
 * </ul>
 * <p>
 * The animation is powered by a {@link Timer} running at approximately 60 FPS,
 * using an easing algorithm that slows down as the animated value approaches the target value.
 * This creates a natural and visually appealing transition effect.
 * <p>
 * Usage example:
 * <pre>{@code
 * GradientProgressBar progressBar = new GradientProgressBar();
 * progressBar.setMinimum(0);
 * progressBar.setMaximum(100);
 * progressBar.setValue(50);  // Value changes smoothly with animation
 * }</pre>
 *
 * @author Summer
 * @version 1.0
 * @since 1.0
 */
public class GradientProgressBar extends JProgressBar {

    /**
     * Timer for driving the smooth animation effect.
     * Runs at approximately 60 FPS (16ms interval).
     */
    private Timer animationTimer;

    /**
     * The current animated value displayed on the progress bar.
     * This value gradually approaches the target value during animation.
     */
    private float animatedValue = 0f;

    /**
     * The target value that the progress bar should display.
     * When {@link #setValue(int)} is called, this target is updated and
     * the animation smoothly transitions the animatedValue to reach this target.
     */
    private float targetValue = 0f;

    /**
     * Constructs a new GradientProgressBar with default settings.
     * <p>
     * The constructor initializes the component with the following properties:
     * <ul>
     *   <li>Opaque set to false (transparent background)</li>
     *   <li>String painting disabled by default</li>
     *   <li>Animation timer started at ~60 FPS</li>
     * </ul>
     */
    public GradientProgressBar() {
        setOpaque(false);
        setStringPainted(false);

        // Start smooth animation timer (approximately 60 FPS)
        animationTimer = new Timer(16, e -> {
            if (Math.abs(animatedValue - targetValue) < 0.5f) {
                animatedValue = targetValue;
            } else {
                // Easing effect: slower as it approaches the target
                animatedValue += (targetValue - animatedValue) * 0.08f;
            }
            repaint();
        });
        animationTimer.start();
    }

    /**
     * Sets the current value of the progress bar and triggers smooth animation.
     * <p>
     * This method overrides the parent implementation to update the target value.
     * The animated value will smoothly transition to the new target value
     * using the easing algorithm in the animation timer.
     *
     * @param value the new value to display (must be between minimum and maximum)
     */
    @Override
    public void setValue(int value) {
        super.setValue(value);
        targetValue = value; // Update target value, animation will catch up automatically
    }

    /**
     * Paints the progress bar component with gradient colors and animation effects.
     * <p>
     * This method performs the following rendering steps:
     * <ol>
     *   <li>Enables anti-aliasing for smooth edges</li>
     *   <li>Draws the background as a light gray rounded rectangle</li>
     *   <li>Calculates the progress width based on animated value</li>
     *   <li>Draws the progress bar with blue-to-purple gradient</li>
     *   <li>Applies a glossy highlight effect on the top half</li>
     *   <li>Optionally displays text in the center if string painting is enabled</li>
     * </ol>
     *
     * @param g the Graphics context to use for painting
     */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth();
        int height = getHeight();
        int arc = height; // Corner radius equals height for fully rounded ends

        // Draw background
        g2.setColor(new Color(240, 240, 240));
        g2.fillRoundRect(0, 0, width, height, arc, arc);

        // Calculate progress width
        float percent = animatedValue / (float) getMaximum();
        int progressWidth = (int) (width * percent);

        if (progressWidth > 0) {
            // Gradient color: Blue → Purple
            GradientPaint gradient = new GradientPaint(
                    0, 0, new Color(64, 158, 255),   // Start color (blue)
                    width, 0, new Color(148, 103, 255) // End color (purple)
            );
            g2.setPaint(gradient);
            g2.fillRoundRect(0, 0, progressWidth, height, arc, arc);

            // Glossy highlight effect
            g2.setPaint(new GradientPaint(
                    0, 0, new Color(255, 255, 255, 60),
                    0, height / 2f, new Color(255, 255, 255, 0)
            ));
            g2.fillRoundRect(0, 0, progressWidth, height / 2, arc, arc);
        }

        // Display text if enabled
        if (isStringPainted()) {
            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font("Arial", Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            String text = getString();
            int tx = (width - fm.stringWidth(text)) / 2;
            int ty = (height + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, tx, ty);
        }

        g2.dispose();
    }

    /**
     * Stops the animation timer when the component is removed from a container.
     * Prevents resource leaks when the component is no longer visible.
     */
    @Override
    public void removeNotify() {
        if (animationTimer != null && animationTimer.isRunning()) {
            animationTimer.stop();
        }
        super.removeNotify();
    }
}