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
 * CORS for the Vite dev server and the Electron desktop webview. In the packaged desktop
 * build the SPA is served from the shell's {@code app://shell} custom protocol (a real,
 * non-opaque origin — see the desktop shell's app-protocol module) and its API calls cross
 * to the loopback backend; dev mode additionally runs Vite on the loopback interface
 * (nominally {@code localhost:5173}, but Vite falls back to 5174+ if the port is taken).
 *
 * <p>Because the backend also binds a dynamic port ({@code HeadlessLauncher} falls back to an
 * OS-assigned port if 24056 is taken) and Vite's {@code changeOrigin} does not rewrite the
 * {@code Origin} header, we cannot enumerate exact origins. We use {@code allowedOriginPatterns}
 * (which permits {@code allowCredentials(true)}, unlike a literal {@code "*"} in
 * {@code allowedOrigins}) to accept loopback on any port plus the desktop webview origin.
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
                "app://shell",
                "tauri://localhost",
                "http://tauri.localhost",
                "https://tauri.localhost")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true);
    }
}
