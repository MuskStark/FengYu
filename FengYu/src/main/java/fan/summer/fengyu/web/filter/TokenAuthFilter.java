package fan.summer.fengyu.web.filter;

import fan.summer.fengyu.HeadlessLauncher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Per-launch token auth. When {@link HeadlessLauncher#TOKEN_PROPERTY} is set, every request must
 * carry that token as the {@code X-FengYu-Token} header. Read-only plugin UI assets are public
 * because sandboxed iframe navigations cannot attach custom headers; all plugin API/RPC endpoints
 * remain protected. The AI and agent SSE endpoints accept the token as a {@code ?token=} query
 * parameter because {@code EventSource} cannot set custom headers.
 *
 * <p>When the property is unset/blank, auth is disabled (browser-dev convenience). Combined with
 * the loopback-only bind, this keeps a random tab on the machine from hitting the backend.
 */
@Component
@Order(1)
public class TokenAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-FengYu-Token";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String expected = System.getProperty(HeadlessLauncher.TOKEN_PROPERTY, "");
        if (expected.isBlank()) {          // auth disabled
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/api/health".equals(path)
                || path.startsWith("/api/setup/")
                || (path.startsWith("/plugin-runtime/")
                    && ("GET".equalsIgnoreCase(request.getMethod())
                        || "HEAD".equalsIgnoreCase(request.getMethod())))) {
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null
                && ("/api/ai/stream".equals(path) || "/api/agent/stream".equals(path))) {
            provided = request.getParameter("token");   // EventSource fallback
        }

        // Constant-time comparison: the token is the only thing gating every API call, so a
        // short-circuiting String.equals would expose a timing side-channel on the loopback API
        // (reachable by every local user/process, and via DNS rebinding from a browser tab).
        // provided is null only when no header/query was sent at all — treat as a plain mismatch.
        boolean ok = provided != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                         provided.getBytes(StandardCharsets.UTF_8));
        if (ok) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"error\":\"missing or invalid token\"}");
        }
    }
}
