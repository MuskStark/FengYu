package fan.summer.fengyu.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * CORS for the Vite dev server and the Tauri webview. In production (Tauri) the frontend is
 * served from the same origin, but dev mode runs Vite on the loopback interface (nominally
 * {@code localhost:5173}, but Vite falls back to 5174+ if the port is taken) against the
 * loopback backend, and the Tauri webview loads from {@code tauri://localhost} (macOS) or
 * {@code http(s)://tauri.localhost} (Windows/Linux) while talking to {@code 127.0.0.1:<port>}.
 *
 * <p>Because the backend also binds a dynamic port ({@code HeadlessLauncher} falls back to an
 * OS-assigned port if 24056 is taken) and Vite's {@code changeOrigin} does not rewrite the
 * {@code Origin} header, we cannot enumerate exact origins. We use {@code allowedOriginPatterns}
 * (which permits {@code allowCredentials(true)}, unlike a literal {@code "*"} in
 * {@code allowedOrigins}) to accept loopback on any port plus the Tauri webview schemes.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Resolves the request locale from the {@code Accept-Language} header so plugin manifest
     * strings can be localized server-side. Supports {@code en}/{@code zh} (mirroring the frontend's
     * {@code LanguageName}) and defaults to {@code en} when the header is absent or unsupported —
     * which keeps single-language plugins rendering their top-level English defaults.
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE));
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://[::1]:*",
                "tauri://localhost",
                "http://tauri.localhost",
                "https://tauri.localhost")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true);
    }
}
