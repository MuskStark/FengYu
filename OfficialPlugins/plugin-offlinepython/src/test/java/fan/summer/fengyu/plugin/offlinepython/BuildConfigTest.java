package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import fan.summer.fengyu.plugin.offlinepython.domain.BuildConfig;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildConfigTest {

    @Test
    void roundTripsThroughJson(@TempDir Path tmp) throws Exception {
        BuildConfig cfg = BuildConfig.defaults();
        cfg.getPython().setVersion("3.12.10");
        cfg.getPython().setPlatforms(new java.util.ArrayList<>(List.of("win_amd64", "manylinux2014_x86_64")));

        Path file = tmp.resolve("config.json");
        JsonStore.save(cfg, file);
        BuildConfig loaded = JsonStore.load(file, BuildConfig.class);

        assertEquals("3.12.10", loaded.getPython().getVersion());
        assertEquals(List.of("win_amd64", "manylinux2014_x86_64"), loaded.getPython().getPlatforms());
        assertEquals("win_amd64", loaded.getPython().getPrimaryPlatform());
        assertTrue(loaded.getDownload().isRecursive());
    }

    @Test
    void primaryPlatformDefaultsToWinAmd64WhenEmpty() {
        BuildConfig cfg = new BuildConfig();
        cfg.getPython().setPlatforms(new java.util.ArrayList<>());
        assertEquals("win_amd64", cfg.getPython().getPrimaryPlatform());
    }

    @Test
    void defaultsAreSensible() {
        BuildConfig cfg = BuildConfig.defaults();
        assertEquals("output", cfg.getRepository().getOutput());
        assertEquals("wheelhouse", cfg.getRepository().getWheelDir());
        assertEquals("official", cfg.getDownload().getMirror());
    }

    @Test
    void loadsLegacySinglePlatformConfigGracefully() {
        // Old config.json had a single "platform" key (field now removed). Gson ignores the
        // unknown key and `platforms` falls back to its field-initializer default ["win_amd64"]:
        // no crash, valid list. (Non-default legacy platform values are NOT migrated — accepted
        // on this unreleased branch where the UI never persisted a user-chosen platform.)
        String legacyJson = "{\"python\":{\"version\":\"3.12.10\",\"platform\":\"win_amd64\"}}";
        BuildConfig loaded = JsonStore.fromJson(legacyJson, BuildConfig.class);
        assertEquals(List.of("win_amd64"), loaded.getPython().getPlatforms());
        assertEquals("win_amd64", loaded.getPython().getPrimaryPlatform());
    }

    @Test
    void roundTripsDepPlatformsMap(@TempDir Path tmp) throws Exception {
        BuildConfig cfg = BuildConfig.defaults();
        cfg.getPython().getDepPlatforms().put("numpy",
                new java.util.ArrayList<>(List.of("win_amd64", "manylinux2014_x86_64")));
        cfg.getPython().getDepPlatforms().put("requests",
                new java.util.ArrayList<>(List.of("win_amd64")));

        Path file = tmp.resolve("config.json");
        JsonStore.save(cfg, file);
        BuildConfig loaded = JsonStore.load(file, BuildConfig.class);

        assertNotNull(loaded.getPython().getDepPlatforms());
        assertEquals(List.of("win_amd64", "manylinux2014_x86_64"),
                loaded.getPython().getDepPlatforms().get("numpy"));
        assertEquals(List.of("win_amd64"),
                loaded.getPython().getDepPlatforms().get("requests"));
    }

    @Test
    void legacyConfigWithoutDepPlatformsLoadsEmptyMap() {
        String legacyJson = "{\"python\":{\"version\":\"3.12.10\",\"platforms\":[\"win_amd64\"]}}";
        BuildConfig loaded = JsonStore.fromJson(legacyJson, BuildConfig.class);
        assertNotNull(loaded.getPython().getDepPlatforms());
        assertTrue(loaded.getPython().getDepPlatforms().isEmpty());
    }

    @Test
    void mergeIntoPreservesSectionsTheCallerOmitted(@TempDir Path tmp) throws Exception {
        // Disk has a full config with a customized repository section.
        BuildConfig disk = BuildConfig.defaults();
        disk.getRepository().setOutput("my-output");
        disk.getRepository().setWheelDir("my-wheels");
        disk.getPkg().setZip(false);
        Path cfgFile = tmp.resolve("config.json");
        JsonStore.save(disk, cfgFile);

        // A caller (UI or AI tool) sends only the python section.
        Map<String, Object> incomingPython = Map.of(
                "python", Map.of("version", "3.13.0", "platforms", List.of("manylinux2014_x86_64")));
        JsonObject base = JsonParser.parseString(Files.readString(cfgFile)).getAsJsonObject();
        JsonObject incoming = JsonStore.toJsonTree(incomingPython);
        BuildConfig merged = JsonStore.fromJson(
                JsonStore.mergeInto(base, incoming).toString(), BuildConfig.class);

        // python takes the incoming values...
        assertEquals("3.13.0", merged.getPython().getVersion());
        assertEquals(List.of("manylinux2014_x86_64"), merged.getPython().getPlatforms());
        // ...but repository/pkg/bundle keep their on-disk values — NOT reset to defaults.
        assertEquals("my-output", merged.getRepository().getOutput());
        assertEquals("my-wheels", merged.getRepository().getWheelDir());
        assertFalse(merged.getPkg().isZip(), "pkg.zip must be preserved from disk, not reset to true");
    }

    @Test
    void mergeIntoOverwritesLeafValuesWhenProvided() {
        JsonObject base = JsonParser.parseString(
                "{\"download\":{\"mirror\":\"official\",\"recursive\":true}}").getAsJsonObject();
        JsonObject incoming = JsonParser.parseString(
                "{\"download\":{\"mirror\":\"tsinghua\"}}").getAsJsonObject();
        BuildConfig merged = JsonStore.fromJson(
                JsonStore.mergeInto(base, incoming).toString(), BuildConfig.class);
        assertEquals("tsinghua", merged.getDownload().getMirror());
        assertTrue(merged.getDownload().isRecursive(), "unmentioned leaf must be preserved");
    }
}
