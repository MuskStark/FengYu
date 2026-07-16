package fan.summer.fengyu.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards known Vue SPA routes to the bundled {@code index.html} so that deep links and browser
 * refreshes on history-mode routes resolve to the shell instead of a 404. Only the explicit,
 * top-level routes declared by {@code frontend/src/router/index.ts} are mapped here; API
 * ({@code /api/**}), static assets ({@code /assets/**}, {@code /vendor/**}), and plugin-runtime
 * ({@code /plugin-runtime/**}) paths fall through to their own handlers.
 */
@Controller
public final class SpaForwardController {
    @GetMapping({"/", "/setup", "/tools", "/agent", "/plugins", "/settings", "/about", "/plugin/{id}"})
    public String forward() {
        return "forward:/index.html";
    }
}
