package fan.summer.buildintool.pdftool.converter;

import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java PDF-to-DOCX converter using Apache PDFBox for extraction and
 * Apache POI for DOCX generation.
 *
 * <p>Conversion strategy per page:</p>
 * <ol>
 *   <li>Extract text with {@link PDFTextStripper} and individual images via
 *       page-resource enumeration.</li>
 *   <li>If the page has text, write styled paragraphs <em>and</em> embed any
 *       extracted images after the text.</li>
 *   <li>If the page has no significant text but has extracted images, embed
 *       those images at their original resolution.</li>
 *   <li>If the page has neither text nor extractable images (e.g. vector
 *       graphics), render the whole page to a PNG as a fallback.</li>
 * </ol>
 *
 * @since 3.0.0
 */
public class PdfBoxToDocxConverter implements DocumentConverter {

    private static final PluginLogger log = LoggerFactory.getLogger(PdfBoxToDocxConverter.class);

    private static final int TEXT_THRESHOLD = 10;
    private static final float RENDER_DPI = 150f;
    private static final int MIN_IMAGE_SIZE = 10;
    private static final int IMAGE_WIDTH_EMU = 5486400; // ~6 inches

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
                    List<ExtractedImage> images = extractPageImages(page);

                    if (text.length() > TEXT_THRESHOLD) {
                        writeTextPage(docx, text, page);
                        for (ExtractedImage img : images) {
                            writeExtractedImage(docx, img);
                        }
                    } else if (!images.isEmpty()) {
                        log.debug("Page {} has {} extracted images, no significant text",
                                i + 1, images.size());
                        for (ExtractedImage img : images) {
                            writeExtractedImage(docx, img);
                        }
                    } else {
                        log.debug("Page {} has little text ({} chars), rendering as image",
                                i + 1, text.length());
                        writeImagePage(docx, renderer, i);
                    }

                    if (i < pageCount - 1) {
                        XWPFParagraph sep = docx.createParagraph();
                        sep.setSpacingAfter(200);
                        sep.createRun().addBreak();
                    }
                }

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

    // ── Image extraction ───────────────────────────────────────

    private static List<ExtractedImage> extractPageImages(PDPage page) {
        List<ExtractedImage> images = new ArrayList<>();
        try {
            var resources = page.getResources();
            if (resources == null) return images;

            for (var name : resources.getXObjectNames()) {
                try {
                    PDXObject xobj = resources.getXObject(name);
                    if (xobj instanceof PDImageXObject img) {
                        BufferedImage bi = img.getImage();
                        if (bi.getWidth() < MIN_IMAGE_SIZE || bi.getHeight() < MIN_IMAGE_SIZE) continue;
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(bi, "png", baos);
                        images.add(new ExtractedImage(baos.toByteArray(), bi.getWidth(), bi.getHeight()));
                    }
                } catch (Exception e) {
                    log.debug("Failed to extract image {}: {}", name.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to enumerate page resources: {}", e.getMessage());
        }
        return images;
    }

    private record ExtractedImage(byte[] pngBytes, int width, int height) {}

    // ── Text extraction ────────────────────────────────────────

    private static String extractPageText(PDDocument doc, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.setSortByPosition(true);
        return stripper.getText(doc).trim();
    }

    // ── Text page → DOCX paragraphs ────────────────────────────

    private static void writeTextPage(XWPFDocument docx, String text, PDPage page) {
        int fontSize = deriveFontSize(page);
        String[] lines = text.split("\\R");
        for (String line : lines) {
            XWPFParagraph para = docx.createParagraph();
            para.setSpacingAfter(0);

            if (line.isEmpty()) {
                continue;
            }

            XWPFRun run = para.createRun();
            run.setText(line);
            run.setFontSize(fontSize);
            run.setFontFamily("SimSun");
        }
    }

    private static int deriveFontSize(PDPage page) {
        PDRectangle mediaBox = page.getMediaBox();
        float heightPt = mediaBox.getHeight();
        int size = Math.round(heightPt / 842f * 11f);
        return Math.clamp(size, 8, 16);
    }

    // ── Extracted image → DOCX embed ───────────────────────────

    private static void writeExtractedImage(XWPFDocument docx, ExtractedImage img)
            throws IOException, org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        XWPFParagraph para = docx.createParagraph();
        para.setSpacingAfter(0);

        int heightEmu = Math.round((float) img.height / img.width * IMAGE_WIDTH_EMU);
        try (var bis = new java.io.ByteArrayInputStream(img.pngBytes)) {
            XWPFRun picRun = para.createRun();
            picRun.addPicture(bis,
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                    "extracted_image.png",
                    IMAGE_WIDTH_EMU, heightEmu);
        }
    }

    // ── Full-page render fallback → DOCX embed ─────────────────

    private static void writeImagePage(XWPFDocument docx, PDFRenderer renderer, int pageIndex)
            throws IOException, org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] imageBytes = baos.toByteArray();

        XWPFParagraph para = docx.createParagraph();
        para.setSpacingAfter(0);

        int widthEmu = IMAGE_WIDTH_EMU;
        int heightEmu = Math.round((float) image.getHeight() / image.getWidth() * widthEmu);

        try (var bis = new java.io.ByteArrayInputStream(imageBytes)) {
            XWPFRun picRun = para.createRun();
            picRun.addPicture(bis,
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                    "page_" + (pageIndex + 1) + ".png",
                    widthEmu, heightEmu);
        }
    }
}
