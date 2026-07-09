package fan.summer.zhiflow.plugin;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.plugin.PluginDescriptor;
import fan.summer.zhiflow.api.plugin.PluginSource;
import fan.summer.zhiflow.api.plugin.ZhiFlowPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryServiceTest {

    private ZhiFlowPlugin plugin(String id, String uiEntry, PluginSource source) {
        return new ZhiFlowPlugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor(id, "n", "d", ToolCategory.OTHER,
                    "icon", IconStyle.BLUE, "1.0.0", uiEntry, false, source);
            }
            @Override public Object invoke(String action, java.util.Map<String, Object> args) {
                return null;
            }
        };
    }

    @Test
    void rejectsPluginWithBlankUiEntry() {
        PluginRegistryService svc = new PluginRegistryService(List.of(
            plugin("com.example.no-ui", "", PluginSource.THIRD_PARTY)));
        assertTrue(svc.find("com.example.no-ui").isEmpty(),
            "plugin with blank uiEntry must not be registered");
    }

    @Test
    void downgradesOfficialWithWrongIdPrefix() {
        PluginRegistryService svc = new PluginRegistryService(List.of(
            plugin("com.example.fake", "/plugin-ui/x/index.js", PluginSource.OFFICIAL)));
        Optional<ZhiFlowPlugin> found = svc.find("com.example.fake");
        assertTrue(found.isPresent());
        assertEquals(PluginSource.THIRD_PARTY, found.get().descriptor().source(),
            "OFFICIAL plugin whose id lacks 'fan.summer.' prefix is downgraded to THIRD_PARTY");
    }

    @Test
    void keepsOfficialWithCorrectPrefix() {
        PluginRegistryService svc = new PluginRegistryService(List.of(
            plugin("fan.summer.real", "/plugin-ui/x/index.js", PluginSource.OFFICIAL)));
        assertEquals(PluginSource.OFFICIAL,
            svc.find("fan.summer.real").orElseThrow().descriptor().source());
    }
}
