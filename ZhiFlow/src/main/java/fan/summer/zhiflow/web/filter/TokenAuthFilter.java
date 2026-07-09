package fan.summer.zhiflow.web.filter;

import fan.summer.zhiflow.HeadlessLauncher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-launch token auth. When {@link HeadlessLauncher#TOKEN_PROPERTY} is set, every request must
 * carry that token as the {@code X-ZhiFlow-Token} header — except {@code /api/health} (readiness
 * probe) and {@code /api/ai/stream}, which accepts the token as a {@code ?token=} query param
 * because {@code EventSource} cannot set custom headers.
 *
 * <p>When the property is unset/blank, auth is disabled (browser-dev convenience). Combined with
 * the loopback-only bind, this keeps a random tab on the machine from hitting the backend.
 */
@Component
@Order(1)
public class TokenAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-ZhiFlow-Token";

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
                || path.startsWith("/api/setup/")) {
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null && "/api/ai/stream".equals(path)) {
            provided = request.getParameter("token");   // EventSource fallback
        }

        if (expected.equals(provided)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"error\":\"missing or invalid token\"}");
        }
    }
}
