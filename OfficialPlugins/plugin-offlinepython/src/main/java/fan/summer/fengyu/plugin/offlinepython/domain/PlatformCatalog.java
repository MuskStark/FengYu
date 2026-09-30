package fan.summer.fengyu.plugin.offlinepython.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalog of pip-valid target platforms plus pure selection helpers (summary text and an
 * at-least-one toggle guard). Pure logic lives here so it is unit-testable headless; the
 * JavaFX {@code PlatformMultiSelect} view is a thin wrapper over these methods.
 */
public final class PlatformCatalog {

    /** A supported platform: a pip-valid --platform tag plus a Chinese display label. */
    public record Entry(String tag, String label) {}

    /** Ordered catalog of supported target platforms. */
    public static final List<Entry> ALL = List.of(
            new Entry("win_amd64", "Windows x64"),
            new Entry("win32", "Windows x86"),
            new Entry("manylinux2014_x86_64", "Linux x64"),
            new Entry("manylinux2014_aarch64", "Linux ARM64"),
            new Entry("macosx_10_15_x86_64", "macOS Intel"),
            new Entry("macosx_11_0_arm64", "macOS Apple Silicon"),
            new Entry("any", "通用（纯 Python）"));

    /** Display label for a tag, or the tag itself if not in the catalog. */
    public static String labelOf(String tag) {
        for (Entry e : ALL) if (e.tag().equals(tag)) return e.label();
        return tag;
    }

    /** MDI (Material Design Icons) name for a tag's OS family, to render a distinguishing
     *  icon in the UI. Maps by prefix so it works for any tag (incl. out-of-catalog ones
     *  from PyPI): win -> microsoft-windows, manylinux or linux -> linux,
     *  macosx or darwin -> apple, otherwise cube (any / generic). */
    public static String iconOf(String tag) {
        if (tag == null) return "cube";
        String t = tag.toLowerCase();
        if (t.startsWith("win")) return "microsoft-windows";
        if (t.startsWith("manylinux") || t.startsWith("linux")) return "linux";
        if (t.startsWith("macosx") || t.startsWith("macos") || t.startsWith("darwin")) return "apple";
        return "cube"; // any / generic / unknown
    }

    /** Architecture family parsed from a tag (e.g. "x86_64", "aarch64", "arm64", "x86", "arm");
     *  "" when not applicable (e.g. "any"). Works for out-of-catalog tags by suffix. */
    public static String archOf(String tag) {
        if (tag == null) return "";
        String t = tag.toLowerCase();
        if (t.endsWith("amd64") || t.endsWith("x86_64")) return "x86_64";
        if (t.endsWith("aarch64")) return "aarch64";
        if (t.endsWith("arm64")) return "arm64";
        if (t.equals("win32") || t.endsWith("i386") || t.endsWith("i686")) return "x86";
        if (t.endsWith("armv7l")) return "arm";
        return ""; // any / unknown
    }

    /** Bit width (32 / 64) parsed from a tag; 0 when not applicable (e.g. "any"). */
    public static int bitsOf(String tag) {
        String a = archOf(tag);
        if (a.isEmpty()) return 0;
        return (a.equals("x86") || a.equals("arm")) ? 32 : 64;
    }

    /** Compact "arch · N-bit" label for a tag (e.g. "x86_64 · 64-bit"); "" for pure-Python (any). */
    public static String archBitsLabel(String tag) {
        String a = archOf(tag);
        if (a.isEmpty()) return "";
        return a + " · " + bitsOf(tag) + "-bit";
    }

    /**
     * Compact summary for a selection: one item → its tag; two → "a、b"; three+ → "a、b +N".
     * Used by both the dropdown button text and the table's target-platform column.
     */
    public static String summary(List<String> selected) {
        if (selected == null || selected.isEmpty()) return "win_amd64";
        if (selected.size() == 1) return selected.get(0);
        if (selected.size() == 2) return selected.get(0) + "、" + selected.get(1);
        return selected.get(0) + "、" + selected.get(1) + " +" + (selected.size() - 2);
    }

    /**
     * Toggle a tag in the selection. Removing the last remaining platform is refused
     * (returns the list unchanged) so at least one target is always selected.
     */
    public static List<String> toggle(List<String> selected, String tag) {
        List<String> next = new ArrayList<>(selected == null ? List.of() : selected);
        if (next.contains(tag)) {
            if (next.size() <= 1) return next; // keep at least one
            next.remove(tag);
        } else {
            next.add(tag);
        }
        return next;
    }

    private PlatformCatalog() {}
}
