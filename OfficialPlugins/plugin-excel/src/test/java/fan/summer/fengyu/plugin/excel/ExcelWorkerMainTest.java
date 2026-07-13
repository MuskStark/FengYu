package fan.summer.fengyu.plugin.excel;

import com.google.gson.Gson;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelWorkerMainTest {
    @TempDir Path tmp;

    @Test
    void handlesAnalyzeAndConfigureOverTheRealWorkerProtocol() throws Exception {
        Path source = tmp.resolve("input.xlsx");
        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream output = new FileOutputStream(source.toFile())) {
            Sheet sheet = workbook.createSheet("Alpha");
            sheet.createRow(0).createCell(0).setCellValue("region");
            sheet.createRow(1).createCell(0).setCellValue("east");
            workbook.write(output);
        }

        Gson json = new Gson();
        String input = json.toJson(Map.of(
            "jsonrpc", "2.0", "id", "analyze-1", "method", "analyze",
            "params", Map.of("session", "worker", "sourceFile", source.toString()))) + "\n"
            + json.toJson(Map.of(
            "jsonrpc", "2.0", "id", "configure-1", "method", "configure",
            "params", Map.of("session", "worker", "mode", "BY_SHEET",
                "selectedSheets", List.of("Alpha")))) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ExcelWorkerMain.worker().run(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        String responses = output.toString(StandardCharsets.UTF_8);
        assertEquals(2, responses.lines().count());
        assertTrue(responses.contains("\"id\":\"analyze-1\""));
        assertTrue(responses.contains("\"success\":true"));
        assertFalse(responses.contains("Unexpected character"));
    }
}
