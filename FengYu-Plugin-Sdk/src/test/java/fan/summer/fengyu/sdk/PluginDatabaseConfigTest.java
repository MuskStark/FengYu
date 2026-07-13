package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDatabaseConfigTest {
    @TempDir Path temp;

    @Test void completeEnvironmentBuildsConfig() {
        var env = Map.of(
            PluginEnvironment.DB_TYPE, "h2", PluginEnvironment.DB_DRIVER, "org.h2.Driver",
            PluginEnvironment.DB_URL, "jdbc:h2:mem:mail", PluginEnvironment.DB_USERNAME, "sa",
            PluginEnvironment.DB_PASSWORD, "secret", PluginEnvironment.PLUGIN_DATA_DIR, temp.toString());

        var config = PluginDatabaseConfig.fromEnvironment(env).orElseThrow();

        assertEquals("jdbc:h2:mem:mail", config.url());
        assertEquals("secret", config.password());
        assertFalse(config.toString().contains("secret"));
    }

    @Test void absentDatabaseValuesReturnEmpty() {
        assertTrue(PluginDatabaseConfig.fromEnvironment(Map.of()).isEmpty());
    }

    @Test void partialDatabaseValuesFailWithoutEchoingSecrets() {
        var error = assertThrows(IllegalArgumentException.class, () ->
            PluginDatabaseConfig.fromEnvironment(Map.of(PluginEnvironment.DB_PASSWORD, "secret")));

        assertFalse(error.getMessage().contains("secret"));
    }
}
