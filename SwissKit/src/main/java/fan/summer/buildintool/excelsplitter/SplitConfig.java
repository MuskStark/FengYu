package fan.summer.buildintool.excelsplitter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SplitConfig {

    public enum SplitMode { BY_SHEET, BY_COLUMN, COMPLEX }

    // Step 1: source file + analysis result (populated after async analysis)
    public Path sourceFile;
    public Map<String, Map<Integer, String>> analysisResult; // sheetName → colIndex → header

    // Step 2: mode
    public SplitMode mode = SplitMode.BY_SHEET;

    // BY_SHEET: which sheets to export (all if empty)
    public List<String> selectedSheets = new ArrayList<>();

    // BY_COLUMN: sheet and column to group by
    public String splitSheet;
    public String splitColumn;
    public int    splitColumnIndex = -1;

    // COMPLEX: DB-backed config task ID
    public String complexTaskId;

    // Step 3: output options
    public Path   outputDir;
    public String filePrefix = "";
}
