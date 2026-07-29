package fan.summer.fengyu.ai.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers {@link Skill}s from two sources and resolves their enabled state.
 *
 * <ol>
 *   <li><b>Builtin</b> — classpath {@code /skills/<id>/SKILL.md}, packaged inside the app JAR.
 *       Shipped with every release; cannot be uninstalled (disable instead).</li>
 *   <li><b>Installed</b> — a {@code .fys} package extracted under
 *       {@code <programWorkingDirectory>/skills/<id>/} by {@link SkillPackageService}. This is
 *       the lifecycle twin of an installed plugin: full install/uninstall/enable/disable via the
 *       same filesystem-marker pattern.</li>
 * </ol>
 *
 * <p>An installed skill with the same id as a builtin one <em>overrides</em> it — users can
 * tailor shipped guidance without forking the JAR. The installed source wins because it is
 * scanned second into a {@link LinkedHashMap} keyed by id.
 *
 * <h2>Metadata vs body</h2>
 * <p>For installed skills, metadata (name/description/version/...) comes from the package's
 * {@code manifest.json} (the authoritative {@link SkillManifest}); the body is read from the
 * sibling {@code SKILL.md}. For builtin skills there is no manifest — both metadata and body
 * come from the {@code SKILL.md} YAML frontmatter (+ the markdown after it). This keeps
 * builtin authoring a single-file affair while giving installed skills the structured
 * manifest the marketplace needs.
 *
 * <h2>Discovery cadence</h2>
 * <p>Both sources are re-scanned on every {@link #all()} / {@link #enabled()} / {@link #find}
 * call. Skill counts are small and there is no watch service — this mirrors
 * {@code PluginPackageService.installed()} (same synchronous filesystem scan strategy) and
 * means a freshly installed skill is visible without a restart.
 *
 * @since 4.0.0
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final long MAX_RESOURCE_BYTES = 1024L * 1024L;

    /** Classpath location of builtin skills (inside the JAR). */
    private static final String BUILTIN_PATTERN = "/skills/*/SKILL.md";

    /** Matches the YAML frontmatter block and captures its body and the markdown after it. */
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n?(.*)", Pattern.DOTALL);

    private final SkillPackageService packages;

    public SkillRegistry(SkillPackageService packages) {
        this.packages = packages;
    }

    // ── Public API ───────────────────────────────────────────────────

    /** Every discovered skill (builtin + installed, installed overriding builtin on id clash). */
    public List<Skill> all() {
        Map<String, Skill> byId = new LinkedHashMap<>();
        for (Skill s : scanBuiltin()) byId.putIfAbsent(s.id(), s);
        for (Skill s : scanInstalled()) byId.put(s.id(), s);
        List<Skill> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(Skill::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** All discovered skills whose effective enabled state is true. */
    public List<Skill> enabled() {
        return all().stream().filter(this::isEnabled).toList();
    }

    /** Look up a single skill by id (used by the {@code skill} tool to load a body). */
    public Optional<Skill> find(String id) {
        if (id == null) return Optional.empty();
        return all().stream().filter(s -> id.equals(s.id())).findFirst();
    }

    /**
     * Reads a text resource referenced by a skill body. The path is always resolved below the
     * effective skill root; absolute paths and traversal are rejected.
     */
    public Optional<String> readResource(String id, String relativePath) {
        Optional<Skill> skill = find(id);
        if (skill.isEmpty() || !isEnabled(skill.get())) return Optional.empty();
        Path relative = safeRelativePath(relativePath);
        try {
            byte[] bytes;
            if (skill.get().source() == Skill.Source.INSTALLED) {
                Path root = packages.directory(id).toAbsolutePath().normalize();
                Path resource = root.resolve(relative).normalize();
                if (!resource.startsWith(root) || !Files.isRegularFile(resource)) {
                    return Optional.empty();
                }
                if (Files.size(resource) > MAX_RESOURCE_BYTES) {
                    throw new IllegalArgumentException("Skill resource exceeds 1 MB");
                }
                bytes = Files.readAllBytes(resource);
            } else {
                Resource resource = new ClassPathResource(
                        "skills/" + id + "/" + relative.toString().replace('\\', '/'));
                if (!resource.exists()) return Optional.empty();
                bytes = resource.getContentAsByteArray();
                if (bytes.length > MAX_RESOURCE_BYTES) {
                    throw new IllegalArgumentException("Skill resource exceeds 1 MB");
                }
            }
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read skill resource", e);
        }
    }

    private static Path safeRelativePath(String value) {
        if (value == null || value.isBlank() || value.contains("\\")
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid skill resource path");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().isBlank()) {
            throw new IllegalArgumentException("Invalid skill resource path");
        }
        return path;
    }

    /**
     * The effective enabled state. Installed skills consult their {@code .disabled} marker via
     * {@link SkillPackageService#isEnabled}. Builtin skills are always enabled — they ship in
     * the JAR and have no install directory to hold a marker; disabling them is not supported
     * (the controller returns 409 for that case). To suppress a builtin, install an overriding
     * skill of the same id and disable that.
     */
    public boolean isEnabled(Skill skill) {
        if (skill.source() == Skill.Source.INSTALLED) {
            return packages.isEnabled(skill.id());
        }
        return true;
    }

    /** Persist a new enabled override for an installed skill (delegates to the package service). */
    public void setEnabled(String id, boolean enabled) throws IOException {
        packages.setEnabled(id, enabled);
    }

    // ── Builtin scan (classpath, inside the JAR) ─────────────────────

    private List<Skill> scanBuiltin() {
        List<Skill> out = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(BUILTIN_PATTERN);
            for (Resource res : resources) {
                skillFromBuiltinResource(res).ifPresent(out::add);
            }
        } catch (IOException e) {
            // Not fatal — builtin skills are best-effort; the app still runs without them.
            log.debug("No builtin skills found on classpath: {}", e.toString());
        }
        return out;
    }

    private Optional<Skill> skillFromBuiltinResource(Resource res) {
        try {
            String text = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
            // Derive the id from the path: .../skills/<id>/SKILL.md
            String url = res.getURL().toString();
            int idx = url.lastIndexOf("/skills/");
            String id = idx >= 0 ? url.substring(idx + 8).replaceFirst("/SKILL\\.md$", "") : "";
            ParsedSkill parsed = parseFromSkillMd(id, text);
            return Optional.of(new Skill(parsed.id(), parsed.name(), parsed.description(),
                    parsed.body(), Skill.Source.BUILTIN));
        } catch (Exception e) {
            log.warn("Failed to read builtin skill {}: {}", res, e.toString());
            return Optional.empty();
        }
    }

    // ── Installed scan (filesystem, via SkillPackageService) ─────────

    private List<Skill> scanInstalled() {
        List<Skill> out = new ArrayList<>();
        for (SkillManifest manifest : packages.installed()) {
            try {
                Path dir = packages.directory(manifest.id());
                Path skillFile = dir.resolve("SKILL.md");
                String body = Files.isRegularFile(skillFile)
                        ? Files.readString(skillFile, StandardCharsets.UTF_8) : "";
                // Strip a leading frontmatter block from the body if present (the manifest is
                // already authoritative for installed-skill metadata); show only the guidance.
                body = stripFrontmatter(body);
                out.add(new Skill(manifest.id(), manifest.name(),
                        manifest.description() == null ? "" : manifest.description(),
                        body, Skill.Source.INSTALLED));
            } catch (Exception e) {
                log.warn("Failed to read installed skill {}: {}", manifest.id(), e.toString());
            }
        }
        return out;
    }

    // ── SKILL.md parsing ─────────────────────────────────────────────

    /**
     * Parse a builtin {@code SKILL.md} (frontmatter + body) into id/name/description/body.
     * The id is taken from the directory (never trusted from frontmatter), so two skills can
     * never collide on a hand-edited id. Missing {@code name}/{@code description} degrade to
     * the id / empty string rather than failing the whole skill.
     */
    private ParsedSkill parseFromSkillMd(String id, String text) {
        String name = id;
        String description = "";
        String body = text == null ? "" : text.strip();
        if (text != null) {
            Matcher m = FRONTMATTER_PATTERN.matcher(text);
            if (m.find()) {
                Map<String, String> fm = parseFrontmatter(m.group(1));
                name = fm.getOrDefault("name", id);
                description = fm.getOrDefault("description", "");
                body = m.group(2).strip();
            }
        }
        return new ParsedSkill(id, name, description, body);
    }

    /** Remove a leading YAML frontmatter block, returning only the markdown body. */
    private static String stripFrontmatter(String text) {
        if (text == null) return "";
        Matcher m = FRONTMATTER_PATTERN.matcher(text);
        return m.find() ? m.group(2).strip() : text.strip();
    }

    /**
     * Tiny line-oriented YAML reader for the flat {@code key: value} frontmatter skills use.
     * Intentionally not a general YAML parser — adding SnakeYAML just for two fields would be
     * over-engineering. Descriptions are single-line by convention.
     */
    private static Map<String, String> parseFrontmatter(String block) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : block.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    /** Internal carrier for parsed frontmatter + body. */
    private record ParsedSkill(String id, String name, String description, String body) {}
}
