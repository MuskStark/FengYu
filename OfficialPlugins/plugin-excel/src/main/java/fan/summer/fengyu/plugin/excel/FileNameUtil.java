package fan.summer.fengyu.plugin.excel;

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

    /** Path separators and characters illegal in Windows filenames. */
    private static final String ILLEGAL_FILENAME_CHARS = "<>:\"/\\|?*";

    /** Cap on a sanitized filename segment; split keys are raw cell values, so keep names sane. */
    private static final int MAX_SEGMENT_LENGTH = 120;

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

    /**
     * Sanitizes an arbitrary cell value (a split key) into a safe filename segment: path
     * separators, Windows-illegal characters ({@code <>:"/\|?*}) and control characters are
     * replaced with {@code '_'}, and the result is truncated to 120 characters. This keeps a
     * crafted cell value like {@code ../../evil} from steering the output path outside the
     * output directory, and keeps {@code / \ :} etc. from producing broken filenames.
     *
     * @param segment the raw split-key value
     * @return a safe, non-empty filename segment; never null
     */
    public static String sanitizeSegment(String segment) {
        if (segment == null || segment.isEmpty()) return "_";
        StringBuilder out = new StringBuilder(Math.min(segment.length(), MAX_SEGMENT_LENGTH));
        for (int i = 0; i < segment.length() && out.length() < MAX_SEGMENT_LENGTH; i++) {
            char c = segment.charAt(i);
            out.append(ILLEGAL_FILENAME_CHARS.indexOf(c) >= 0 || Character.isISOControl(c) ? '_' : c);
        }
        return out.length() == 0 ? "_" : out.toString();
    }
}
