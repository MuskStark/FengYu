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
 * installed (idempotent). Scans an explicitly configured directory for packaged archives,
 * derives the skill id from the filename, skips if present, otherwise hands the archive to
 * {@link SkillPackageService} for the authoritative atomic install.
 *
 * <p>Runs at context start as an {@link ApplicationRunner}. All failures are caught and logged
 * as warnings so a bad archive can never block boot. The source directory MUST be set
 * explicitly via {@code fengyu.skills.official-directory}: the legacy implicit default
 * ({@code ${user.dir}/OfficialSkills/target/packages}) is gone — the OfficialSkills tree
 * left this repository when official skills moved to the store, and an implicit trusted
 * install path pointing at a developer-working-directory convention is a leftover no
 * production build should carry. A blank/unset property simply disables the seeder.</p>
 *
 * <p><b>Note:</b> skills that ship inside the app JAR under {@code /skills/<id>/SKILL.md} are
 * discovered separately by {@link SkillRegistry} as {@link Skill.Source#BUILTIN} (never
 * installed, never uninstalled). This seeder is for the {@code .fys} packaging workflow that
 * mirrors the official plugin build pipeline.</p>
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
            @Value("${fengyu.skills.official-directory:}") String source) {
        this.packages = packages;
        // No implicit default: an empty property disables the seeder entirely.
        this.source = (source == null || source.isBlank())
                ? null
                : Path.of(source).toAbsolutePath().normalize();
    }

    @Override public void run(ApplicationArguments args) { seed(); }

    public synchronized void seed() {
        if (source == null || !Files.isDirectory(source)) return;
        try (var entries = Files.list(source)) {
            for (Path archive : entries.filter(p -> p.getFileName().toString().endsWith(".fys")).toList()) {
                try {
                    String id = archive.getFileName().toString().replaceFirst("-\\d+\\.\\d+\\.\\d+.*\\.fys$", "");
                    if (packages.find(id).isPresent()) continue;
                    // The package service performs the authoritative validation and
                    // atomic install; the bundled seeder is a trusted source, so a
                    // package may carry the official identity (review M-6).
                    SkillManifest incoming = packages.installTrusted(archive);
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
