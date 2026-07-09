package fan.summer.zhiflow.setup;

import fan.summer.zhiflow.web.controller.AiController;
import fan.summer.zhiflow.web.controller.PluginController;
import fan.summer.zhiflow.web.controller.SettingsController;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * SETUP-mode Spring Boot application — a minimal context that serves only the setup wizard.
 *
 * <p>Excludes {@link DataSourceAutoConfiguration} and {@link HibernateJpaAutoConfiguration} so the
 * context starts with zero DB/JPA dependency. The wizard's test/initialize endpoints open raw
 * JDBC connections on demand via {@link DataSourceConfigService} and never touch this context.
 *
 * <p>DDL is deferred entirely to APP-mode startup: on restart, Hibernate {@code ddl-auto=update}
 * (from {@code application.yml}) creates the schema from the entities, and
 * {@code VirtualUserInitializer} inserts the virtual user id=1. Both already exist and are tested,
 * so SETUP mode needs no schema machinery at all.
 *
 * <p>Scans the {@code setup} package plus {@code fan.summer.zhiflow.web}. The {@code web} package
 * supplies the infrastructure SETUP mode still needs — {@code PortAnnouncer} (so Tauri reads the
 * bound port), {@code TokenAuthFilter}, {@code HealthController} (readiness probe),
 * {@code WebConfig} (CORS for the Vite dev server), and {@code GlobalExceptionHandler} (clean 400s
 * for the wizard). It is NOT scanned wholesale, though: the three APP-only controllers are
 * excluded via {@code excludeFilters} because they depend on beans that do not exist in this
 * minimal context — {@link PluginController} needs {@code PluginRegistryService},
 * {@link SettingsController} needs {@code AiConfigServiceHeadless}, and {@link AiController} is
 * meaningless before setup completes. This mirrors the {@code excludeFilters} idiom already used
 * by {@code AiApplication} on the opposite side (it excludes this class).
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@ComponentScan(
        basePackages = {"fan.summer.zhiflow.setup", "fan.summer.zhiflow.web"},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {PluginController.class, SettingsController.class, AiController.class}))
public class SetupApplication {
}
