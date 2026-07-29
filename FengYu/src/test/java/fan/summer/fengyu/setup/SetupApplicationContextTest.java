package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full {@link SetupApplication} context (SETUP mode) to verify it starts cleanly.
 *
 * <p>SETUP mode is a minimal context: it scans only {@code fan.summer.fengyu.setup} +
 * {@code fan.summer.fengyu.web}, and excludes the APP-only controllers via
 * {@code excludeFilters} because they depend on beans (e.g. {@code AiModeService},
 * {@code PluginRegistryService}) that are not scanned in SETUP mode. A new APP-only controller
 * that is NOT added to that exclude list causes an {@code UnsatisfiedDependencyException} at boot
 * — exactly the regression that hit {@code AiConfigController} (it needs {@code AiModeService} +
 * {@code BackendReactivator}, neither scanned here). This test boots the real SETUP context so
 * such a gap fails the build instead of failing in production.
 *
 * <p>Mirrors {@code HeadlessIntegrationTest}'s approach for the APP-mode ({@code FengYuApplication})
 * context. Uses the {@code test} profile for consistency, though SETUP mode excludes
 * DataSource/JPA auto-config entirely.
 */
@SpringBootTest(classes = SetupApplication.class)
@ActiveProfiles("test")
class SetupApplicationContextTest {

    @Autowired
    ApplicationContext context;

    @Test
    void setupContext_bootsCleanly() {
        assertNotNull(context);
        assertTrue(context.containsBean("setupController"),
                "SETUP-mode context must contain the setup wizard controller");
        assertFalse(context.containsBean("securityController"),
                "APP-only security diagnostics must not load in SETUP mode");
        assertFalse(context.containsBean("mcpController"),
                "APP-only MCP diagnostics must not load in SETUP mode");
    }
}
