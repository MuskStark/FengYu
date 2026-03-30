package plugin.swisskitj;

import javax.swing.*;

/**
 * 类的详细说明
 *
 * @author summer
 * @version 1.00
 * @Date 2026/3/9
 */
public class QccToExcelWorker extends SwingWorker<Void, Void> {
    private String file;
    private String output;
    private JProgressBar progressBar;

    public QccToExcelWorker(String file, String output, JProgressBar progressBar) {
        this.file = file;
        this.output = output;
        this.progressBar = progressBar;
    }

    @Override
    protected Void doInBackground() throws Exception {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(0);
            progressBar.setStringPainted(true);
            progressBar.setString("Generate...");
        });
        CsvToExcelProcessor.process(file, output);
        return null;
    }
    @Override
    protected void done() {
        progressBar.setValue(100);
        progressBar.setString("Done");
    }
}
