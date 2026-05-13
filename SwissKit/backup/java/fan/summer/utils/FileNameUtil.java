package fan.summer.utils;

/**
 * Utility class for file name operations.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/4
 */
public class FileNameUtil {

    /**
     * Extracts file name without extension.
     *
     * @param fileName the file name with or without extension
     * @return file name without extension
     */
    public static String getFileName(String fileName) {
        String nameWithoutExt;
        if (fileName.contains(".")) nameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.'));
        else nameWithoutExt = fileName;
        return nameWithoutExt;
    }

}