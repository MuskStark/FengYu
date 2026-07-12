package fan.summer.fengyu.plugin.excel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parameters for one split operation, populated progressively by the UI/AI tools. */
public class SplitConfig {
    public enum SplitMode { BY_SHEET, BY_COLUMN, COMPLEX }

    public Path sourceFile;
    public Map<String, Map<Integer, String>> analysisResult;
    public SplitMode mode = SplitMode.BY_SHEET;
    public List<String> selectedSheets = new ArrayList<>();
    public String splitSheet;
    public String splitColumn;
    public int splitColumnIndex = -1;
    public List<ComplexSplitEntry> complexEntries = new ArrayList<>();
    public Path outputDir;
    public String filePrefix = "";

    public String toDebugString() {
        return "SplitConfig{sourceFile=" + sourceFile + ", mode=" + mode
             + ", selectedSheets=" + selectedSheets.size() + ", splitSheet=" + splitSheet
             + ", splitColumn=" + splitColumn + ", splitColumnIndex=" + splitColumnIndex
             + ", complexEntries=" + complexEntries.size() + ", outputDir=" + outputDir
             + ", filePrefix='" + filePrefix + "'}";
    }
}
