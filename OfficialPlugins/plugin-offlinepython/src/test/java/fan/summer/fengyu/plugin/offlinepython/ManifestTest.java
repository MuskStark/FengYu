package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import fan.summer.fengyu.plugin.offlinepython.domain.Manifest;
import fan.summer.fengyu.plugin.offlinepython.domain.WheelEntry;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestTest {

    @Test
    void manifestTracksSchemaVersionAndWheels() {
        Manifest m = new Manifest();
        m.setSchemaVersion(1);
        m.setBuiltAt("2026-06-26T14:52:48");
        m.getWheels().add(new WheelEntry("numpy", "1.26.4",
            "wheelhouse/numpy-1.26.4-cp312-cp312-win_amd64.whl",
            "deadbeef", 19098624L, true));

        String json = new com.google.gson.GsonBuilder().create().toJson(m);
        Manifest back = JsonStore.fromJson(json, Manifest.class);

        assertEquals(1, back.getSchemaVersion());
        assertEquals(1, back.getWheels().size());
        assertEquals("numpy", back.getWheels().get(0).getName());
        assertTrue(back.getWheels().get(0).isRequired());
        assertTrue(json.contains("\"schemaVersion\""));
    }

    @Test
    void manifestTracksPlatforms() {
        Manifest m = new Manifest();
        m.getPython().setPlatforms(new java.util.ArrayList<>(List.of("win_amd64", "manylinux2014_aarch64")));
        String json = new com.google.gson.GsonBuilder().create().toJson(m);
        Manifest back = JsonStore.fromJson(json, Manifest.class);
        assertEquals(List.of("win_amd64", "manylinux2014_aarch64"), back.getPython().getPlatforms());
    }
}
