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
}
