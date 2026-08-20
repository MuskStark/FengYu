package fan.summer.fengyu.web.filter;

import fan.summer.fengyu.HeadlessLauncher;
import fan.summer.fengyu.web.StreamTicketService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
 * remain protected.
 *
 * <p>The AI and agent SSE endpoints accept a one-time {@code ?ticket=} from
 * {@link StreamTicketService} instead of the token: {@code EventSource} cannot set headers, and
 * the historical {@code ?token=} fallback leaked the full API credential into every
 * URL-capturing layer (proxy/access logs, shell history, webview diagnostics). A ticket
 * authorizes exactly one stream connection and expires quickly (see the service).
 *
 * <p>When the property is unset/blank, auth is disabled (browser-dev convenience). Combined with
 * the loopback-only bind, this keeps a random tab on the machine from hitting the backend.
 */
@Component
@Order(1)
public class TokenAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-FengYu-Token";

    private final StreamTicketService streamTickets;

    /** Production constructor — the ticket service is a required collaborator. */
    @Autowired
    public TokenAuthFilter(StreamTicketService streamTickets) {
        this.streamTickets = streamTickets;
    }

    /** Test constructor without tickets: the {@code ?ticket=} path is then unavailable. */
    TokenAuthFilter() {
        this(null);
    }

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
                || path.startsWith("/api/setup/")   // SETUP-mode wizard (see SetupApplication)
                // Workflow hooks have their own per-trigger secret. Only POST is public; trigger
                // creation/list/rotation/deletion remain under /api/agent and launch-token auth.
                || (path.startsWith("/api/workflow-hooks/")
                    && "POST".equalsIgnoreCase(request.getMethod()))
                || (path.startsWith("/plugin-runtime/")
                    && ("GET".equalsIgnoreCase(request.getMethod())
                        || "HEAD".equalsIgnoreCase(request.getMethod())))) {
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER);

        // EventSource cannot attach headers; the stream endpoints redeem a single-use ticket
        // minted by the header-authenticated /stream-ticket endpoints instead. The redemption
        // names the endpoint it is bound to, so a ticket minted for one stream cannot open
        // another.
        if (provided == null
                && ("GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod()))
                && streamTickets != null) {
            String streamEndpoint = StreamTicketService.STREAM_ENDPOINTS.contains(path) ? path : null;
            if (streamEndpoint != null
                    && streamTickets.redeem(request.getParameter("ticket"), streamEndpoint)) {
                chain.doFilter(request, response);
                return;
            }
        }

        // Constant-time comparison: the token is the only thing gating every API call, so a
        // short-circuiting String.equals would expose a timing side-channel on the loopback API
        // (reachable by every local user/process, and via DNS rebinding from a browser tab).
        // provided is null only when no header was sent at all — treat as a plain mismatch.
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
