package fan.summer.fengyu.web;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Prints {@code FENGYU_PORT=<actual>} to stdout once the embedded web server is up, so the Tauri
 * sidecar can read the bound port. Printed unconditionally — the backend defaults to a fixed port
 * ({@code HeadlessLauncher.DEFAULT_PORT}) but falls back to {@code --server.port=0} if it is taken,
 * so the desktop shell always needs to read the actual port from this line.
 */
@Component
public class PortAnnouncer implements ApplicationListener<WebServerInitializedEvent> {

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        System.out.println("FENGYU_PORT=" + port);
        System.out.flush();
    }
}
