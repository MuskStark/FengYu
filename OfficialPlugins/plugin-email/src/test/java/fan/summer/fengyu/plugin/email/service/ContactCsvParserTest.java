package fan.summer.fengyu.plugin.email.service;

import fan.summer.fengyu.plugin.email.model.ContactImport.ParseError;
import fan.summer.fengyu.plugin.email.model.ContactImport.ParsedContact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactCsvParserTest {
    @TempDir Path temp;

    @Test void parsesHeaderAliasesAndDelimitedTags() throws Exception {
        Path file = write("headers.csv",
            "E-mail,Name,Tags,Notes\n" +
            "alice@example.com,Alice,Marketing | VIP,Big client\n" +
            "bob@example.com,Bob,Sales;Priority,\n");
        try (ContactCsvParser parser = new ContactCsvParser()) {
            ContactFileParser.Result result = parser.parse(file);
            assertEquals(0, result.errors().size(), result.errors()::toString);
            assertEquals(2, result.contacts().size());
            ParsedContact alice = result.contacts().get(0);
            assertEquals("alice@example.com", alice.email());
            assertEquals("Alice", alice.nickname());
            assertEquals("Big client", alice.notes());
            assertEquals(java.util.List.of("Marketing", "VIP"), alice.tags());
            ParsedContact bob = result.contacts().get(1);
            assertEquals(java.util.List.of("Sales", "Priority"), bob.tags());
        }
    }

    @Test void acceptsChineseHeaderAliases() throws Exception {
        Path file = write("zh.csv", "邮箱,姓名,标签\nli@example.com,李,Madrid\n");
        try (ContactCsvParser parser = new ContactCsvParser()) {
            ContactFileParser.Result result = parser.parse(file);
            assertEquals(0, result.errors().size(), result.errors()::toString);
            ParsedContact li = result.contacts().getFirst();
            assertEquals("li@example.com", li.email());
            assertEquals("李", li.nickname());
            assertEquals(java.util.List.of("Madrid"), li.tags());
        }
    }

    @Test void honorsQuotedFieldsWithEmbeddedDelimiterAndEscapedQuotes() throws Exception {
        Path file = write("quoted.csv",
            "email,name,notes\n" +
            "\"a,@x.com\",\"Last, First\",\"She said \"\"hi\"\"\"\n");
        try (ContactCsvParser parser = new ContactCsvParser()) {
            ContactFileParser.Result result = parser.parse(file);
            assertEquals(0, result.errors().size(), result.errors()::toString);
            ParsedContact a = result.contacts().getFirst();
            assertEquals("a,@x.com", a.email()); // RFC-4180: outer quotes stripped, inner comma preserved
            assertEquals("Last, First", a.nickname());
            assertEquals("She said \"hi\"", a.notes());
        }
    }

    @Test void stripsLeadingBomAndSkipsBlankRows() throws Exception {
        Path file = write("bom.csv",
            "\uFEFFemail,Name\n" +   // U+FEFF is the UTF-8 BOM; must be stripped before header parsing
            "\n" +
            "zoe@example.com,Zoe\n");
        try (ContactCsvParser parser = new ContactCsvParser()) {
            ContactFileParser.Result result = parser.parse(file);
            assertTrue(result.errors().isEmpty(), result.errors()::toString);
            assertEquals(1, result.contacts().size());
            assertEquals("zoe@example.com", result.contacts().getFirst().email());
        }
    }

    @Test void reportsMissingEmailHeaderAndBlankEmailRowsAsErrors() throws Exception {
        Path file = write("bad.csv",
            "name,tags\n" +              // no email column
            "No Email,x\n");
        try (ContactCsvParser parser = new ContactCsvParser()) {
            ContactFileParser.Result result = parser.parse(file);
            assertTrue(result.contacts().isEmpty());
            assertEquals(1, result.errors().size());
            assertNotNull(result.errors().get(0).message());
            assertTrue(result.errors().get(0).message().toLowerCase().contains("email"));
        }
    }

    @Test void recordsRowErrorForBlankEmailDataCell() throws Exception {
        Path file = write("blank-email.csv",
            "email,name\n" +
            ",Nameless\n" +
            "good@example.com,Good\n");
        try (ContactCsvParser parser = new ContactCsvParser()) {
            ContactFileParser.Result result = parser.parse(file);
            assertEquals(1, result.contacts().size());
            assertEquals("good@example.com", result.contacts().getFirst().email());
            assertEquals(1, result.errors().size());
            ParseError error = result.errors().getFirst();
            assertEquals(2, error.row()); // spreadsheet row 2
            assertTrue(error.message().toLowerCase().contains("email"));
        }
    }

    @Test void detectsSemicolonDelimiter() throws Exception {
        Path file = write("semi.csv", "email;name\na@x.com;A\n");
        try (ContactCsvParser parser = new ContactCsvParser()) {
            ContactFileParser.Result result = parser.parse(file);
            assertEquals(0, result.errors().size(), result.errors()::toString);
            assertEquals("a@x.com", result.contacts().getFirst().email());
            assertEquals("A", result.contacts().getFirst().nickname());
        }
    }

    private Path write(String name, String content) throws Exception {
        Path file = temp.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
