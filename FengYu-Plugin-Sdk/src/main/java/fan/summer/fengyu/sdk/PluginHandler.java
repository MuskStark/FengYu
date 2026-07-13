package fan.summer.fengyu.sdk;

import java.util.Map;

@FunctionalInterface
public interface PluginHandler {
    Object handle(Map<String, Object> params) throws Exception;
}
