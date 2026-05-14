package fan.summer.buildintool.excelsplitter;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration collected across wizard steps, passed between steps.
 */
public class SplitConfig {

    public enum SplitMode {
        BY_SHEET("By Sheet"),
        BY_COLUMN("By Column Value"),
        BY_ROW_COUNT("By Row Count");

        public final String label;
        SplitMode(String label) { this.label = label; }
    }

    // Step 1: Source file
    public Path   sourceFile;
    public List<String> sheetNames;   // All sheet names in source file

    // Step 2: Split mode
    public SplitMode mode = SplitMode.BY_SHEET;

    // BY_COLUMN specific
    public String splitColumn;        // Column header name
    public int    splitColumnIndex;   // Column index (0-based)

    // BY_ROW_COUNT specific
    public int rowsPerFile = 1000;

    // BY_SHEET specific
    public List<String> selectedSheets; // Sheets to split, null means all

    // Step 3: Output settings
    public Path   outputDir;
    public String filePrefix = "";    // Output file name prefix
    public boolean keepHeader = true; // Whether to keep header when splitting by column/row

    @Override
    public String toString() {
        return "SplitConfig{mode=" + mode + ", source=" + sourceFile + "}";
    }
}
