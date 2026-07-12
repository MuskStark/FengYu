package fan.summer.fengyu.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Forces Hibernate schema management ON in APP mode, independent of Spring's
 * {@code spring.jpa.*} property binding.
 *
 * <p><b>Why this exists.</b> The backend ships as a {@code maven-shade-plugin} uber-jar. In that
 * shaded jar Spring Boot fails to bind the {@code spring.jpa.*} configuration subtree — the
 * {@code JpaProperties}/{@code HibernateProperties} beans never pick up {@code application.yml}
 * (proven: the "spring.jpa.open-in-view is enabled by default" warning fires even though the yml
 * sets it {@code false}, and a {@code --spring.jpa.hibernate.ddl-auto=update} CLI override is
 * ignored too). Consequently {@code ddl-auto} defaults to {@code none} for the non-embedded
 * SQLite/MySQL/PostgreSQL datasources, so Hibernate creates NO tables and the app crashes at
 * {@code VirtualUserInitializer} (missing {@code sys_user}) on a fresh DB, or silently omits new
 * entities' tables on an existing DB. From an exploded classpath (IntelliJ) the binding works, so
 * the bug only manifests in the packaged product.
 *
 * <p>A {@link HibernatePropertiesCustomizer} is collected and applied while Spring Boot builds the
 * {@code EntityManagerFactory} (that step demonstrably runs — JPA queries work), so writing
 * {@code hibernate.hbm2ddl.auto} directly into the Hibernate properties map here reaches Hibernate
 * regardless of the broken {@code spring.jpa} binding. {@code update} is additive: it creates
 * missing tables/columns and never drops data.
 *
 * <p>Only active in APP mode ({@code fengyu.mode=app}); SETUP mode has no datasource.
 */
@Configuration
@ConditionalOnProperty(name = "fengyu.mode", havingValue = "app")
public class HibernateDdlConfig {

    private static final Logger log = LoggerFactory.getLogger(HibernateDdlConfig.class);

    @Bean
    public HibernatePropertiesCustomizer ddlAutoUpdateCustomizer() {
        return (Map<String, Object> hibernateProperties) -> {
            // Only set a default when nothing upstream already did — respects an explicit
            // value if the binding is ever fixed, but guarantees a value in the shaded jar.
            hibernateProperties.putIfAbsent("hibernate.hbm2ddl.auto", "update");
            log.info("Hibernate hbm2ddl.auto forced to '{}' (shaded-jar spring.jpa binding workaround)",
                    hibernateProperties.get("hibernate.hbm2ddl.auto"));
        };
    }
}
