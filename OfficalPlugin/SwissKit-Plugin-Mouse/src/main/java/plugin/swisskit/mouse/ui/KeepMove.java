/*
 * Created by JFormDesigner on Wed Apr 08 10:03:03 CST 2026
 */

package plugin.swisskit.mouse.ui;

import fan.summer.annoattion.SwissKitPage;
import fan.summer.api.KitPage;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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
     * Starts periodic mouse movement - moves cursor 1 pixel every minute
     * to prevent screen saver from activating.
     */
    private void startMouseMove() {
        // Disable Start, enable Stop - button interlock
        startMoveBt.setEnabled(false);
        stopMoveBt.setEnabled(true);

        timer = new Timer(true); // daemon timer
        // Schedule task to run every 60,000 ms (1 minute)
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                moveMouse();
            }
        }, 0, 60_000); // 1 minute = 60,000 ms
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
     * Moves the mouse cursor by 1 pixel from its current position.
     * Uses Robot class to control the mouse programmatically.
     */
    private void moveMouse() {
        Point location = MouseInfo.getPointerInfo().getLocation();
        robot.mouseMove((int) location.getX() + 1, (int) location.getY() + 1);
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
