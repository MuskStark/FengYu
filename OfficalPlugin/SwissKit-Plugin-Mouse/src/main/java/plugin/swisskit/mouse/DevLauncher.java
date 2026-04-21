package plugin.swisskit.mouse;

import plugin.swisskit.mouse.ui.KeepMove;

import javax.swing.*;

public class DevLauncher {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
//            FlatIntelliJLaf.setup(); // Use same theme as main application

            JFrame frame = new JFrame("Plugin Dev");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(900, 600);

            // Instantiate your page directly
            KeepMove page = new KeepMove();
            frame.add(page.getPanel());

            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}