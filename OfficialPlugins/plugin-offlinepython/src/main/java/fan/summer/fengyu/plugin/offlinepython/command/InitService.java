package fan.summer.fengyu.plugin.offlinepython.command;

import fan.summer.fengyu.plugin.offlinepython.domain.BuildConfig;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the project skeleton: config.json, requirements.txt, README.md. */
public class InitService {

    private static final String REQ_SKELETON =
            "# Add packages, one per line, e.g.  numpy==1.26.4\n";

    public void initialize(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);

        Path req = projectDir.resolve("requirements.txt");
        if (!Files.exists(req)) Files.writeString(req, REQ_SKELETON);

        Path cfg = projectDir.resolve("config.json");
        if (!Files.exists(cfg)) JsonStore.save(BuildConfig.defaults(), cfg);

        Path readme = projectDir.resolve("README.md");
        if (!Files.exists(readme)) {
            Files.writeString(readme, """
                # Offline Python Repository

                Open this project in the Offline Python Builder plugin,
                edit dependencies, then Build.
                """);
        }
    }
}
