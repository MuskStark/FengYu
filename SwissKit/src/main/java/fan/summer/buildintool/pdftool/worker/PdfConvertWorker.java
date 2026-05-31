package fan.summer.buildintool.pdftool.worker;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.buildintool.pdftool.converter.DocumentConverter;
import fan.summer.buildintool.pdftool.converter.Documents4jConverter;
import fan.summer.buildintool.pdftool.converter.OfficeDetector;
import fan.summer.buildintool.pdftool.converter.WpsConverter;
import javafx.concurrent.Task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX {@link Task} that converts a list of PDF files to DOCX format using
 * a detected Office back-end (WPS Office, LibreOffice, or MS Word).
 *
 * <p>The converter is selected based on the result of
 * {@link OfficeDetector#detect()}:</p>
 * <ul>
 *   <li>{@code WPS} &rarr; {@link WpsConverter}</li>
 *   <li>{@code LIBRE_OFFICE} or {@code MS_WORD} &rarr; {@link Documents4jConverter}</li>
 * </ul>
 *
 * <p>Each output file uses the same base name as the input with a
 * {@code .docx} extension. Progress is updated per file converted.</p>
 *
 * @since 3.0.0
 */
public class PdfConvertWorker extends Task<List<Path>> {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfConvertWorker.class);

    private final List<Path> pdfPaths;
    private final Path outputDir;

    /**
     * Creates a new convert worker.
     *
     * @param pdfPaths  list of PDF files to convert
     * @param outputDir directory where converted DOCX files will be written
     */
    public PdfConvertWorker(List<Path> pdfPaths, Path outputDir) {
        this.pdfPaths = pdfPaths;
        this.outputDir = outputDir;
    }

    @Override
    public List<Path> call() throws Exception {
        if (pdfPaths.isEmpty()) {
            throw new IllegalArgumentException("No PDF files to convert");
        }

        log.info("Starting PDF conversion: {} files", pdfPaths.size());

        // Detect Office back-end and create converter
        OfficeDetector.DetectedBackend backend = OfficeDetector.detect()
                .orElseThrow(() -> new IllegalStateException(
                        "No Office application found. Please install WPS Office, LibreOffice, or Microsoft Word."));

        DocumentConverter converter = createConverter(backend);
        log.info("Using converter: {} ({})", backend.displayName(), backend.type());

        // Ensure output directory exists
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        List<Path> outputFiles = new ArrayList<>();

        for (int i = 0; i < pdfPaths.size(); i++) {
            if (isCancelled()) {
                log.info("PDF conversion cancelled after processing {} files", i);
                break;
            }

            Path source = pdfPaths.get(i);
            String baseName = extractBaseName(source);
            Path outputPath = resolveUniquePath(outputDir, baseName + ".docx");

            log.debug("Converting {} -> {}", source, outputPath);
            converter.convert(source, outputPath);

            outputFiles.add(outputPath);
            updateProgress(i + 1, pdfPaths.size());
        }

        log.info("PDF conversion completed: {} files converted", outputFiles.size());
        return outputFiles;
    }

    /**
     * Creates the appropriate {@link DocumentConverter} for the detected back-end.
     */
    private static DocumentConverter createConverter(OfficeDetector.DetectedBackend backend) {
        return switch (backend.type()) {
            case WPS -> new WpsConverter(backend.executablePath());
            case LIBRE_OFFICE, MS_WORD -> new Documents4jConverter();
        };
    }

    /**
     * Extracts the base name (without extension) from a file path.
     */
    private static String extractBaseName(Path path) {
        String fileName = path.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
    }

    /**
     * Resolves a unique output path within the given directory, appending
     * {@code (1)}, {@code (2)}, etc. if the file already exists.
     */
    private static Path resolveUniquePath(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        String nameWithoutExt;
        String ext;
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            nameWithoutExt = fileName.substring(0, dotIdx);
            ext = fileName.substring(dotIdx);
        } else {
            nameWithoutExt = fileName;
            ext = "";
        }

        int counter = 1;
        while (true) {
            candidate = dir.resolve(nameWithoutExt + " (" + counter + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }
}
