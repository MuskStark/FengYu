package fan.summer.fengyu.plugin.excel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExcelSessionStoreTest {
    @Test
    void getCreatesAndReuses() {
        ExcelSessionStore s = new ExcelSessionStore();
        SplitConfig a = s.get("sess-1");
        assertSame(a, s.get("sess-1"));
    }

    @Test
    void activeTracksMostRecent() {
        ExcelSessionStore s = new ExcelSessionStore();
        s.get("sess-1");
        SplitConfig second = s.get("sess-2");
        assertTrue(s.active().isPresent());
        assertSame(second, s.active().get());
    }

    @Test
    void removeDropsSession() {
        ExcelSessionStore s = new ExcelSessionStore();
        s.get("sess-1");
        s.remove("sess-1");
        assertTrue(s.active().isEmpty());
    }
}
