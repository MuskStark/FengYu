package fan.summer.zhiflow.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Vite dev server and the Tauri webview. In production (Tauri) the frontend is
 * served from the same origin, but dev mode runs Vite on {@code localhost:5173} against the
 * loopback backend, so those origins are allowed. {@code tauri://localhost} covers the desktop
 * webview origin.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOrigins(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "tauri://localhost",
                "http://tauri.localhost")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true);
    }
}
