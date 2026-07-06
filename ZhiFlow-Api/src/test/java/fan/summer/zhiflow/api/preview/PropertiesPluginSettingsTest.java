package fan.summer.zhiflow.api.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesPluginSettingsTest {

    @TempDir
    Path dir;

    @Test
    void putGetRemoveRoundTrip() {
        PropertiesPluginSettings s = new PropertiesPluginSettings(dir, "com.example.tool");
        assertTrue(s.get("k").isEmpty());
        s.put("k", "v");
        assertEquals("v", s.get("k").orElseThrow());
        assertEquals("v", s.get("k", "def"));
        s.remove("k");
        assertTrue(s.get("k").isEmpty());
        assertEquals("def", s.get("k", "def"));
    }

    @Test
    void persistsAcrossInstances() {
        new PropertiesPluginSettings(dir, "com.example.tool").put("lang", "zh");
        PropertiesPluginSettings reloaded = new PropertiesPluginSettings(dir, "com.example.tool");
        assertEquals("zh", reloaded.get("lang").orElseThrow());
        assertTrue(Files.exists(dir.resolve("com.example.tool.properties")));
    }

    @Test
    void nullValueMeansRemove() {
        PropertiesPluginSettings s = new PropertiesPluginSettings(dir, "p");
        s.put("k", "v");
        s.put("k", null);
        assertTrue(s.get("k").isEmpty());
    }

    @Test
    void nullKeyRejected() {
        PropertiesPluginSettings s = new PropertiesPluginSettings(dir, "p");
        assertThrows(NullPointerException.class, () -> s.get(null));
        assertThrows(NullPointerException.class, () -> s.put(null, "v"));
        assertThrows(NullPointerException.class, () -> s.remove(null));
    }

    @Test
    void pluginIdIsSanitizedForFileName() {
        assertEquals("a_b_c.d-e_f", PropertiesPluginSettings.sanitize("a/b:c.d-e f"));
    }
}
