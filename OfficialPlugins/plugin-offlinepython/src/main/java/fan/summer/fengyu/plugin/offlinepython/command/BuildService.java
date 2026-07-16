package fan.summer.fengyu.plugin.offlinepython.command;

import fan.summer.fengyu.plugin.offlinepython.domain.BuildConfig;
import fan.summer.fengyu.plugin.offlinepython.domain.BuildSummary;
import fan.summer.fengyu.plugin.offlinepython.domain.DependencySpec;
import fan.summer.fengyu.plugin.offlinepython.domain.Manifest;
import fan.summer.fengyu.plugin.offlinepython.domain.RequirementsFile;
import fan.summer.fengyu.plugin.offlinepython.domain.WheelEntry;
import fan.summer.fengyu.plugin.offlinepython.infra.HashUtil;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;
import fan.summer.fengyu.plugin.offlinepython.infra.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Runs pip download for the configured target platform, then writes manifest + SHA256SUMS. */
public class BuildService {

    /** @return a BuildSummary describing the build outcome (wheels, cache hits, size, duration). */
    public BuildSummary build(
            Path projectDir, BuildConfig cfg, String pythonExecutable,
            Consumer<String> onLog, ProcessRunner runner) throws Exception {
        Path output = projectDir.resolve(cfg.getRepository().getOutput());
        // wheel 按目标 Python 版本分子目录存放:wheelhouse/<pythonVersion>/
        Path wheelhouse = output.resolve(cfg.getRepository().getWheelDir())
                .resolve(cfg.getPython().getVersion());
        Files.createDirectories(wheelhouse);

        int preExisting = countWheels(wheelhouse);
        List<DependencySpec> deps = readRequirements(projectDir);
        List<DepGroup> groups = groupByPlatform(deps,
                cfg.getPython().getDepPlatforms(), cfg.getPython().getPlatforms());
        boolean onlyBinary = cfg.getDownload().isOnlyBinary();
        boolean recursive = cfg.getDownload().isRecursive();

        long start = System.currentTimeMillis();
        for (DepGroup g : groups) {
            List<String> cmd = ProcessRunner.pipDownloadCommand(
                    pythonExecutable, g.specs, wheelhouse.toString(), g.platforms,
                    majorMinor(cfg.getPython().getVersion()), cfg.getPython().getImplementation(),
                    onlyBinary, recursive);
            onLog.accept("$ " + String.join(" ", cmd));
            int code = runner.run(cmd, onLog);
            if (code != 0) {
                long duration = System.currentTimeMillis() - start;
                return new BuildSummary(preExisting, preExisting, 0L, duration);
            }
        }
        long duration = System.currentTimeMillis() - start;
        writeManifest(projectDir, cfg, output, wheelhouse, unionPlatforms(groups));
        writeSha256Sums(output);
        return BuildSummary.compute(wheelhouse, preExisting, duration);
    }

    private int countWheels(Path wheelhouse) throws IOException {
        try (Stream<Path> files = Files.list(wheelhouse)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".whl")).count();
        }
    }

    void writeManifest(Path projectDir, BuildConfig cfg, Path output, Path wheelhouse,
                       List<String> platforms) throws IOException {
        List<WheelEntry> wheels = new ArrayList<>();
        List<String> reqNames = new ArrayList<>();
        for (DependencySpec d : readRequirements(projectDir)) reqNames.add(d.toString());

        try (Stream<Path> files = Files.list(wheelhouse)) {
            List<Path> sorted = files.sorted().toList();
            for (Path f : sorted) {
                if (!f.toString().endsWith(".whl")) continue;
                String name = wheelNamePart(f.getFileName().toString());
                String normName = DependencySpec.normalizeName(name);
                boolean required = reqNames.stream()
                        .anyMatch(r -> DependencySpec.normalizeName(r).startsWith(normName));
                wheels.add(new WheelEntry(
                        name, "", output.relativize(f).toString().replace('\\', '/'),
                        HashUtil.sha256Hex(f), Files.size(f), required));
            }
        }

        Manifest m = new Manifest();
        m.setSchemaVersion(1);
        m.getPython().setVersion(cfg.getPython().getVersion());
        m.getPython().setPlatforms(new ArrayList<>(platforms));
        m.setBuiltAt(java.time.OffsetDateTime.now().toString());
        m.setBuiltOn(System.getProperty("user.name"));
        m.setToolVersion("1.0.0");
        m.getWheels().addAll(wheels);
        m.getRequirements().addAll(reqNames);
        JsonStore.save(m, output.resolve("manifest.json"));
    }

    void writeSha256Sums(Path output) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(output)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".whl")
                              || p.getFileName().toString().toLowerCase().contains("python"))
                    .sorted().toList();
            for (Path f : files) {
                sb.append(HashUtil.sha256Hex(f)).append("  ")
                  .append(output.relativize(f).toString().replace('\\', '/'))
                  .append(System.lineSeparator());
            }
        }
        Files.writeString(output.resolve("SHA256SUMS"), sb.toString());
    }

    /** A build group: deps sharing one target-platform set, run in a single pip download. */
    static final class DepGroup {
        final List<String> platforms;
        final List<String> specs = new ArrayList<>();
        DepGroup(List<String> platforms) { this.platforms = platforms; }
    }

    /** Read requirements.txt into DependencySpecs (empty list if absent). */
    static List<DependencySpec> readRequirements(Path projectDir) throws IOException {
        Path reqs = projectDir.resolve("requirements.txt");
        if (!Files.exists(reqs)) return List.of();
        return RequirementsFile.parse(Files.readString(reqs));
    }

    /** Partition deps by their resolved platform set. Each dep's platforms come from
     *  depPlatforms[normalizeName(name)], falling back to defaultPlatforms. Pure, unit-tested. */
    static List<DepGroup> groupByPlatform(List<DependencySpec> deps,
                                          Map<String, List<String>> depPlatforms,
                                          List<String> defaultPlatforms) {
        Map<String, DepGroup> groups = new LinkedHashMap<>();
        for (DependencySpec d : deps) {
            List<String> plats = resolvePlatforms(d.name(), depPlatforms, defaultPlatforms);
            String key = platformKey(plats);
            DepGroup g = groups.computeIfAbsent(key, k -> new DepGroup(new ArrayList<>(plats)));
            g.specs.add(d.toString());
        }
        return new ArrayList<>(groups.values());
    }

    static List<String> resolvePlatforms(String name, Map<String, List<String>> depPlatforms,
                                         List<String> defaultPlatforms) {
        String norm = DependencySpec.normalizeName(name);
        if (depPlatforms != null && depPlatforms.containsKey(norm)) {
            List<String> p = depPlatforms.get(norm);
            if (p != null && !p.isEmpty()) return p;
        }
        return (defaultPlatforms == null || defaultPlatforms.isEmpty())
                ? List.of("win_amd64") : defaultPlatforms;
    }

    /** Stable key for a platform set (dedup + sort). */
    static String platformKey(List<String> plats) {
        return String.join(",", new TreeSet<>(plats));
    }

    /** Union of all group platforms, deduped, insertion-ordered. */
    static List<String> unionPlatforms(List<DepGroup> groups) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (DepGroup g : groups) seen.addAll(g.platforms);
        return new ArrayList<>(seen);
    }

    /** "numpy-1.26.4-cp312-...whl" -> "numpy". */
    static String wheelNamePart(String fileName) {
        int dash = fileName.indexOf('-');
        return dash < 0 ? fileName : fileName.substring(0, dash);
    }

    static String majorMinor(String version) {
        String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : version;
    }
}
