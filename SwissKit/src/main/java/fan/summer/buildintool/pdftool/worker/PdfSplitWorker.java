package fan.summer.buildintool.pdftool.worker;

import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import javafx.concurrent.Task;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX {@link Task} that splits a PDF document into multiple files based on
 * page ranges.
 *
 * <p>Each page range is an {@code int[]} of {@code [start, end]} (1-based,
 * inclusive). Output files are named {@code {baseName}_p{range}.pdf} (e.g.
 * {@code report_p1-3.pdf}, {@code report_p5.pdf}).</p>
 *
 * @since 3.0.0
 */
public class PdfSplitWorker extends Task<List<Path>> {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfSplitWorker.class);

    private final Path pdfPath;
    private final List<int[]> pageRanges;
    private final Path outputDir;

    /**
     * Creates a new split worker.
     *
     * @param pdfPath    path to the source PDF file
     * @param pageRanges list of page ranges, each {@code [start, end]} (1-based inclusive)
     * @param outputDir  directory where split files will be written
     */
    public PdfSplitWorker(Path pdfPath, List<int[]> pageRanges, Path outputDir) {
        this.pdfPath = pdfPath;
        this.pageRanges = pageRanges;
        this.outputDir = outputDir;
    }

    @Override
    public List<Path> call() throws Exception {
        List<Path> outputFiles = new ArrayList<>();
        String baseName = extractBaseName(pdfPath);

        log.info("Starting PDF split: {} ({} ranges)", pdfPath, pageRanges.size());

        try (PDDocument sourceDoc = Loader.loadPDF(pdfPath.toFile())) {
            int totalPages = sourceDoc.getNumberOfPages();
            log.debug("Source document has {} pages", totalPages);

            for (int i = 0; i < pageRanges.size(); i++) {
                if (isCancelled()) {
                    log.info("PDF split cancelled after processing {} ranges", i);
                    break;
                }

                int[] range = pageRanges.get(i);
                int start = range[0];
                int end = range[1];

                // Validate range
                if (start < 1 || end < start || start > totalPages) {
                    throw new IllegalArgumentException(
                            "Invalid page range [" + start + ", " + end + "] for document with " + totalPages + " pages");
                }
                int clampedEnd = Math.min(end, totalPages);

                // Build output file name
                String rangeLabel = (start == clampedEnd)
                        ? String.valueOf(start)
                        : start + "-" + clampedEnd;
                String outputName = baseName + "_p" + rangeLabel + ".pdf";
                Path outputPath = resolveUniquePath(outputDir, outputName);

                try (PDDocument splitDoc = new PDDocument()) {
                    for (int page = start; page <= clampedEnd; page++) {
                        PDPage sourcePage = sourceDoc.getPage(page - 1); // 0-based index
                        splitDoc.addPage(sourcePage);
                    }
                    splitDoc.save(outputPath.toFile());
                }

                outputFiles.add(outputPath);
                log.debug("Saved split range [{}-{}] to {}", start, clampedEnd, outputPath);

                updateProgress(i + 1, pageRanges.size());
            }
        }

        log.info("PDF split completed: {} files written", outputFiles.size());
        return outputFiles;
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
        if (!java.nio.file.Files.exists(candidate)) {
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
            if (!java.nio.file.Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }
}
