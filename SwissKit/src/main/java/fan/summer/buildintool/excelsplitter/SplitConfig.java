package fan.summer.buildintool.excelsplitter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SplitConfig {

    public enum SplitMode { BY_SHEET, BY_COLUMN, BY_ROW_COUNT }

    // Step 1: source file
    public Path sourceFile;

    // All sheet names from the source file (populated after file load)
    public List<String> sheetNames = new ArrayList<>();

    // Step 2: mode selection
    public SplitMode mode = SplitMode.BY_SHEET;

    // BY_SHEET: which sheets to export (defaults to all)
    public List<String> selectedSheets = new ArrayList<>();

    // BY_COLUMN: column name and 0-based index to split by
    public String splitColumn;
    public int    splitColumnIndex = -1;

    // BY_ROW_COUNT: max rows per output file
    public int rowsPerFile = 1000;

    // Step 3: output options
    public Path    outputDir;
    public String  filePrefix = "";
    public boolean keepHeader = true;
}
