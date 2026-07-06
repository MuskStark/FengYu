package fan.summer.zhiflow.buildintool.excelsplitter;

import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Apache FESOD event listener for batch-reading Excel data without intermediate model mapping.
 * Each row is captured as {@code Map<Integer, Object>} (column index → cell value) and held
 * in an in-memory list, which is periodically flushed via {@link #saveData()}.
 *
 * <p>Subclass this and override {@link #saveData()} to implement custom persistence logic.
 * The {@link #clear()} method must be called between successive read operations.
 *
 * @since 3.0.0
 * @see AnalysisEventListener
 */
public class NoModelDataListener extends AnalysisEventListener<Map<Integer, Object>> {

    private static final Logger log = LoggerFactory.getLogger(NoModelDataListener.class);

    /** Number of rows to accumulate before triggering a flush. */
    private static final int BATCH_COUNT = 500_000;
    private List<Map<Integer, Object>> cachedDataList = new ArrayList<>(BATCH_COUNT);
    private boolean usedDataBase;

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
        cachedDataList = new ArrayList<>(BATCH_COUNT);
    }

    /**
     * Invoked by FESOD for every row parsed. Logs the row at DEBUG level and appends
     * it to the internal list. Triggers a flush when the batch size is reached.
     *
     * @param data    the parsed row as a column-index → value map
     * @param context the FESOD analysis context (unused)
     */
    @Override
    public void invoke(Map<Integer, Object> data, AnalysisContext context) {
        log.debug("Parsed one data row: {}", data);
        usedDataBase = false;
        cachedDataList.add(data);
        if (cachedDataList.size() >= BATCH_COUNT) {
            saveData();
            cachedDataList = new ArrayList<>(BATCH_COUNT);
        }
    }

    /**
     * Invoked by FESOD after all sheets have been fully parsed. Any remaining rows in
     * the cache are flushed by calling {@link #saveData()}.
     *
     * @param context the FESOD analysis context (unused)
     */
    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        if (usedDataBase) {
            saveData();
            usedDataBase = false;
        }
        log.info("All data parsing completed!");
    }

    /**
     * Called when the row cache reaches {@link #BATCH_COUNT} or at end-of-sheet.
     * Default implementation logs the row count. Override this in a subclass to
     * persist batches to a database or file.
     */
    protected void saveData() {
        log.info("{} rows of data, starting to save to database!", cachedDataList.size());
        usedDataBase = true;
        log.info("Database save successful!");
    }
}
