package fan.summer.kitpage.excel.listener;

import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.apache.fesod.sheet.exception.ExcelAnalysisStopException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apache FESOD event listener for extracting column headers from Excel files.
 * Stops reading after the header row is processed.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/1
 */
public class HeaderListener extends AnalysisEventListener<Map<Integer, String>> {
        private Map<Integer, String> headers = new HashMap<>();
        private boolean headRead = false;

        /**
         * Called when the header row is read
         * Sorts headers by column index and stores them in the list
         *
         * @param headMap a map of column indices to header values
         * @param context the analysis context
         */
        @Override
        public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
            // This method is triggered when reading the header row
            if (!headRead) {
                // Sort by column number and collect headers
                this.headers.clear();
                this.headers = headMap;
                headRead = true;
            }
        }

        /**
         * Called when a data row is read
         * Stops reading immediately after the first data row (only headers are needed)
         *
         * @param data a map of column indices to cell values
         * @param context the analysis context
         * @throws ExcelAnalysisStopException to stop the reading process
         */
        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            // Stop after reading the first row of data (only need headers)
            throw new ExcelAnalysisStopException();
        }

        /**
         * Called after all data has been analyzed
         *
         * @param context the analysis context
         */
        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {}

        /**
         * Get the list of headers extracted from the Excel file
         *
         * @return a list of header strings in column order
         */
        public Map<Integer, String> getHeaders() {
            return headers;
        }
    }