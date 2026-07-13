package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginMarketplaceServiceTest {
    @Test
    void comparesSemanticVersionCore() {
        assertTrue(PluginMarketplaceService.compareVersions("1.2.0", "1.1.9") > 0);
        assertEquals(0, PluginMarketplaceService.compareVersions("2.0.0", "2.0.0"));
        assertTrue(PluginMarketplaceService.compareVersions("1.0.0", "1.0.1") < 0);
    }
}
