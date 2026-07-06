package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.ZhiFlowPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BasePluginHostTest {

    private static ZhiFlowPlugin stubPlugin() {
        return new ZhiFlowPlugin() {
            public String getId() { return "test.host.plugin"; }
            public String getName() { return "Test"; }
            public String getDescription() { return ""; }
            public ToolCategory getCategory() { return ToolCategory.OTHER; }
            public String getVersion() { return "0"; }
            public String getMdiIcon() { return "star"; }
            public Node createView() { return null; }
        };
    }

    /** 具体化:settings 用内存 Map。 */
    private static BasePluginHost host(ZhiFlowPlugin p) {
        return new BasePluginHost(p) {
            private final Map<String, String> map = new HashMap<>();
            private final PluginSettings settings = new PluginSettings() {
                public Optional<String> get(String key) { return Optional.ofNullable(map.get(key)); }
                public String get(String key, String def) { return map.getOrDefault(key, def); }
                public void put(String key, String value) { if (value == null) map.remove(key); else map.put(key, value); }
                public void remove(String key) { map.remove(key); }
            };
            @Override public PluginSettings settings() { return settings; }
        };
    }

    @Test
    void pluginIdMirrorsPlugin() {
        assertEquals("test.host.plugin", host(stubPlugin()).pluginId());
    }

    @Test
    void facadesAreNonNullAndStable() {
        BasePluginHost h = host(stubPlugin());
        assertNotNull(h.logger(BasePluginHostTest.class));
        assertNotNull(h.i18n());
        assertNotNull(h.theme());
        assertNotNull(h.notifications());
        assertSame(h.tasks(), h.tasks());   // TaskRunner 是每 host 单例
        assertSame(h.i18n(), h.i18n());
    }

    @Test
    void i18nGetFallsBackToKey() {
        // I18n.get 未命中时返回 key 本身 —— 门面必须保持该语义
        assertEquals("no.such.key.xyz", host(stubPlugin()).i18n().get("no.such.key.xyz"));
    }

    @Test
    void nullPluginRejected() {
        assertThrows(NullPointerException.class, () -> host(null));
    }
}
