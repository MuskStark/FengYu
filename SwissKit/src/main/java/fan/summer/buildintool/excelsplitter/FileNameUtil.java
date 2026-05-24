package fan.summer.buildintool.excelsplitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for filename manipulation, primarily used to strip file extensions
 * when constructing output filenames for split operations.
 *
 * @since 3.0.0
 */
public class FileNameUtil {

    private static final Logger log = LoggerFactory.getLogger(FileNameUtil.class);

    /**
     * Returns the filename without its extension.
     * If the input contains no dot, the full string is returned unchanged.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "report_2024_Q1.xlsx"} → {@code "report_2024_Q1"}</li>
     *   <li>{@code "data.csv"}            → {@code "data"}</li>
     *   <li>{@code "archive"}             → {@code "archive"}</li>
     * </ul>
     *
     * @param fileName the original filename (with or without extension)
     * @return the filename with extension removed; never null
     */
    public static String getFileName(String fileName) {
        log.debug("Extracting filename without extension: {}", fileName);
        if (fileName.contains(".")) {
            String result = fileName.substring(0, fileName.lastIndexOf('.'));
            log.debug("Stripped extension, result: {}", result);
            return result;
        }
        return fileName;
    }
}
