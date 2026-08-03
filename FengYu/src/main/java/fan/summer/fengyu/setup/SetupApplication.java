package fan.summer.fengyu.setup;

import fan.summer.fengyu.web.controller.AgentController;
import fan.summer.fengyu.web.controller.AiConfigController;
import fan.summer.fengyu.web.controller.AiController;
import fan.summer.fengyu.web.controller.AiFileController;
import fan.summer.fengyu.web.controller.ConversationController;
import fan.summer.fengyu.web.controller.PluginController;
import fan.summer.fengyu.web.controller.PluginMarketplaceController;
import fan.summer.fengyu.web.controller.PluginRuntimeController;
import fan.summer.fengyu.web.controller.PluginRuntimeFileController;
import fan.summer.fengyu.web.controller.PluginStoreController;
import fan.summer.fengyu.web.controller.McpController;
import fan.summer.fengyu.web.controller.SecurityController;
import fan.summer.fengyu.web.controller.SettingsController;
import fan.summer.fengyu.web.controller.SkillController;
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
 * <p>Scans the {@code setup} package plus {@code fan.summer.fengyu.web}. The {@code web} package
 * supplies the infrastructure SETUP mode still needs — {@code PortAnnouncer} (so Tauri reads the
 * bound port), {@code TokenAuthFilter}, {@code HealthController} (readiness probe),
 * {@code WebConfig} (CORS for the Vite dev server), and {@code GlobalExceptionHandler} (clean 400s
 * for the wizard). It is NOT scanned wholesale, though: the APP-only controllers are
 * excluded via {@code excludeFilters} because they depend on beans that do not exist in this
 * minimal context — {@link PluginController} needs {@code PluginRegistryService},
 * {@link SettingsController} needs {@code AiConfigServiceHeadless}, {@link AgentController}
 * needs {@code AgentRunner}/{@code ToolCallback}, {@link AiController} is
 * meaningless before setup completes, and {@link AiConfigController} needs
 * {@code AiModeService}/{@code BackendReactivator} (which live in the {@code ai} package, not
 * scanned here). {@link ConversationController} needs the AI-history JPA repositories, absent in
 * this DB-less context. {@link SkillController} needs {@code SkillRegistry}/
 * {@code SkillPackageService}/{@code SkillMarketplaceService} from the {@code ai.skill} package,
 * which this context does not scan. {@link SecurityController} depends on the APP-mode
 * {@code ProcessSandbox} component, while {@link McpController} reports APP-mode MCP clients, so
 * both are excluded as well. This mirrors the {@code excludeFilters} idiom already used
 * by {@link fan.summer.fengyu.FengYuApplication} on the opposite side (it excludes this class).
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@ComponentScan(
        basePackages = {"fan.summer.fengyu.setup", "fan.summer.fengyu.web"},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {PluginController.class, PluginMarketplaceController.class,
                        PluginRuntimeController.class,
                        PluginRuntimeFileController.class,
                        PluginStoreController.class,
                        SettingsController.class,
                        AiController.class, AiFileController.class, AiConfigController.class, AgentController.class,
                        ConversationController.class, SkillController.class,
                        McpController.class, SecurityController.class}))
public class SetupApplication {
}
