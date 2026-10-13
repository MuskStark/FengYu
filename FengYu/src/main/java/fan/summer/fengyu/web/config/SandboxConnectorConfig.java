package fan.summer.fengyu.web.config;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.web.SandboxOrigin;
import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The MCP Apps sandbox origin: an extra Tomcat connector on 127.0.0.1 at a DISTINCT port
 * (app port + 1 by preference, probed for availability), so third-party MCP App HTML runs
 * under a token-free browser origin — {@code SandboxOriginFilter} enforces that this port
 * serves ONLY {@code /sandbox/**} and that {@code /sandbox/**} is reachable ONLY through it.
 *
 * <p>A distinct origin cannot come from a loopback ALIAS (127.0.0.2): macOS refuses to bind
 * aliases without explicit ifconfig setup (Errno 49), so the design uses a second port on
 * the same loopback address — browsers key origin by host:port. The chosen port travels to
 * the browser inside the prepare response ({@code SandboxOrigin#appUrl}), so nothing else
 * needs to know it.
 *
 * <p>Failure posture: SETUP mode (no MCP runtime) and dynamic app ports skip the connector;
 * probe-bind failures degrade to "no rendered MCP Apps UI" — never to a weaker sandbox and
 * never a broken server.
 */
@Configuration
public class SandboxConnectorConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConnectorConfig.class);
    /** Port candidates tried after appPort+1 before falling back to an OS-assigned port. */
    private static final int PORT_PROBE_LIMIT = 8;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> sandboxConnectorCustomizer(
            ObjectProvider<McpRuntimeManager> mcpRuntime, SandboxOrigin sandboxOrigin) {
        return factory -> {
            if (mcpRuntime.getIfAvailable() == null) return; // SETUP mode: no MCP Apps surface
            if (factory.getPort() <= 0) {
                log.info("MCP Apps sandbox connector skipped: dynamic server port");
                return;
            }
            int chosen = pickPort(factory.getPort());
            if (chosen <= 0) {
                log.warn("MCP Apps sandbox connector: no bindable port found; MCP Apps UI disabled");
                return;
            }
            Connector sandbox = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            sandbox.setPort(chosen);
            // Loopback-only is a security invariant (same as the app connector): without this
            // the Tomcat default listens on every interface and would serve sandbox HTML to
            // the LAN. Tomcat 11 exposes the bind address as a connector property.
            sandbox.setProperty("address", "127.0.0.1");
            factory.addAdditionalConnectors(sandbox);
            sandboxOrigin.activate(chosen);
            log.info("MCP Apps sandbox origin bound: http://127.0.0.1:{}", chosen);
        };
    }

    /** appPort+1 first, then the next few, then an OS-assigned ephemeral port. */
    static int pickPort(int appPort) {
        for (int candidate = appPort + 1; candidate <= appPort + PORT_PROBE_LIMIT; candidate++) {
            if (bindable(candidate)) return candidate;
        }
        return bindable(0) ? 0 : -1; // 0 → OS-assigned at connector start; -1 → unavailable
    }

    private static boolean bindable(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port));
            return true;
        } catch (IOException busy) {
            return false;
        }
    }
}
