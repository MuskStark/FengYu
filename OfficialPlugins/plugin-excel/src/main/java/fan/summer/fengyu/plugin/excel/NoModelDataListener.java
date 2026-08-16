package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.PluginMessages;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Apache FESOD event listener for reading Excel data without intermediate model mapping.
 * Each row is captured as {@code Map<Integer, Object>} (column index → cell value) and held
 * in an in-memory list, bounded by {@link #MAX_ROWS} to keep the listener memory-bounded.
 *
 * <p>A sheet with more than {@link #MAX_ROWS} rows fails loudly with a localized
 * {@link IllegalStateException} instead of being silently truncated — the previous
 * batch-flush behaviour dropped every row past the cap (the log-only {@code saveData} was
 * never overridden) while the split still reported success, producing outputs with missing
 * data. The {@link #clear()} method must be called between successive read operations.
 *
 * @since 3.0.0
 * @see AnalysisEventListener
 */
public class NoModelDataListener extends AnalysisEventListener<Map<Integer, Object>> {

    private static final Logger log = LoggerFactory.getLogger(NoModelDataListener.class);

    /** Hard cap on rows buffered per read; keeps the listener memory-bounded. */
    public static final int MAX_ROWS = 500_000;

    /** Localized message resolver for the row-limit error. */
    private static final PluginMessages MSGS =
            PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, NoModelDataListener.class);

    private List<Map<Integer, Object>> cachedDataList = new ArrayList<>();
    private int totalRows;

    /**
     * Returns the accumulated row data captured since the last {@link #clear()} call.
     *
     * @return a live list of row maps; never null
     */
    public List<Map<Integer, Object>> getCachedDataList() {
        return cachedDataList;
    }

    /**
     * Clears the cached data list and reinitializes it for the next batch of data.
     * Call this before each new sheet read to avoid cross-contamination between sheets.
     */
    public void clear() {
        cachedDataList = new ArrayList<>();
        totalRows = 0;
    }

    /**
     * Invoked by FESOD for every row parsed. Appends the row to the in-memory list, or fails
     * loudly once the sheet would exceed {@link #MAX_ROWS} — the caller surfaces the error
     * instead of producing output files with silently missing rows.
     *
     * @param data    the parsed row as a column-index → value map
     * @param context the FESOD analysis context (unused)
     */
    @Override
    public void invoke(Map<Integer, Object> data, AnalysisContext context) {
        log.debug("Parsed one data row: {}", data);
        if (++totalRows > MAX_ROWS) {
            throw new IllegalStateException(MSGS.format("ex.err.rowLimitExceeded", MAX_ROWS));
        }
        cachedDataList.add(data);
    }

    /**
     * Invoked by FESOD after all rows have been fully parsed.
     *
     * @param context the FESOD analysis context (unused)
     */
    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        log.info("All data parsing completed!");
    }
}
