package fan.summer.zhiflow.config;

import fan.summer.zhiflow.setup.DataSourceConfig;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Injects the Hibernate dialect from {@code datasource.properties} into JPA properties.
 * Active only in APP mode. The dialect is determined by the chosen {@link
 * fan.summer.zhiflow.setup.DbType} during setup and persisted.
 *
 * <p>Uses a {@code JpaPropertySource}-style approach: we set
 * {@code spring.jpa.properties.hibernate.dialect} as a system property before context refresh,
 * because Spring Boot's JPA auto-config reads it during EMF construction.
 */
@Configuration
@ConditionalOnProperty(name = "zhiflow.mode", havingValue = "app")
public class JpaConfig {

    public JpaConfig(DataSourceConfigService configService) {
        DataSourceConfig cfg = configService.load();
        if (cfg != null && cfg.dialect() != null) {
            // Set before EMF auto-config reads it.
            System.setProperty("spring.jpa.properties.hibernate.dialect", cfg.dialect());
        }
    }
}
