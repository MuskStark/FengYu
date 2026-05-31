package fan.summer.buildintool.pdftool.converter;

import java.nio.file.Path;

/**
 * Strategy interface for converting a document from one format to another.
 *
 * <p>Implementations target specific back-ends (WPS Office, documents4j / MS Word,
 * LibreOffice, etc.). Callers obtain a suitable instance via
 * {@link OfficeDetector#detect()} and then invoke {@link #convert(Path, Path)}.</p>
 *
 * @since 3.0.0
 */
public interface DocumentConverter {

    /**
     * Converts the file at {@code inputPath} to the format implied by
     * {@code outputPath} and writes the result to {@code outputPath}.
     *
     * @param inputPath  the source file (e.g. a PDF)
     * @param outputPath the destination file (e.g. a DOCX)
     * @throws Exception if the conversion fails for any reason
     */
    void convert(Path inputPath, Path outputPath) throws Exception;
}
