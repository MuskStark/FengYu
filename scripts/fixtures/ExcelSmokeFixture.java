import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Generates the minimal workbook used by scripts/e2e-smoke.sh. */
public final class ExcelSmokeFixture {
    private ExcelSmokeFixture() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ExcelSmokeFixture <output.xlsx>");
        }
        Path output = Path.of(args[0]);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Alpha");
            sheet.createRow(0).createCell(0).setCellValue("region");
            sheet.createRow(1).createCell(0).setCellValue("east");
            sheet.createRow(2).createCell(0).setCellValue("west");
            // Second sheet for the multi-rule complex split: one rule per worksheet.
            var beta = workbook.createSheet("Beta");
            beta.createRow(0).createCell(0).setCellValue("dept");
            beta.createRow(1).createCell(0).setCellValue("sales");
            beta.createRow(2).createCell(0).setCellValue("hr");
            try (var stream = Files.newOutputStream(output)) {
                workbook.write(stream);
            }
        }
    }
}
