package fan.summer.fengyu;

import fan.summer.fengyu.setup.SetupApplication;
import fan.summer.fengyu.setup.SetupController;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * APP-mode Spring Boot application for the headless FengYu backend.
 *
 * <p>This is the full application context {@link HeadlessLauncher} boots once the datasource has
 * been configured and probed: it picks up the web controllers, the plugin registry service, the
 * JPA entities/repositories under {@code fan.summer.fengyu.database}, and the AI {@code ChatModel}
 * {@code @Bean}s under {@code fan.summer.fengyu.ai}. With {@code spring-boot-starter-web} on the
 * classpath it boots an embedded servlet web server (Tomcat); {@link HeadlessLauncher} binds it to
 * loopback via {@code --server.address}/{@code --server.port} args.
 *
 * <p>Living in the root package {@code fan.summer.fengyu}, the default {@code @SpringBootApplication}
 * base-package scan already covers every subpackage — including {@code database} (JPA), {@code ai},
 * {@code web}, and {@code plugin} — so no explicit {@code @EntityScan}/{@code @EnableJpaRepositories}
 * is needed. This is the conventional Spring Boot layout: the main class sits at the top of the
 * package tree it scans.
 *
 * <p>The only carve-out is {@link SetupApplication}, a SIBLING Spring Boot entry point (a standalone
 * {@code @SpringBootApplication} that excludes DataSource/JPA auto-config for SETUP mode). It is
 * launched directly by {@link HeadlessLauncher} and must NOT be component-scanned into THIS
 * (APP-mode) context, otherwise its {@code @EnableAutoConfiguration(exclude=...)} would leak into
 * the merged auto-config and suppress DataSource/Hibernate auto-config here, removing the
 * {@code entityManagerFactory} bean. Its setup-package {@code @Component}s (e.g.
 * {@code DataSourceConfigService}) are still picked up individually.
 *
 * <p>{@link SetupController} is excluded alongside it: the wizard endpoints are unauthenticated
 * ({@code TokenAuthFilter} whitelists {@code /api/setup/**}) and must only exist in the SETUP-mode
 * context. In APP mode the equivalent reset path is the token-protected
 * {@code SettingsController} endpoint, so serving {@code /api/setup/**} here would expose
 * unauthenticated database reconfiguration (and deletion) forever after setup completes.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(
        basePackages = "fan.summer.fengyu",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SetupApplication.class, SetupController.class}))
public class FengYuApplication {
}
