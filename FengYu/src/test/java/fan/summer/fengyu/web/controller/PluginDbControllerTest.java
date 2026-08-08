package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.setup.DbProvisioningException;
import fan.summer.fengyu.setup.DbType;
import fan.summer.fengyu.setup.PluginDbProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit-tests {@link PluginDbController}'s HTTP semantics via {@link MockMvcBuilders#standaloneSetup}
 * (the repo's established pattern — see {@code SettingsControllerTest}). {@code @MockBean} was
 * removed in Spring Boot 4.x, so dependencies are wired with plain {@code Mockito.mock()} against a
 * standalone MockMvc. The assertions (status codes + response bodies) are exactly what the plan
 * specifies, so the test still pins the contract.
 */
class PluginDbControllerTest {

    private MockMvc setup(PluginPackageService packages, PluginDbProvisioner provisioner) {
        return MockMvcBuilders.standaloneSetup(new PluginDbController(packages, provisioner)).build();
    }

    @Test
    void provisionCallsProvisionerAndReturnsOk() throws Exception {
        PluginPackageService packages = mock(PluginPackageService.class);
        PluginDbProvisioner provisioner = mock(PluginDbProvisioner.class);
        when(packages.find(eq("fan.summer.email"))).thenReturn(Optional.of(
            manifest("fan.summer.email", List.of("database"))));
        when(provisioner.isProvisioned("fan.summer.email")).thenReturn(false);
        when(provisioner.provision("fan.summer.email")).thenReturn(
            new PluginDbProvisioner.ProvisionedCredentials(
                DbType.POSTGRESQL, "org.postgresql.Driver",
                "jdbc:postgresql://db/fengyu?currentSchema=fengyu_email",
                "fengyu_plugin_email", "pw"));

        setup(packages, provisioner).perform(post("/api/plugin-db/provision/fan.summer.email"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"provisioned\":true,\"status\":\"provisioned\",\"pluginId\":\"fan.summer.email\"}"));

        verify(provisioner).provision("fan.summer.email");
    }

    @Test
    void provisionReturns404WhenPluginNotInstalled() throws Exception {
        PluginPackageService packages = mock(PluginPackageService.class);
        PluginDbProvisioner provisioner = mock(PluginDbProvisioner.class);
        when(packages.find("no.such.plugin")).thenReturn(Optional.empty());

        setup(packages, provisioner).perform(post("/api/plugin-db/provision/no.such.plugin"))
            .andExpect(status().isNotFound());
    }

    @Test
    void provisionReturns409WhenPluginLacksDatabasePermission() throws Exception {
        PluginPackageService packages = mock(PluginPackageService.class);
        PluginDbProvisioner provisioner = mock(PluginDbProvisioner.class);
        when(packages.find("fan.summer.markdown")).thenReturn(Optional.of(
            manifest("fan.summer.markdown", List.of())));

        setup(packages, provisioner).perform(post("/api/plugin-db/provision/fan.summer.markdown"))
            .andExpect(status().isConflict());
    }

    @Test
    void provisionReturns500WithMessageWhenAdminCredentialsMissing() throws Exception {
        PluginPackageService packages = mock(PluginPackageService.class);
        PluginDbProvisioner provisioner = mock(PluginDbProvisioner.class);
        when(packages.find("fan.summer.email")).thenReturn(Optional.of(
            manifest("fan.summer.email", List.of("database"))));
        when(provisioner.provision("fan.summer.email")).thenThrow(
            new DbProvisioningException("Admin credentials are required"));

        setup(packages, provisioner).perform(post("/api/plugin-db/provision/fan.summer.email"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Admin credentials")));
    }

    @Test
    void statusReflectsProvisionerState() throws Exception {
        PluginPackageService packages = mock(PluginPackageService.class);
        PluginDbProvisioner provisioner = mock(PluginDbProvisioner.class);
        when(provisioner.isProvisioned("fan.summer.email")).thenReturn(true);

        setup(packages, provisioner).perform(post("/api/plugin-db/status/fan.summer.email"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"provisioned\":true,\"status\":\"provisioned\",\"pluginId\":\"fan.summer.email\"}"));
    }

    // Adjusted to the current PluginManifest constructor signature (14-arg backwards-compatible
    // form, omitting the trailing `Map<String, LocaleOverride> i18n`). Nested records Ui(String)
    // and Backend(String, String) [via its 2-arg compat ctor] match the current shape.
    private static PluginManifest manifest(String id, List<String> permissions) {
        return new PluginManifest(1, id, "Test", "Test", "1.0.0", "FengYu", "email", "net",
            new PluginManifest.Ui("ui/index.html"),
            new PluginManifest.Backend("java -jar backend/worker.jar", "json-rpc-2.0"),
            permissions, null, true, List.of());
    }
}
