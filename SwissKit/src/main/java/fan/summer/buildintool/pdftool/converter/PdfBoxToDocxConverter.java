package fan.summer.buildintool.pdftool.converter;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Pure-Java PDF-to-DOCX converter using Apache PDFBox for extraction and
 * Apache POI for DOCX generation.  Does <strong>not</strong> require any
 * external Office application.
 *
 * <p>Conversion strategy per page:</p>
 * <ol>
 *   <li>Extract text with {@link PDFTextStripper} and write it as a styled
 *       paragraph (font size derived from the page dimensions).</li>
 *   <li>If a page yields very little text (&le; 10 characters) it is treated
 *       as image-only and rendered to a PNG that is embedded in the DOCX.</li>
 * </ol>
 *
 * @since 3.0.0
 */
public class PdfBoxToDocxConverter implements DocumentConverter {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfBoxToDocxConverter.class);

    /** Minimum characters on a page to consider it "textual". */
    private static final int TEXT_THRESHOLD = 10;

    /** Resolution (DPI) used when rendering image-only pages. */
    private static final float RENDER_DPI = 150f;

    @Override
    public void convert(Path inputPath, Path outputPath) throws Exception {
        log.info("Starting pure-Java PDF to DOCX conversion: {} -> {}", inputPath, outputPath);

        try (PDDocument pdfDoc = Loader.loadPDF(inputPath.toFile())) {
            int pageCount = pdfDoc.getNumberOfPages();
            log.info("PDF has {} pages", pageCount);

            PDFRenderer renderer = new PDFRenderer(pdfDoc);

            try (XWPFDocument docx = new XWPFDocument()) {
                for (int i = 0; i < pageCount; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Conversion interrupted");
                    }

                    PDPage page = pdfDoc.getPage(i);
                    String text = extractPageText(pdfDoc, i);

                    if (text.length() > TEXT_THRESHOLD) {
                        // Text page — write content as paragraphs
                        writeTextPage(docx, text, page);
                    } else {
                        // Image-only / scanned page — render and embed as image
                        log.debug("Page {} has little text ({} chars), rendering as image", i + 1, text.length());
                        writeImagePage(docx, renderer, i);
                    }

                    // Page separator (except after the last page)
                    if (i < pageCount - 1) {
                        XWPFParagraph sep = docx.createParagraph();
                        sep.setSpacingAfter(200);
                        sep.createRun().addBreak();
                    }
                }

                // Write DOCX
                Path parent = outputPath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                try (var out = Files.newOutputStream(outputPath)) {
                    docx.write(out);
                }
            }
        }

        log.info("PDF to DOCX conversion completed: {}", outputPath);
    }

    // ── Text extraction ────────────────────────────────────────

    private static String extractPageText(PDDocument doc, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1); // 1-based
        stripper.setEndPage(pageIndex + 1);
        stripper.setSortByPosition(true);
        return stripper.getText(doc).trim();
    }

    // ── Text page → DOCX paragraphs ────────────────────────────

    private static void writeTextPage(XWPFDocument docx, String text, PDPage page) {
        // Derive a sensible font size from the page dimensions.
        // A standard A4 page is ~842pt tall and typically uses 10-12pt fonts.
        int fontSize = deriveFontSize(page);

        // Split text into lines and write each as a paragraph.
        String[] lines = text.split("\\R");
        for (String line : lines) {
            XWPFParagraph para = docx.createParagraph();
            para.setSpacingAfter(0);

            if (line.isEmpty()) {
                // Empty line — keep as blank paragraph for spacing
                continue;
            }

            XWPFRun run = para.createRun();
            run.setText(line);
            run.setFontSize(fontSize);
            run.setFontFamily("SimSun"); // Good CJK coverage
        }
    }

    private static int deriveFontSize(PDPage page) {
        PDRectangle mediaBox = page.getMediaBox();
        float heightPt = mediaBox.getHeight();
        // A4 = 842pt → font 11; scale roughly linearly, clamped to [8, 16].
        int size = Math.round(heightPt / 842f * 11f);
        return Math.max(8, Math.min(16, size));
    }

    // ── Image page → rendered PNG embedded in DOCX ─────────────

    private static void writeImagePage(XWPFDocument docx, PDFRenderer renderer, int pageIndex)
            throws IOException, org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] imageBytes = baos.toByteArray();

        XWPFParagraph para = docx.createParagraph();
        para.setSpacingAfter(0);

        // Compute width in EMUs (English Metric Units) for the DOCX image.
        // Target ~6 inches wide, which is ~5486400 EMUs.
        int widthEmu = 5486400;
        int heightEmu = Math.round((float) image.getHeight() / image.getWidth() * widthEmu);

        try (var bis = new java.io.ByteArrayInputStream(imageBytes)) {
            XWPFRun picRun = para.createRun();
            @SuppressWarnings("deprecation")
            Object unused = picRun.addPicture(bis,
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                    "page_" + (pageIndex + 1) + ".png",
                    widthEmu, heightEmu);
        }
    }
}
