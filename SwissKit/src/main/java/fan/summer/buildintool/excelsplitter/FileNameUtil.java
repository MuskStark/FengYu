package fan.summer.buildintool.excelsplitter;

public class FileNameUtil {
    public static String getFileName(String fileName) {
        if (fileName.contains(".")) return fileName.substring(0, fileName.lastIndexOf('.'));
        return fileName;
    }
}
