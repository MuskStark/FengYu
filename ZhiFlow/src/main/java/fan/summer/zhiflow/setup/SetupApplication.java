package fan.summer.zhiflow.setup;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

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
 * <p>Scans only the {@code setup} package plus {@code fan.summer.zhiflow.web} (for
 * {@code PortAnnouncer} and {@code TokenAuthFilter}).
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@ComponentScan(basePackages = {"fan.summer.zhiflow.setup", "fan.summer.zhiflow.web"})
public class SetupApplication {
}
