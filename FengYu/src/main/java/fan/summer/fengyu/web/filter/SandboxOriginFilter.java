package fan.summer.fengyu.web.filter;

import fan.summer.fengyu.web.SandboxOrigin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Hard origin separation for MCP Apps sandbox content. The sandbox Tomcat connector listens
 * on 127.0.0.1 at a DISTINCT port (see {@code SandboxConnectorConfig}), so
 * {@code http://127.0.0.1:<sandboxPort>} is a browser origin of its own — plugin-style MCP
 * App pages never execute on an origin that holds the auth token or the app's storage, even
 * if one is opened in a top-level window.
 *
 * <p>This filter enforces the mapping in both directions (the rules hold regardless of
 * filter order — {@code @Order(2)} pins it deterministically right behind
 * {@code TokenAuthFilter} ({@code @Order(1)}) instead of relying on component scan
 * order; a sandbox-path request on the app origin is 404'd here
 * before it can reach any controller):
 *
 * <ul>
 *   <li>requests arriving on the sandbox port may ONLY reach {@code /sandbox/**};</li>
 *   <li>{@code /sandbox/**} is ONLY served over the sandbox port — the app origin gets a
 *       plain 404, so no sandbox document (which is third-party MCP HTML) can ever execute
 *       on the token-bearing origin.</li>
 * </ul>
 *
 * <p>When the sandbox connector is inactive (SETUP mode, dynamic app port, exotic platform),
 * no request can arrive via the sandbox port and the second rule keeps {@code /sandbox/**}
 * dark everywhere — the feature degrades to "no MCP Apps UI" rather than to a weaker sandbox.
 */
@Component
@Order(2)
public class SandboxOriginFilter extends OncePerRequestFilter {

    public static final String SANDBOX_PATH_PREFIX = "/sandbox/";

    private final SandboxOrigin origin;

    public SandboxOriginFilter(SandboxOrigin origin) {
        this.origin = origin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean sandboxPath = request.getRequestURI().startsWith(SANDBOX_PATH_PREFIX);
        boolean viaSandboxPort = origin.active() && request.getLocalPort() == origin.port();
        if (viaSandboxPort != sandboxPath) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain");
            response.getWriter().write("not found");
            return;
        }
        chain.doFilter(request, response);
    }
}
