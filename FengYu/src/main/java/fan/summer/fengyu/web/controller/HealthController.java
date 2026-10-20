package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginHostVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health probe — the Electron sidecar polls {@code GET /api/health} until the backend is ready
 * before loading the webview. Also reports the running build's version so the SPA can render it
 * without a GitHub round-trip ({@code /api/updates/check} stays the source of latest-release
 * info, but it blocks on the network and must not gate displaying the local version).
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Map<String, Object> health() {
        return Map.of("status", "ok", "version", PluginHostVersion.current());
    }
}
