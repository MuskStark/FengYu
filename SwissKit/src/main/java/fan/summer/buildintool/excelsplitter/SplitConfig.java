package fan.summer.buildintool.excelsplitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Value object holding all parameters required to perform an Excel split operation.
 * Populated progressively by the UI wizard across four steps:
 * file selection → mode selection → configuration review → execution.
 *
 * <p>Not all fields are used simultaneously — each {@link SplitMode} activates a
 * different subset of the configuration.
 *
 * @since 3.0.0
 * @see ExcelSplitter
 */
public class SplitConfig {

    private static final Logger log = LoggerFactory.getLogger(SplitConfig.class);

    /**
     * The three supported split modes.
     */
    public enum SplitMode {
        /** One output file per sheet, all rows copied. */
        BY_SHEET,
        /** Groups rows by a column's unique values, one file per value. */
        BY_COLUMN,
        /** DB-backed multi-config per task; supports both normal split and full-sheet copy. */
        COMPLEX
    }

    // Step 1: source file + analysis result (populated after async analysis)
    /** Path to the source Excel file; set by Step1View after file selection. */
    public Path sourceFile;
    /** Sheet-name → (colIndex → header) map; populated by {@link ExcelSplitter#analyze(Path)}. */
    public Map<String, Map<Integer, String>> analysisResult;

    // Step 2: mode
    /** Active split mode; defaults to {@link SplitMode#BY_SHEET}. */
    public SplitMode mode = SplitMode.BY_SHEET;

    // BY_SHEET: which sheets to export (all if empty)
    /** Sheets selected for export in {@link SplitMode#BY_SHEET} mode; empty means all sheets. */
    public List<String> selectedSheets = new ArrayList<>();

    // BY_COLUMN: sheet and column to group by
    /** Sheet name used for {@link SplitMode#BY_COLUMN} grouping. */
    public String splitSheet;
    /** Display name of the column being split on (for UI display only). */
    public String splitColumn;
    /** Zero-based column index for {@link SplitMode#BY_COLUMN} grouping. */
    public int    splitColumnIndex = -1;

    // COMPLEX: DB-backed config task ID
    /** UUID identifying this task in the complex-split DB table. */
    public String complexTaskId;

    // Step 3: output options
    /** Directory where all output files are written; set by Step3View. */
    public Path   outputDir;
    /** Optional prefix prepended to every output filename; may be blank. */
    public String filePrefix = "";

    /**
     * Returns a debug string summarizing the current configuration state.
     */
    public String toDebugString() {
        return "SplitConfig{sourceFile=" + sourceFile +
               ", mode=" + mode +
               ", selectedSheets=" + selectedSheets.size() +
               ", splitSheet=" + splitSheet +
               ", splitColumn=" + splitColumn +
               ", splitColumnIndex=" + splitColumnIndex +
               ", complexTaskId=" + complexTaskId +
               ", outputDir=" + outputDir +
               ", filePrefix='" + filePrefix + "'}";
    }
}
