package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDbProvisioningStoreTest {
    @TempDir Path temp;

    private PluginDbProvisioningStore newStore() {
        return new PluginDbProvisioningStore(temp);
    }

    @Test
    void putGetRemoveRoundTripsAndPasswordIsEncryptedOnDisk() throws Exception {
        PluginDbProvisioningStore store = newStore();
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "fan.summer.email", DbType.POSTGRESQL, "fengyu_fan_summer_email",
            "fengyu_plugin_email", "super-secret-pw", "jdbc:postgresql://db/fengyu",
            "org.postgresql.Driver", "2026-08-08T10:00:00Z"));

        PluginDbProvisioningStore.ProvisionedPluginDb loaded = store.get("fan.summer.email");
        assertEquals("fengyu_plugin_email", loaded.userName());
        assertEquals("super-secret-pw", loaded.password(), "password must decrypt back to plaintext");

        Properties raw = store.readRawForTest();
        String onDisk = raw.getProperty("plugin.fan.summer.email.password");
        assertTrue(onDisk.startsWith("ENC("), "plugin password must be encrypted at rest: " + onDisk);
        assertFalse(raw.toString().contains("super-secret-pw"));

        assertTrue(store.remove("fan.summer.email"));
        assertNull(store.get("fan.summer.email"));
        assertFalse(store.remove("fan.summer.email"), "second remove returns false");
    }

    @Test
    void getReturnsNullForUnknownPlugin() {
        assertNull(newStore().get("no.such.plugin"));
    }

    @Test
    void overwritingSamePluginReplacesTheRecord() {
        PluginDbProvisioningStore store = newStore();
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "p", DbType.MYSQL, "fengyu_p", "u1", "pw1", "jdbc:mysql://h/fengyu_p",
            "com.mysql.cj.jdbc.Driver", "t1"));
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "p", DbType.MYSQL, "fengyu_p", "u2", "pw2", "jdbc:mysql://h/fengyu_p",
            "com.mysql.cj.jdbc.Driver", "t2"));
        PluginDbProvisioningStore.ProvisionedPluginDb loaded = store.get("p");
        assertEquals("u2", loaded.userName());
        assertEquals("pw2", loaded.password());
    }

    @Test
    void lifecycleStatusAndListRoundTrip() {
        PluginDbProvisioningStore store = newStore();
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "fan.summer.email", DbType.H2, "fengyu_fan_summer_email", "worker",
            "pw", "jdbc:h2:tcp://localhost/db", "org.h2.Driver", "t",
            PluginDbProvisioningStore.STATUS_PROVISIONING));

        assertEquals(PluginDbProvisioningStore.STATUS_PROVISIONING,
            store.get("fan.summer.email").canonicalStatus());
        assertEquals(1, store.list().size());
        store.setStatus("fan.summer.email", PluginDbProvisioningStore.STATUS_DELETE_PENDING);
        assertEquals(PluginDbProvisioningStore.STATUS_DELETE_PENDING,
            store.list().getFirst().canonicalStatus());
    }
}
