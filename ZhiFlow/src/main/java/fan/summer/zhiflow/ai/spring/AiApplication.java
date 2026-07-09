package fan.summer.zhiflow.ai.spring;

import fan.summer.zhiflow.setup.SetupApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Boot application for the headless ZhiFlow backend.
 *
 * <p>Scans {@code fan.summer.zhiflow}, so it picks up the web controllers, the plugin registry
 * service, and the AI {@code ChatModel} {@code @Bean}s. With {@code spring-boot-starter-web} on the
 * classpath it boots an embedded servlet web server (Tomcat); {@link fan.summer.zhiflow.HeadlessLauncher}
 * binds it to loopback via {@code --server.address}/{@code --server.port} args.
 *
 * <p>The JPA entities and repositories live under {@code fan.summer.zhiflow.database}, which is a
 * SIBLING of this class's package ({@code fan.summer.zhiflow.ai.spring}), not a descendant — so the
 * default {@code @SpringBootApplication} base-package scan (rooted here) would miss them. The
 * explicit {@link EntityScan} and {@link EnableJpaRepositories} widen discovery to the
 * {@code database} subpackages.
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "fan.summer.zhiflow",
        // SetupApplication is a SIBLING Spring Boot entry point (a standalone
        // @SpringBootApplication that excludes DataSource/JPA auto-config for SETUP mode). It is
        // launched directly by HeadlessLauncher — it must NOT be component-scanned into THIS
        // (APP-mode) context, otherwise its @EnableAutoConfiguration(exclude=...) leaks into the
        // merged auto-config and suppresses DataSourceAutoConfiguration/HibernateJpaAutoConfiguration
        // here, which would remove the entityManagerFactory bean. Its setup-package @Components
        // (e.g. DataSourceConfigService) are still picked up individually.
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = SetupApplication.class))
@EntityScan(basePackages = "fan.summer.zhiflow.database.entity")
@EnableJpaRepositories(basePackages = "fan.summer.zhiflow.database.repository")
public class AiApplication {
}
