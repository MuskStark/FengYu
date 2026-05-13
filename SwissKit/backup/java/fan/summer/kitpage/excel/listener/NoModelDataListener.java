package fan.summer.kitpage.excel.listener;

import com.alibaba.fastjson2.JSON;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.common.util.ListUtils;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;

import java.util.List;
import java.util.Map;



/**
 * Apache FESOD event listener for batch reading Excel data without model mapping.
 * Caches data in memory and logs progress during parsing.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/1
 */
@Slf4j
public class NoModelDataListener extends AnalysisEventListener<Map<Integer, Object>> {

    private static final int BATCH_COUNT = 500000;
    @Getter
    private List<Map<Integer, Object>> cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
    private boolean usedDataBase;

    /**
     * Clears the cached data list and reinitializes it for the next batch of data.
     */
    public void clear() {
        cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
    }

    @Override
    public void invoke(Map<Integer, Object> data, AnalysisContext context) {
        log.debug("Parsed one data row: {}", JSON.toJSONString(data));
        usedDataBase = false;
        cachedDataList.add(data);
        if (cachedDataList.size() >= BATCH_COUNT) {
            saveData();
            cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        if (usedDataBase) {
            saveData();
            usedDataBase = false;
        }
        log.info("All data parsing completed!");
    }

    /**
     * Saves data to the database (placeholder implementation).
     */
    private void saveData() {
        log.info("{} rows of data, starting to save to database!", cachedDataList.size());
        usedDataBase = true;
        log.info("Database save successful!");
    }
}