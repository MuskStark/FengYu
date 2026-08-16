package fan.summer.fengyu.plugin.excel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileNameUtilTest {
    @Test
    void stripsExtension() {
        assertEquals("report_2024_Q1", FileNameUtil.getFileName("report_2024_Q1.xlsx"));
        assertEquals("data", FileNameUtil.getFileName("data.csv"));
        assertEquals("archive", FileNameUtil.getFileName("archive"));
    }

    @Test
    void sanitizesSplitKeysIntoSafeFilenameSegments() {
        // Path traversal collapses to underscores — the key can no longer steer the output
        // path outside the output directory.
        assertEquals(".._.._evil", FileNameUtil.sanitizeSegment("../../evil"));
        assertEquals(".._.._evil", FileNameUtil.sanitizeSegment("..\\..\\evil"));
        // Characters illegal on Windows (each separator char → one underscore).
        assertEquals("a_b_c_d_e_f_g_h_i_j", FileNameUtil.sanitizeSegment("a<b>c:d\"e/f\\g|h?i*j"));
        // Control characters.
        assertEquals("ctrl__y", FileNameUtil.sanitizeSegment("ctrl\u0000\u001fy"));
        // Truncation to a sane length.
        assertEquals(120, FileNameUtil.sanitizeSegment("x".repeat(300)).length());
        // Defensive: null/empty never produce an empty segment.
        assertEquals("_", FileNameUtil.sanitizeSegment(null));
        assertEquals("_", FileNameUtil.sanitizeSegment(""));
    }
}
