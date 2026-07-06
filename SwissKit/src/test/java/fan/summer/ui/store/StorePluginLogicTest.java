package fan.summer.ui.store;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static fan.summer.ui.store.StorePluginLogic.InstallState.*;
import static org.junit.jupiter.api.Assertions.*;

class StorePluginLogicTest {

    // ── compareVersion ───────────────────────────────────────────
    @Test
    void compareVersion_equal() {
        assertEquals(0, StorePluginLogic.compareVersion("1.2.3", "1.2.3"));
    }

    @Test
    void compareVersion_higherMinor() {
        assertTrue(StorePluginLogic.compareVersion("1.3.0", "1.2.9") > 0);
    }

    @Test
    void compareVersion_lowerMajor() {
        assertTrue(StorePluginLogic.compareVersion("1.9.9", "2.0.0") < 0);
    }

    @Test
    void compareVersion_differentSegmentCount() {
        // "1.2" treated as "1.2.0"
        assertEquals(0, StorePluginLogic.compareVersion("1.2", "1.2.0"));
        assertTrue(StorePluginLogic.compareVersion("1.2.1", "1.2") > 0);
    }

    @Test
    void compareVersion_nonNumericFallsBackToStringCompare() {
        // 3.0.0-rc.3 vs 3.0.0-rc.2 → rc.3 > rc.2 on the trailing segment
        assertTrue(StorePluginLogic.compareVersion("3.0.0-rc.3", "3.0.0-rc.2") > 0);
    }

    @Test
    void compareVersion_nullSafe() {
        assertEquals(0, StorePluginLogic.compareVersion(null, null));
        assertTrue(StorePluginLogic.compareVersion("1.0.0", null) > 0);
        assertTrue(StorePluginLogic.compareVersion(null, "1.0.0") < 0);
    }

    // ── installState ─────────────────────────────────────────────
    @Test
    void installState_notInstalled() {
        assertEquals(NOT_INSTALLED,
            StorePluginLogic.installState("com.x.a", "1.0.0", Map.of("com.x.b", "1.0.0")));
    }

    @Test
    void installState_installedSameVersion() {
        assertEquals(INSTALLED,
            StorePluginLogic.installState("com.x.a", "1.0.0", Map.of("com.x.a", "1.0.0")));
    }

    @Test
    void installState_storeNewer_updatable() {
        assertEquals(UPDATABLE,
            StorePluginLogic.installState("com.x.a", "1.1.0", Map.of("com.x.a", "1.0.0")));
    }

    @Test
    void installState_installedNewerThanStore_treatedAsInstalled() {
        assertEquals(INSTALLED,
            StorePluginLogic.installState("com.x.a", "1.0.0", Map.of("com.x.a", "1.2.0")));
    }

    @Test
    void installState_nullMap_notInstalled() {
        assertEquals(NOT_INSTALLED,
            StorePluginLogic.installState("com.x.a", "1.0.0", null));
    }

    // ── matches ──────────────────────────────────────────────────
    private StorePlugin sample() {
        StorePlugin p = new StorePlugin();
        p.id = "com.example.excel-splitter";
        p.name = "Excel Splitter";
        p.description = "Split large Excel files by sheet or column.";
        p.version = "2.1.0";
        p.iconStyle = IconStyle.BLUE;
        p.category = ToolCategory.DEV;
        return p;
    }

    @Test
    void matches_emptyQueryAllCategory_true() {
        assertTrue(StorePluginLogic.matches(sample(), "", null));
        assertTrue(StorePluginLogic.matches(sample(), null, null));
    }

    @Test
    void matches_nameSubstringCaseInsensitive() {
        assertTrue(StorePluginLogic.matches(sample(), "excel", null));
        assertTrue(StorePluginLogic.matches(sample(), "SPLIT", null));
    }

    @Test
    void matches_descriptionAndId() {
        assertTrue(StorePluginLogic.matches(sample(), "column", null));
        assertTrue(StorePluginLogic.matches(sample(), "example.excel", null));
    }

    @Test
    void matches_noHit_false() {
        assertFalse(StorePluginLogic.matches(sample(), "zzz", null));
    }

    @Test
    void matches_categoryFilter() {
        assertTrue(StorePluginLogic.matches(sample(), "", ToolCategory.DEV));
        assertFalse(StorePluginLogic.matches(sample(), "", ToolCategory.IMAGE));
    }

    @Test
    void matches_categoryAndQueryCombined() {
        assertTrue(StorePluginLogic.matches(sample(), "excel", ToolCategory.DEV));
        assertFalse(StorePluginLogic.matches(sample(), "excel", ToolCategory.NET));
    }
}
