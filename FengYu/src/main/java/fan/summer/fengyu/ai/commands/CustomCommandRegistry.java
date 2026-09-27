package fan.summer.fengyu.ai.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovery for user-authored slash commands: markdown files with optional YAML-ish
 * frontmatter, scanned from two roots (terminal coding-agent practice):
 *
 * <ul>
 *   <li>user: {@code ~/.fengyu/commands/*.md}</li>
 *   <li>project (workspace-bound conversations only): {@code <workspace>/.fengyu/commands/*.md}</li>
 * </ul>
 *
 * <p>Frontmatter: a leading {@code ---} block whose {@code description:} line is extracted
 * (plain key:value parse — deliberately not a YAML dependency). The body after frontmatter
 * is the prompt template; {@code $ARGUMENTS} / {@code {{input}}} placeholders are replaced
 * by the frontend with the composer's current text at send time. A 5s snapshot cache keeps
 * the composer's {@code /} panel snappy without hammering the filesystem.</p>
 */
@Service
public class CustomCommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CustomCommandRegistry.class);

    /** One discovered command. {@code scope} is "user" or "project". */
    public record CustomCommand(String id, String name, String description,
                                String prompt, String scope) {}

    static final int MAX_COMMANDS = 100;
    static final int MAX_PROMPT_CHARS = 16_000;
    private static final long SNAPSHOT_TTL_MILLIS = 5_000;

    private record Snapshot(List<CustomCommand> user, List<CustomCommand> project,
                            long userAt, long projectAt) {}

    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    /** All commands visible to a conversation (user root + optional workspace root). */
    public List<CustomCommand> commands(Path workspaceRoot) {
        long now = System.currentTimeMillis();
        Snapshot cached = snapshots.get(key(workspaceRoot));
        if (cached != null && now - cached.userAt() < SNAPSHOT_TTL_MILLIS
                && now - cached.projectAt() < SNAPSHOT_TTL_MILLIS) {
            return merge(cached.user(), cached.project());
        }
        List<CustomCommand> user = cached == null || now - cached.userAt() >= SNAPSHOT_TTL_MILLIS
                ? scan(userRoot(), "user") : cached.user();
        List<CustomCommand> project = workspaceRoot == null
                ? List.of()
                : (cached == null || now - cached.projectAt() >= SNAPSHOT_TTL_MILLIS
                        ? scan(workspaceRoot.resolve(".fengyu/commands"), "project")
                        : cached.project());
        snapshots.put(key(workspaceRoot), new Snapshot(user, project, now, now));
        while (snapshots.size() > MAX_SNAPSHOT_ROOTS) {
            String oldest = snapshots.keySet().iterator().next();
            snapshots.remove(oldest);
        }
        return merge(user, project);
    }

    private static String key(Path workspaceRoot) {
        return workspaceRoot == null ? "" : workspaceRoot.toString();
    }

    /** Snapshot cache bound — one entry per distinct workspace root seen this session. */
    private static final int MAX_SNAPSHOT_ROOTS = 32;

    private static List<CustomCommand> merge(List<CustomCommand> user, List<CustomCommand> project) {
        List<CustomCommand> out = new ArrayList<>(user.size() + project.size());
        out.addAll(user);
        out.addAll(project);
        return out.size() > MAX_COMMANDS ? out.subList(0, MAX_COMMANDS) : out;
    }

    private static Path userRoot() {
        return Path.of(System.getProperty("user.home"), ".fengyu", "commands");
    }

    private static List<CustomCommand> scan(Path root, String scope) {
        if (!Files.isDirectory(root)) return List.of();
        List<CustomCommand> out = new ArrayList<>();
        try (var files = Files.list(root)) {
            files.filter(file -> Files.isRegularFile(file)
                            && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .limit(MAX_COMMANDS)
                    .forEach(file -> {
                        CustomCommand command = parse(file, scope);
                        if (command != null) out.add(command);
                    });
        } catch (IOException e) {
            log.debug("command scan failed for {}: {}", root, e.toString());
        }
        return out;
    }

    /** Parses one markdown command file; null when the body is empty/unreadable. */
    private static CustomCommand parse(Path file, String scope) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.length() > MAX_PROMPT_CHARS * 2) return null;
            String body = raw;
            String description = "";
            if (raw.startsWith("---")) {
                int end = raw.indexOf("\n---", 3);
                if (end > 0) {
                    for (String line : raw.substring(3, end).split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.toLowerCase(Locale.ROOT).startsWith("description:")) {
                            description = trimmed.substring("description:".length()).trim();
                            if (description.length() > 1
                                    && description.charAt(0) == description.charAt(description.length() - 1)
                                    && (description.charAt(0) == '"' || description.charAt(0) == '\'')) {
                                description = description.substring(1, description.length() - 1);
                            }
                        }
                    }
                    body = raw.substring(end + 4);
                }
            }
            String prompt = body.strip();
            if (prompt.isEmpty()) return null;
            String base = file.getFileName().toString();
            String name = base.substring(0, base.length() - 3).toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9-]", "-");
            if (name.isEmpty()) return null;
            return new CustomCommand(scope + ":" + name, name,
                    description == null ? "" : description, prompt, scope);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
