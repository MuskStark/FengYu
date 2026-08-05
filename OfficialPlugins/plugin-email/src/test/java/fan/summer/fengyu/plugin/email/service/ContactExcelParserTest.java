package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.model.ContactImport.ParseError;
import fan.summer.fengyu.plugin.email.model.ContactImport.ParsedContact;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactExcelParserTest {
    @TempDir Path temp;

    @Test void parsesFirstSheetAndMapsHeaderAliases() throws Exception {
        Path file = writeWorkbook(workbook -> {
            Sheet sheet = workbook.createSheet("Contacts");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("E-mail");
            header.createCell(1).setCellValue("姓名");
            header.createCell(2).setCellValue("Tags");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ada@example.com");
            row.createCell(1).setCellValue("Ada");
            row.createCell(2).setCellValue("Engineering, VIP");
        });
        try (ContactExcelParser parser = new ContactExcelParser("auto")) {
            var result = parser.parse(file);
            assertTrue(result.errors().isEmpty(), result.errors()::toString);
            assertEquals(1, result.contacts().size());
            ParsedContact ada = result.contacts().getFirst();
            assertEquals("ada@example.com", ada.email());
            assertEquals("Ada", ada.nickname());
            assertEquals(java.util.List.of("Engineering", "VIP"), ada.tags());
        }
    }

    @Test void reportsExtraSheetsAsInformationalNote() throws Exception {
        Path file = writeWorkbook(workbook -> {
            workbook.createSheet("Main");
            Row header = workbook.getSheet("Main").createRow(0);
            header.createCell(0).setCellValue("email");
            Row row = workbook.getSheet("Main").createRow(1);
            row.createCell(0).setCellValue("x@x.com");
            workbook.createSheet("Extra1");
            workbook.createSheet("Extra2");
        });
        try (ContactExcelParser parser = new ContactExcelParser("auto")) {
            var result = parser.parse(file);
            assertEquals(1, result.contacts().size());
            assertEquals("x@x.com", result.contacts().getFirst().email());
            // one informational note about the two extra sheets
            assertEquals(1, result.errors().size());
            ParseError note = result.errors().getFirst();
            assertTrue(note.message().contains("Extra1") && note.message().contains("Extra2"), note.message());
        }
    }

    @Test void reportsMissingEmailHeader() throws Exception {
        Path file = writeWorkbook(workbook -> {
            Sheet sheet = workbook.createSheet("S");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Nobody");
        });
        try (ContactExcelParser parser = new ContactExcelParser("auto")) {
            var result = parser.parse(file);
            assertTrue(result.contacts().isEmpty());
            assertEquals(1, result.errors().size());
            assertTrue(result.errors().getFirst().message().toLowerCase().contains("email"));
        }
    }

    @Test void skipsBlankRowsAndReportsBlankEmailCells() throws Exception {
        Path file = writeWorkbook(workbook -> {
            Sheet sheet = workbook.createSheet("S");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("email");
            Row blank = sheet.createRow(1);   // intentionally empty
            Row noEmail = sheet.createRow(2); // header cell exists, but no value
            noEmail.createCell(1).setCellValue("just a name");
            Row good = sheet.createRow(3);
            good.createCell(0).setCellValue("ok@example.com");
        });
        try (ContactExcelParser parser = new ContactExcelParser("auto")) {
            var result = parser.parse(file);
            assertEquals(1, result.contacts().size());
            assertEquals("ok@example.com", result.contacts().getFirst().email());
            // row 3 (spreadsheet) is the no-email row
            assertEquals(1, result.errors().size());
            assertTrue(result.errors().getFirst().message().toLowerCase().contains("email"));
        }
    }

    /** Builds an .xlsx workbook in temp using a lambda that populates it. */
    private Path writeWorkbook(java.util.function.Consumer<Workbook> populate) throws Exception {
        Path file = temp.resolve("contacts.xlsx");
        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(file.toFile())) {
            populate.accept(workbook);
            workbook.write(out);
        }
        return file;
    }
}
