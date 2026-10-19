package fan.summer.fengyu.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Clickjacking guard for the main SPA document (security audit P2): the shell may only be
 * framed by itself, a loopback origin (the Vite dev server on any port, the packaged web
 * deployment), or the desktop webview's {@code app://shell} origin. A meta tag cannot
 * express {@code frame-ancestors}, and neither the SPA nor the backend sent any framing
 * policy — so a remote page could iframe a loopback deployment as a phishing/UI shell
 * (the loopback-Host check stops DNS rebinding requests, not framing).
 *
 * <p>Scope: document-shaped GET/HEAD responses only. API and actuator payloads are not
 * documents; {@code /sandbox/*} pages pin their own stricter frame-ancestors; plugin UI
 * assets ({@code /plugin-runtime}) get their per-response CSP from
 * {@code PluginRuntimeController}, whose {@code setHeader} replaces this default — the
 * legitimate framers of those assets (the SPA itself) are all in this list anyway.
 */
@Component
@Order(3)
public class FrameAncestorsFilter extends OncePerRequestFilter {

    private static final String POLICY =
            "frame-ancestors 'self' http://localhost:* http://127.0.0.1:* http://[::1]:* app://shell";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        if (("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
                && !isExempt(request.getRequestURI())) {
            response.setHeader("Content-Security-Policy", POLICY);
        }
        chain.doFilter(request, response);
    }

    private static boolean isExempt(String path) {
        return path.startsWith("/api/") || path.startsWith("/sandbox/") || path.startsWith("/actuator/");
    }
}
