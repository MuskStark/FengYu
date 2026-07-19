package fan.summer.fengyu.ai.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Installs bundled/development official {@code .fys} artifacts once and skips when already
 * installed (idempotent). The lifecycle twin of {@code OfficialPluginSeeder}: same shape — scan
 * a directory for packaged archives, derive the skill id from the filename, skip if present,
 * otherwise hand the archive to {@link SkillPackageService} for the authoritative atomic install.
 *
 * <p>Runs at context start as an {@link ApplicationRunner}. All failures are caught and logged
 * as warnings so a bad archive can never block boot. The source directory defaults to
 * {@code ${user.dir}/OfficialSkills/target/packages} and may simply not exist in most setups —
 * that is fine, the seeder returns silently.
 *
 * <p><b>Note:</b> skills that ship inside the app JAR under {@code /skills/<id>/SKILL.md} are
 * discovered separately by {@link SkillRegistry} as {@link Skill.Source#BUILTIN} (never
 * installed, never uninstalled). This seeder is for the {@code .fys} packaging workflow that
 * mirrors the official plugin build pipeline.
 *
 * @since 4.0.0
 */
@Component
@Order(0)
public class OfficialSkillSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OfficialSkillSeeder.class);
    private final SkillPackageService packages;
    private final Path source;

    public OfficialSkillSeeder(SkillPackageService packages,
            @Value("${fengyu.skills.official-directory:${user.dir}/OfficialSkills/target/packages}") String source) {
        this.packages = packages;
        this.source = Path.of(source).toAbsolutePath().normalize();
    }

    @Override public void run(ApplicationArguments args) { seed(); }

    public synchronized void seed() {
        if (!Files.isDirectory(source)) return;
        try (var entries = Files.list(source)) {
            for (Path archive : entries.filter(p -> p.getFileName().toString().endsWith(".fys")).toList()) {
                try {
                    String id = archive.getFileName().toString().replaceFirst("-\\d+\\.\\d+\\.\\d+.*\\.fys$", "");
                    if (packages.find(id).isPresent()) continue;
                    // The package service performs the authoritative validation and atomic install.
                    SkillManifest incoming = packages.install(archive);
                    log.info("Official skill ready: {} {}", incoming.id(), incoming.version());
                } catch (Exception e) {
                    log.warn("Cannot seed official skill {}: {}", archive, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Cannot scan official skill packages: {}", e.getMessage());
        }
    }
}
