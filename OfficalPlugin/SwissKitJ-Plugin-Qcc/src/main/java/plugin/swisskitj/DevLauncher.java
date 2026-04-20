package plugin.swisskitj;

import com.formdev.flatlaf.FlatIntelliJLaf;

import javax.swing.*;

public class DevLauncher {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FlatIntelliJLaf.setup(); // 和主程序保持同款主题

            JFrame frame = new JFrame("Plugin Dev");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(900, 600);

            // 直接实例化你的页面
            QccToExcel page = new QccToExcel();
            frame.add(page.getPanel());

            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}