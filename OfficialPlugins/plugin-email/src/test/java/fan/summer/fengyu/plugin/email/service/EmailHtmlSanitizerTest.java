package fan.summer.fengyu.plugin.email.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailHtmlSanitizerTest {
    @Test
    void keepsEmailSafeWordFormattingAndDropsActiveContent() {
        String input = "<p class='MsoNormal' style='text-align:center;color:#336699;position:absolute'>"
            + "<b>Quarterly</b><script>alert(1)</script></p>"
            + "<table><tr><th>Milestone</th></tr><tr><td>Done</td></tr></table>";

        String clean = new EmailHtmlSanitizer().sanitize(input);

        assertTrue(clean.contains("<b>Quarterly</b>"));
        assertTrue(clean.contains("<table>"));
        assertTrue(clean.contains("text-align: center"));
        assertFalse(clean.contains("script"));
        assertFalse(clean.contains("MsoNormal"));
        assertFalse(clean.contains("position"));
    }

    @Test
    void derivesReadablePlainTextFromSanitizedHtml() {
        String plain = new EmailHtmlSanitizer().toPlainText("<h2>Hello</h2><p>Quarterly <b>report</b></p>");
        assertTrue(plain.contains("Hello"));
        assertTrue(plain.contains("Quarterly report"));
    }
}
