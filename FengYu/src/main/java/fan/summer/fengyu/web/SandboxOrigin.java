package fan.summer.fengyu.web;

import org.springframework.stereotype.Component;

/**
 * The MCP Apps sandbox origin state: the port of the extra Tomcat connector that serves
 * {@code /sandbox/**} (bound on 127.0.0.1, distinct from the app port — a distinct origin
 * is what keeps third-party MCP App HTML off the token-bearing origin). Zero means the
 * sandbox is unavailable (SETUP mode, dynamic port, or probe-bind failed on an exotic
 * platform) and every {@code /sandbox/**} request 404s.
 */
@Component
public class SandboxOrigin {

    private volatile int port;

    /** Called once by the web-server customizer after the probe-bind picks the port. */
    public void activate(int boundPort) {
        this.port = boundPort;
    }

    public int port() {
        return port;
    }

    public boolean active() {
        return port > 0;
    }

    /** Absolute sandbox page URL handed to the browser via the prepare response. */
    public String appUrl(String sandboxId) {
        return "http://127.0.0.1:" + port + "/sandbox/app/" + sandboxId;
    }
}
