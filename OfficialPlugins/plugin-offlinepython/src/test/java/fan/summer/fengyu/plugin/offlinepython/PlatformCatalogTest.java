package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import fan.summer.fengyu.plugin.offlinepython.domain.PlatformCatalog;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlatformCatalogTest {

    @Test
    void allTagsArePipValidPlatformTags() {
        // pip --platform accepts win_*, manylinux*, macosx_*, any
        for (PlatformCatalog.Entry e : PlatformCatalog.ALL) {
            String t = e.tag();
            assertTrue(t.matches("^(win_amd64|win32|manylinux[0-9_]*_(x86_64|aarch64)|macosx_[0-9_]+_(x86_64|arm64)|any)$"),
                    "invalid tag: " + t);
            assertFalse(e.label().isBlank());
        }
    }

    @Test
    void labelOfKnownAndUnknown() {
        assertEquals("Windows x64", PlatformCatalog.labelOf("win_amd64"));
        assertEquals("zzz", PlatformCatalog.labelOf("zzz"));
    }

    @Test
    void summarySingleShowsTag() {
        assertEquals("win_amd64", PlatformCatalog.summary(List.of("win_amd64")));
    }

    @Test
    void summaryTwoJoinsWithComma() {
        assertEquals("win_amd64、manylinux2014_x86_64",
                PlatformCatalog.summary(List.of("win_amd64", "manylinux2014_x86_64")));
    }

    @Test
    void summaryThreeAppendsCount() {
        assertEquals("win_amd64、manylinux2014_x86_64 +1",
                PlatformCatalog.summary(List.of("win_amd64", "manylinux2014_x86_64", "any")));
    }

    @Test
    void summaryEmptyDefaultsToWinAmd64() {
        assertEquals("win_amd64", PlatformCatalog.summary(List.of()));
    }

    @Test
    void iconOfMapsPlatformTagToOsIcon() {
        assertEquals("microsoft-windows", PlatformCatalog.iconOf("win_amd64"));
        assertEquals("microsoft-windows", PlatformCatalog.iconOf("win32"));
        assertEquals("linux", PlatformCatalog.iconOf("manylinux2014_x86_64"));
        assertEquals("linux", PlatformCatalog.iconOf("manylinux2014_aarch64"));
        assertEquals("apple", PlatformCatalog.iconOf("macosx_11_0_arm64"));
        assertEquals("cube", PlatformCatalog.iconOf("any"));
        // out-of-catalog tags (e.g. from PyPI) still map by prefix
        assertEquals("linux", PlatformCatalog.iconOf("manylinux_2_28_x86_64"));
        assertEquals("cube", PlatformCatalog.iconOf(null));
    }

    @Test
    void archAndBitsParsedFromTag() {
        assertEquals("x86_64", PlatformCatalog.archOf("win_amd64"));
        assertEquals("x86_64", PlatformCatalog.archOf("manylinux2014_x86_64"));
        assertEquals("aarch64", PlatformCatalog.archOf("manylinux2014_aarch64"));
        assertEquals("arm64", PlatformCatalog.archOf("macosx_11_0_arm64"));
        assertEquals("x86", PlatformCatalog.archOf("win32"));
        assertEquals("", PlatformCatalog.archOf("any"));
        assertEquals(64, PlatformCatalog.bitsOf("win_amd64"));
        assertEquals(32, PlatformCatalog.bitsOf("win32"));
        assertEquals(0, PlatformCatalog.bitsOf("any"));
        assertEquals("x86_64 · 64-bit", PlatformCatalog.archBitsLabel("manylinux2014_x86_64"));
        assertEquals("x86 · 32-bit", PlatformCatalog.archBitsLabel("win32"));
        assertEquals("", PlatformCatalog.archBitsLabel("any"));
        // out-of-catalog tag still parsed by suffix
        assertEquals("x86_64", PlatformCatalog.archOf("manylinux_2_28_x86_64"));
    }

    @Test
    void toggleAddsMissingTag() {
        assertEquals(List.of("win_amd64", "any"),
                PlatformCatalog.toggle(List.of("win_amd64"), "any"));
    }

    @Test
    void toggleRemovesPresentTagWhenOthersRemain() {
        assertEquals(List.of("win_amd64"),
                PlatformCatalog.toggle(List.of("win_amd64", "any"), "any"));
    }

    @Test
    void toggleRefusesToRemoveLastPlatform() {
        assertEquals(List.of("win_amd64"),
                PlatformCatalog.toggle(List.of("win_amd64"), "win_amd64"));
    }
}
