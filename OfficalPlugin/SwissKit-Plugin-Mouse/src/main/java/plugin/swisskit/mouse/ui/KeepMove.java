/*
 * Created by JFormDesigner on Wed Apr 08 10:03:03 CST 2026
 */

package plugin.swisskit.mouse.ui;

import fan.summer.annoattion.SwissKitPage;
import fan.summer.api.KitPage;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author summer
 */
@SwissKitPage(menuName = "KeepMove", menuTooltip = "KeepMove", order = 9)
public class KeepMove implements KitPage {
    // Robot instance for controlling mouse movement
    private Robot robot;
    // Timer for scheduling periodic mouse movement
    private Timer timer;
    // Random for expert mode movement randomization
    private Random random = new Random();
    // Track last human input time for expert mode
    private volatile long lastHumanInputTime = System.currentTimeMillis();
    // Global input listeners for detecting human activity
    private AWTEventListener humanInputListener;
    // Expert mode state
    private boolean expertMode = true;
    // Expert mode: minimum interval between synthetic events
    private static final int EXPERT_MIN_INTERVAL_MS = 15_000;  // 15 seconds minimum
    // Expert mode: maximum interval between synthetic events
    private static final int EXPERT_MAX_INTERVAL_MS = 45_000;  // 45 seconds maximum
    // Expert mode: minimum movement distance
    private static final int EXPERT_MIN_MOVE = 10;
    // Expert mode: maximum movement distance
    private static final int EXPERT_MAX_MOVE = 50;

    public KeepMove() {
        initComponents();
        // Initialize Robot to control mouse cursor
        try {
            robot = new Robot();
        } catch (AWTException e) {
            e.printStackTrace();
        }
        // Set initial button states - Stop is disabled until movement starts
        stopMoveBt.setEnabled(false);
        setupEventHandlers();
        setupHumanInputDetection();
    }

    /**
     * Sets up global input listeners to detect human activity.
     * Expert mode: pause when human is moving, resume after 20s idle.
     */
    private void setupHumanInputDetection() {
        humanInputListener = new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                int id = event.getID();
                if (id == MouseEvent.MOUSE_MOVED ||
                        id == MouseEvent.MOUSE_DRAGGED ||
                        id == MouseEvent.MOUSE_PRESSED ||
                        id == MouseEvent.MOUSE_RELEASED ||
                        id == KeyEvent.KEY_PRESSED ||
                        id == KeyEvent.KEY_RELEASED) {
                    lastHumanInputTime = System.currentTimeMillis();
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(
                humanInputListener,
                AWTEvent.MOUSE_MOTION_EVENT_MASK |
                        AWTEvent.MOUSE_EVENT_MASK |
                        AWTEvent.KEY_EVENT_MASK
        );
    }

    /**
     * Checks if human has been idle for 20 seconds (resume condition).
     */
    private boolean isHumanIdle20Seconds() {
        return System.currentTimeMillis() - lastHumanInputTime >= 20_000;
    }

    @Override
    public JPanel getPanel() {
        return mouse;
    }

    /**
     * Sets up action listeners for Start and Stop buttons.
     */
    private void setupEventHandlers() {
        // Start button - begins periodic mouse movement
        startMoveBt.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startMouseMove();
            }
        });
        // Stop button - stops periodic mouse movement
        stopMoveBt.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopMouseMove();
            }
        });
    }

    /**
     * Starts periodic mouse movement in expert mode.
     * Uses randomized intervals (15-45s) and movements (10-50px) to appear human-like
     * while ensuring any screensaver is prevented.
     */
    private void startMouseMove() {
        // Disable Start, enable Stop - button interlock
        startMoveBt.setEnabled(false);
        stopMoveBt.setEnabled(true);

        timer = new Timer(true); // daemon timer
        // Schedule with expert mode: random interval to avoid pattern detection
        scheduleNextExpertMove();
    }

    /**
     * Schedules the next expert mode move with randomized delay.
     */
    private void scheduleNextExpertMove() {
        if (timer == null) return;

        // Random interval between 15-45 seconds (avoids screensaver pattern detection)
        int delay = EXPERT_MIN_INTERVAL_MS +
                random.nextInt(EXPERT_MAX_INTERVAL_MS - EXPERT_MIN_INTERVAL_MS);

        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                moveMouseExpert();
                scheduleNextExpertMove();
            }
        }, delay);
    }

    /**
     * Stops the periodic mouse movement.
     */
    private void stopMouseMove() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        // Enable Start, disable Stop - button interlock
        startMoveBt.setEnabled(true);
        stopMoveBt.setEnabled(false);
    }

    /**
     * Expert mode: Moves mouse with random offset and simulates human-like key activity.
     * Only runs when human has been idle for 20+ seconds.
     */
    private void moveMouseExpert() {
        // Only execute when human has been idle for 20 seconds
        if (!isHumanIdle20Seconds()) {
            return;
        }

        Point location = MouseInfo.getPointerInfo().getLocation();

        // Random movement distance (10-50 pixels) in random direction
        int moveDistance = EXPERT_MIN_MOVE +
                random.nextInt(EXPERT_MAX_MOVE - EXPERT_MIN_MOVE);
        int angle = random.nextInt(360);
        int dx = (int) (moveDistance * Math.cos(Math.toRadians(angle)));
        int dy = (int) (moveDistance * Math.sin(Math.toRadians(angle)));

        robot.mouseMove((int) location.getX() + dx, (int) location.getY() + dy);
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        mouse = new JPanel();
        startMoveBt = new JButton();
        stopMoveBt = new JButton();

        //======== mouse ========
        {
            mouse.setLayout(new MigLayout(
                "fill,hidemode 3,alignx center",
                // columns
                "[fill]" +
                "[fill]",
                // rows
                "[]" +
                "[]" +
                "[]"));

            //---- startMoveBt ----
            startMoveBt.setText("StartMove");
            mouse.add(startMoveBt, "cell 0 1");

            //---- stopMoveBt ----
            stopMoveBt.setText("StopMove");
            mouse.add(stopMoveBt, "cell 1 1");
        }
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel mouse;
    private JButton startMoveBt;
    private JButton stopMoveBt;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
