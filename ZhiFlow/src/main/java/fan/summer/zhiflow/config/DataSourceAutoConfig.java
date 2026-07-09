package fan.summer.zhiflow.config;

import com.zaxxer.hikari.HikariDataSource;
import fan.summer.zhiflow.setup.DataSourceConfig;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Constructs the {@link DataSource} bean in APP mode from the persisted
 * {@code datasource.properties}. Uses HikariCP. Only active when
 * {@code zhiflow.mode=app} (set by {@code HeadlessLauncher}).
 *
 * <p>In SETUP mode this bean is absent — Spring's DataSource auto-config is excluded via
 * {@code AiApplication}'s excludes, so the minimal context starts without any DB dependency.
 */
@Configuration
@ConditionalOnProperty(name = "zhiflow.mode", havingValue = "app")
public class DataSourceAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceAutoConfig.class);

    @Bean
    public DataSource dataSource(DataSourceConfigService configService) {
        DataSourceConfig cfg = configService.load();
        if (cfg == null) {
            throw new IllegalStateException(
                    "datasource.properties missing but zhiflow.mode=app — corrupted state");
        }
        log.info("Configuring DataSource: type={}, url={}", cfg.type(), cfg.url());
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(cfg.url());
        ds.setDriverClassName(cfg.driver());
        if (cfg.username() != null && !cfg.username().isBlank()) {
            ds.setUsername(cfg.username());
            ds.setPassword(cfg.password());   // already decrypted by load()
        }
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        return ds;
    }
}
