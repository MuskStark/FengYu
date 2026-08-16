package fan.summer.fengyu.plugin.excel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the listener's row cap behaviour: rows up to {@link NoModelDataListener#MAX_ROWS} are
 * buffered, but a sheet that would exceed the cap fails LOUDLY instead of being silently
 * truncated (the old batch-flush dropped every row past 500k while callers still reported
 * success, so big sheets split "successfully" with missing data).
 */
class NoModelDataListenerTest {

    private static final Map<Integer, Object> ROW = Map.of(0, "v");

    @Test
    void acceptsRowsUpToTheCapThenFailsLoudly() {
        NoModelDataListener listener = new NoModelDataListener();
        for (int i = 0; i < NoModelDataListener.MAX_ROWS; i++) {
            listener.invoke(ROW, null);
        }
        assertEquals(NoModelDataListener.MAX_ROWS, listener.getCachedDataList().size());

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> listener.invoke(ROW, null));
        // MessageFormat renders the Integer limit with EN grouping; the error must name the cap.
        assertTrue(e.getMessage().contains("500,000"), e.getMessage());
    }

    @Test
    void clearResetsTheRowBudgetBetweenReads() {
        NoModelDataListener listener = new NoModelDataListener();
        listener.invoke(ROW, null);
        listener.clear();
        // Without the reset the very first (MAX_ROWS-th overall) invoke would throw.
        for (int i = 0; i < NoModelDataListener.MAX_ROWS; i++) {
            listener.invoke(ROW, null);
        }
        assertEquals(NoModelDataListener.MAX_ROWS, listener.getCachedDataList().size());
    }
}
