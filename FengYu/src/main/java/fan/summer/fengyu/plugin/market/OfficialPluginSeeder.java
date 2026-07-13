package fan.summer.fengyu.plugin.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Installs bundled/development official .fyp artifacts once and upgrades them when newer. */
@Component
public class OfficialPluginSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OfficialPluginSeeder.class);
    private final PluginPackageService packages;
    private final Path source;

    public OfficialPluginSeeder(PluginPackageService packages,
            @Value("${fengyu.plugins.official-directory:${user.dir}/OfficialPlugins/target/packages}") String source) {
        this.packages = packages; this.source = Path.of(source).toAbsolutePath().normalize();
    }

    @Override public void run(ApplicationArguments args) { seed(); }

    public synchronized void seed() {
        if (!Files.isDirectory(source)) return;
        try (var entries = Files.list(source)) {
            for (Path archive : entries.filter(p -> p.getFileName().toString().endsWith(".fyp")).toList()) {
                try {
                    String id = archive.getFileName().toString().replaceFirst("-\\d+\\.\\d+\\.\\d+.*\\.fyp$", "");
                    if (packages.find(id).isPresent()) continue;
                    // The package service performs the authoritative validation and atomic install.
                    PluginManifest incoming = packages.install(archive);
                    log.info("Official plugin ready: {} {}", incoming.id(), incoming.version());
                } catch (Exception e) {
                    log.warn("Cannot seed official plugin {}: {}", archive, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Cannot scan official plugin packages: {}", e.getMessage());
        }
    }
}
