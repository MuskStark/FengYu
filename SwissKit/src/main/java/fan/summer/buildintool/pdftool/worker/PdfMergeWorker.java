package fan.summer.buildintool.pdftool.worker;

import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import javafx.concurrent.Task;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JavaFX {@link Task} that merges multiple PDF files into a single document.
 *
 * <p>Uses PDFBox's {@link PDFMergerUtility} to combine all source PDFs in the
 * given order. The output file name is derived from the first input file with
 * a {@code _merged} suffix. If the output path already exists, an auto-number
 * suffix ({@code (1)}, {@code (2)}, etc.) is appended.</p>
 *
 * @since 3.0.0
 */
public class PdfMergeWorker extends Task<Path> {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfMergeWorker.class);

    private final List<Path> pdfPaths;
    private final Path outputPath;

    /**
     * Creates a new merge worker.
     *
     * @param pdfPaths   ordered list of PDF files to merge
     * @param outputPath the destination file for the merged PDF
     */
    public PdfMergeWorker(List<Path> pdfPaths, Path outputPath) {
        this.pdfPaths = pdfPaths;
        this.outputPath = outputPath;
    }

    @Override
    public Path call() throws Exception {
        if (pdfPaths.isEmpty()) {
            throw new IllegalArgumentException("No PDF files to merge");
        }

        log.info("Starting PDF merge: {} files -> {}", pdfPaths.size(), outputPath);

        // Ensure output directory exists
        Path parentDir = outputPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        // Resolve unique output path if the target already exists
        Path destination = resolveUniquePath(outputPath);

        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(destination.toString());

        for (int i = 0; i < pdfPaths.size(); i++) {
            if (isCancelled()) {
                log.info("PDF merge cancelled after adding {} files", i);
                return null;
            }

            Path source = pdfPaths.get(i);
            if (!Files.exists(source)) {
                throw new FileNotFoundException("Source PDF not found: " + source);
            }

            merger.addSource(source.toFile());
            log.debug("Added source file: {}", source);

            updateProgress(i + 1, pdfPaths.size());
        }

        merger.mergeDocuments(IOUtils.createMemoryOnlyStreamCache());

        log.info("PDF merge completed: {}", destination);
        return destination;
    }

    /**
     * Resolves a unique output path, appending {@code (1)}, {@code (2)}, etc.
     * if the file already exists.
     */
    private static Path resolveUniquePath(Path path) {
        if (!Files.exists(path)) {
            return path;
        }

        Path parent = path.getParent();
        String fileName = path.getFileName().toString();
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
            Path candidate = (parent != null)
                    ? parent.resolve(nameWithoutExt + " (" + counter + ")" + ext)
                    : Path.of(nameWithoutExt + " (" + counter + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }
}
