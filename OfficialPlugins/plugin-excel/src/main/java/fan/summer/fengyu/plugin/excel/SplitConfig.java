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

    /**
     * Private copy for background split jobs: the session config stays mutable (and is
     * mutated under its session lock) while a job runs on a virtual thread, so the job must
     * operate on a snapshot taken under that lock. The analysis map is only ever replaced
     * wholesale, so a reference copy is sufficient; the mutable lists are copied defensively
     * so a later clear/re-configure on the session can never reach a running job.
     */
    public SplitConfig snapshot() {
        SplitConfig c = new SplitConfig();
        c.sourceFile = sourceFile;
        c.analysisResult = analysisResult;
        c.mode = mode;
        c.selectedSheets = selectedSheets == null ? new ArrayList<>() : new ArrayList<>(selectedSheets);
        c.splitSheet = splitSheet;
        c.splitColumn = splitColumn;
        c.splitColumnIndex = splitColumnIndex;
        c.complexEntries = complexEntries == null ? new ArrayList<>() : new ArrayList<>(complexEntries);
        c.outputDir = outputDir;
        c.filePrefix = filePrefix;
        return c;
    }
}
