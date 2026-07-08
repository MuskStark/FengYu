package fan.summer.zhiflow.web;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Prints {@code ZHIFLOW_PORT=<actual>} to stdout once the embedded web server is up, so the Tauri
 * sidecar can read the chosen port (needed when launched with {@code --server.port=0}). Printed
 * unconditionally — a fixed port is echoed too, which the sidecar reads the same way.
 */
@Component
public class PortAnnouncer implements ApplicationListener<WebServerInitializedEvent> {

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        System.out.println("ZHIFLOW_PORT=" + port);
        System.out.flush();
    }
}
