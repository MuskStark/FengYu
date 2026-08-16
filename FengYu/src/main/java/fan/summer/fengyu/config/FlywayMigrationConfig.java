package fan.summer.fengyu.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Forces the Flyway migration settings ON programmatically, independent of Spring's
 * {@code spring.flyway.*} property binding.
 *
 * <p><b>Why this exists.</b> Same class of bug {@link HibernateDdlConfig} documents: in the
 * maven-shade uber-jar the {@code spring.*} configuration-property binding for these
 * auto-configured subsystems silently fails, so {@code application.yml}'s
 * {@code spring.flyway.baseline-on-migrate} never reaches Flyway. With the binding broken,
 * Flyway's default {@code baselineOnMigrate=false} makes the very first boot against an
 * EXISTING ddl-auto-created schema abort with "Found non-empty schema(s) ... but no schema
 * history table" (reproduced against the packaged jar). A customizer bean is collected by
 * {@code FlywayAutoConfiguration} while it builds the {@link FluentConfiguration}, so writing
 * the settings here reaches Flyway regardless of the broken binding.
 *
 * <p>Policy (mirrors application.yml, which stays authoritative for exploded-classpath runs):
 * <ul>
 *   <li>V1 is an empty baseline — fresh installs execute it from zero.</li>
 *   <li>Existing installs (schema present, no history table) are BASELINED at V1 instead of
 *       failing, so upgrading an installation never bricks its database.</li>
 *   <li>V2+ under {@code db/migration} is the versioned channel for what
 *       {@code ddl-auto=update} cannot express (renames, drops, data backfills).</li>
 * </ul>
 *
 * <p>Only active in APP mode ({@code fengyu.mode=app}); SETUP mode has no datasource and no
 * Flyway auto-configuration at all.
 */
@Configuration
@ConditionalOnProperty(name = "fengyu.mode", havingValue = "app")
public class FlywayMigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationConfig.class);

    @Bean
    FlywayConfigurationCustomizer flywayBaselineCustomizer() {
        return configuration -> {
            configuration.baselineOnMigrate(true);
            configuration.baselineVersion("1");
            configuration.locations("classpath:db/migration");
            log.info("Flyway versioned migrations enabled (baseline-on-migrate at V1; "
                    + "V2+ under db/migration carry renames/drops/data fixes)");
        };
    }
}
