package fan.summer.fengyu.plugin.email.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Picks the right {@link ContactFileParser} for a contact-list file. Format is
 * detected from the file extension first, then from content sniffing (the
 * {@code PK} ZIP magic that wraps {@code .xlsx}), so mislabeled exports are
 * still handled correctly.
 */
final class ContactParserFactory {

    static ContactFileParser forFile(Path file, String tagDelimiter) {
        String name = file == null ? "" : file.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv") || name.endsWith(".txt")) return new ContactCsvParser();
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return new ContactExcelParser(tagDelimiter);
        // Unknown extension — sniff the magic bytes.
        if (looksLikeZip(file)) return new ContactExcelParser(tagDelimiter);
        return new ContactCsvParser(); // default: treat as delimited text
    }

    private static boolean looksLikeZip(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(2);
            return magic.length >= 2 && magic[0] == 'P' && magic[1] == 'K'; // ZIP / xlsx magic
        } catch (IOException e) {
            return false;
        }
    }

    private ContactParserFactory() { }
}
