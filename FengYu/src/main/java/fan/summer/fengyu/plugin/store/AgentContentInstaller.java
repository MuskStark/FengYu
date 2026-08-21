package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.PluginInstallRecordEntity;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Installs Claude/Codex/Grok plugins by cloning their git source (JGit), verifying the pinned sha,
 * reading plugin.json, and materializing skills + MCP-server configs into the runtime tree.
 *
 * @since 4.0.0
 */
@Service
public class AgentContentInstaller {
    private static final Logger log = LoggerFactory.getLogger(AgentContentInstaller.class);

    private final PluginInstallRecordRepository records;
    private final Path runtimeRoot;
    private final long cloneTimeoutSeconds;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    // Constructor used by Spring (runtimeRoot comes from RuntimePaths at bean-creation time
    // via a config that injects RuntimePaths.root(); see AgentContentInstallerConfig below).
    // The @Value annotations are read by Spring's bean factory for DI; a direct `new` call
    // (e.g. from tests) supplies plain Path/long arguments and ignores the annotations, so a
    // single constructor serves both paths and avoids an erased-signature duplicate.
    public AgentContentInstaller(PluginInstallRecordRepository records,
            @Value("#{T(fan.summer.fengyu.runtime.RuntimePaths).root()}") Path runtimeRoot,
            @Value("${fengyu.store.git-clone-timeout-seconds:120}") long cloneTimeoutSeconds) {
        this.records = records;
        this.runtimeRoot = runtimeRoot;
        this.cloneTimeoutSeconds = cloneTimeoutSeconds;
    }

    /** Install (or update) an agent-content plugin. */
    public void install(UnifiedCatalogEntry entry) {
        Path skillDest = resolveSkillPath(entry.uid());
        Path cloneDir = null;
        String resolvedSha = entry.pinnedSha();
        try {
            CloneResult clone = cloneSource(entry);
            cloneDir = clone.cloneDir();
            resolvedSha = clone.resolvedSha(); // pinned sha, or HEAD resolved when the catalog declared none
            Path pluginRoot = resolvePluginRoot(cloneDir, entry);
            Path manifest = manifestPath(pluginRoot, entry);
            JsonNode pluginJson = json.readTree(Files.readString(manifest));
            String version = text(pluginJson, "version");

            deleteRecursive(skillDest);
            List<String> skillPaths = extractSkills(pluginJson, pluginRoot, skillDest, entry.uid());

            boolean hasMcp = pluginJson.has("mcpServers") && !pluginJson.get("mcpServers").isNull()
                && !pluginJson.get("mcpServers").isEmpty();
            List<String> mcpRefs = hasMcp
                ? List.of(writeMcpConfig(pluginJson.get("mcpServers"), entry.uid()))
                : List.of();

            upsertRecord(entry, version, resolvedSha, skillDest, skillPaths, mcpRefs, hasMcp);
            log.info("Installed agent-content plugin {} (version={})", entry.uid(), version);
        } catch (IntegrityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Install failed for " + entry.uid(), e);
        } finally {
            if (cloneDir != null) deleteRecursive(cloneDir); // never leave the cloned .git behind
        }
    }

    public void uninstall(String uid) {
        records.findByUidAndUserId(uid, SecurityConstants.LOCAL_VIRTUAL_USER_ID).ifPresent(rec -> {
            // Defense-in-depth: never let a crafted uid delete outside the runtime tree,
            // even if some future code path leaks an unsanitized value into the records.
            if (PluginContentPathSafety.isInside(runtimeRoot, runtimeRoot.resolve("skills").resolve(uid))) {
                deleteRecursive(runtimeRoot.resolve("skills").resolve(uid));
            }
            if (PluginContentPathSafety.isInside(runtimeRoot, runtimeRoot.resolve("mcp-servers").resolve(uid + ".json"))) {
                deleteRecursive(runtimeRoot.resolve("mcp-servers").resolve(uid + ".json"));
            }
            records.delete(rec);
        });
    }

    public void setEnabled(String uid, boolean enabled) {
        records.findByUidAndUserId(uid, SecurityConstants.LOCAL_VIRTUAL_USER_ID).ifPresent(rec -> {
            rec.setEnabled(enabled);
            rec.setUpdatedAt(LocalDateTime.now());
            records.save(rec);
            // Mirror the enable state to a .disabled marker under the skill dir. The skill loader
            // (SkillRegistry via SkillPackageService.isEnabled) reads enable state from this marker,
            // not from the install record, so toggling only the DB row left the skill loaded.
            try {
                Path skillDir = runtimeRoot.resolve("skills").resolve(uid);
                if (PluginContentPathSafety.isInside(runtimeRoot, skillDir) && Files.isDirectory(skillDir)) {
                    Path marker = skillDir.resolve(".disabled");
                    if (enabled) Files.deleteIfExists(marker);
                    else Files.createFile(marker);
                }
            } catch (IOException e) {
                log.warn("Could not sync .disabled marker for {}: {}", uid, e.toString());
            }
        });
    }

    // --- internals ------------------------------------------------------------------

    /**
     * Resolves {@code <runtimeRoot>/skills/<uid>} and asserts it stays inside the runtime tree.
     * The uid segment is slugified by the catalog adapters, but this guard ensures no future code
     * path can use the installer to write/delete outside the runtime root via a crafted uid.
     */
    private Path resolveSkillPath(String uid) {
        Path p = runtimeRoot.resolve("skills").resolve(uid).normalize();
        if (!PluginContentPathSafety.isInside(runtimeRoot, p)) {
            throw new IllegalArgumentException("Refusing path outside runtime root: " + uid);
        }
        return p;
    }

    private Path resolveMcpConfigPath(String uid) {
        Path p = runtimeRoot.resolve("mcp-servers").resolve(uid + ".json").normalize();
        if (!PluginContentPathSafety.isInside(runtimeRoot, p)) {
            throw new IllegalArgumentException("Refusing path outside runtime root: " + uid);
        }
        return p;
    }

    /** Result of a clone: the local clone dir plus the sha that was verified (or resolved). */
    private record CloneResult(Path cloneDir, String resolvedSha) {}

    private CloneResult cloneSource(UnifiedCatalogEntry entry) throws Exception {
        Path cloneRoot = runtimeRoot.resolve(".clone-");
        Files.createDirectories(cloneRoot);
        Path dest = Files.createTempDirectory(cloneRoot, "agent-");
        try {
            UnifiedCatalogEntry.SourceRef ref = entry.sourceRef();
            String url;
            String refName = null;
            if (ref instanceof UnifiedCatalogEntry.GitUrlSource u) {
                url = u.url();
            } else if (ref instanceof UnifiedCatalogEntry.GitSubdirSource s) {
                url = s.url(); refName = s.ref();
            } else if (ref instanceof UnifiedCatalogEntry.GitLocalInRepoSource l) {
                url = l.repoUrl(); refName = l.ref();
            } else {
                throw new IllegalArgumentException("Unsupported source ref for agent content: " + ref);
            }
            requireCloneableScheme(url);
            Git git = (refName == null)
                ? Git.cloneRepository().setURI(url).setDirectory(dest.toFile())
                    .setTimeout((int) cloneTimeoutSeconds).call()
                : Git.cloneRepository().setURI(url).setDirectory(dest.toFile())
                    .setBranchesToClone(Collections.singletonList("refs/heads/" + refName))
                    .setBranch("refs/heads/" + refName)
                    .setTimeout((int) cloneTimeoutSeconds).call();
            try {
                String resolvedSha = verifySha(git, entry);
                return new CloneResult(dest, resolvedSha);
            } finally {
                git.close();
            }
        } catch (Exception e) {
            // M-3: a failed clone (bad host, network error, sha mismatch) must not leave the temp
            // dir — including its .git — behind under .clone-/. Repeated failed installs would
            // otherwise accumulate. Clean up before rethrowing so install()'s finally sees null.
            deleteRecursive(dest);
            throw e;
        }
    }

    /**
     * Rejects clone URLs whose scheme is not http(s) or file. Marketplace JSON is third-party and
     * attacker-controlled; JGit would otherwise happily clone {@code jar:}, {@code ftp:}, or other
     * schemes it supports, and (without the file allowance) even bare local paths. {@code file:} is
     * permitted because the documented local-dev install path uses it (see AgentContentInstallerTest).
     */
    private static void requireCloneableScheme(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Refusing clone URL with unparseable scheme: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("Refusing clone URL without a scheme: " + url);
        }
        if (!List.of("https", "http", "file").contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                "Refusing clone URL with disallowed scheme '" + scheme + "': " + url);
        }
    }

    /**
     * Returns the sha that should be recorded for this install: the catalog-declared pin when one
     * exists (after verifying HEAD matches it), or the resolved HEAD sha when the catalog declared
     * none (the Codex case). Throwing on a pin mismatch preserves tamper detection; resolving when
     * unpinned ensures every install record carries an auditable content fingerprint rather than null.
     */
    private String verifySha(Git git, UnifiedCatalogEntry entry) throws Exception {
        Repository repo = git.getRepository();
        ObjectId head = repo.resolve("HEAD");
        String resolved = head == null ? null : head.getName();
        if (entry.pinnedSha() == null) return resolved;
        if (head == null || !resolved.equalsIgnoreCase(entry.pinnedSha())) {
            throw new IntegrityException(entry.pinnedSha(), head == null ? "<none>" : resolved);
        }
        return resolved;
    }

    private Path resolvePluginRoot(Path cloneDir, UnifiedCatalogEntry entry) {
        UnifiedCatalogEntry.SourceRef ref = entry.sourceRef();
        if (ref instanceof UnifiedCatalogEntry.GitSubdirSource s && s.path() != null)
            return cloneDir.resolve(s.path()).normalize();
        if (ref instanceof UnifiedCatalogEntry.GitLocalInRepoSource l && l.path() != null)
            return cloneDir.resolve(l.path()).normalize();
        return cloneDir;
    }

    private Path manifestPath(Path pluginRoot, UnifiedCatalogEntry entry) throws IOException {
        Path rel = switch (entry.sourceType()) {
            case CLAUDE -> Path.of(".claude-plugin", "plugin.json");
            case CODEX -> Path.of(".codex-plugin", "plugin.json");
            case GROK -> Path.of(".grok-plugin", "plugin.json");
            default -> throw new IllegalArgumentException(
                    "Unsupported agent-content source: " + entry.sourceType());
        };
        Path p = pluginRoot.resolve(rel).normalize();
        if (!PluginContentPathSafety.isInside(pluginRoot, p) || !Files.exists(p))
            throw new IllegalStateException("plugin.json not found at " + rel);
        return p;
    }

    private List<String> extractSkills(JsonNode pluginJson, Path pluginRoot, Path skillDest, String uid)
            throws IOException {
        JsonNode skills = pluginJson.get("skills");
        List<String> names = new ArrayList<>();
        if (skills == null || skills.isNull()) return names;
        if (skills.isTextual()) {
            Path src = pluginRoot.resolve(skills.asText()).normalize();
            // Source-side traversal guard: a malicious plugin.json could declare
            // "skills":["../../../../etc/passwd"]; refuse to read outside pluginRoot.
            if (!PluginContentPathSafety.isInside(pluginRoot, src)) return names;
            names.addAll(copySkillDir(src, skillDest, skills.asText()));
        } else if (skills.isArray()) {
            for (JsonNode s : skills) {
                if (!s.isTextual()) continue;
                Path src = pluginRoot.resolve(s.asText()).normalize();
                if (!PluginContentPathSafety.isInside(pluginRoot, src)) continue; // skip escaping entry
                names.addAll(copySkillDir(src, skillDest, s.asText()));
            }
        }
        return names;
    }

    private List<String> copySkillDir(Path src, Path destBase, String rel) throws IOException {
        // Symlink defense (M-1): a malicious repo can place a symlink whose target is outside
        // pluginRoot. Tests for existence/regular-file/directory follow symlinks by default, which
        // would copy host-readable files into the runtime tree. Use NOFOLLOW_LINKS and skip any
        // symlink outright rather than copying what it points at.
        if (Files.isSymbolicLink(src)) return List.of();
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Files.createDirectories(destBase);
        List<String> copied = new ArrayList<>();
        if (Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS)) {
            // A skill entry may point at a single file; mirror its relative path under destBase.
            Path target = destBase.resolve(rel).normalize();
            if (PluginContentPathSafety.isInside(destBase, target)) {
                Files.createDirectories(target.getParent());
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                copied.add(rel);
            }
            return copied;
        }
        if (!Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Path srcDir = src;
        // walkFileTree without FileVisitOption.FOLLOW_LINKS (the default) does not follow symlinks;
        // the isSymbolicLink checks below are defense-in-depth for symlinked dirs/files.
        Files.walkFileTree(srcDir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Never descend into a symlinked directory (defense in depth; walkFileTree without
                // FOLLOW_LINKS won't follow, but a symlink-to-dir could still appear here on some FSes).
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                Path relPath = srcDir.getParent() == null ? dir : srcDir.getParent().relativize(dir);
                Path target = destBase.resolve(relPath).normalize();
                if (!PluginContentPathSafety.isInside(destBase, target)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE; // skip symlinked file
                Path relPath = srcDir.getParent() == null ? file : srcDir.getParent().relativize(file);
                Path target = destBase.resolve(relPath).normalize();
                if (!PluginContentPathSafety.isInside(destBase, target)) return FileVisitResult.SKIP_SUBTREE;
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
        copied.add(rel);
        return copied;
    }

    private String writeMcpConfig(JsonNode mcpServers, String uid) throws IOException {
        Path file = resolveMcpConfigPath(uid);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(mcpServers));
        return file.toString();
    }

    private void upsertRecord(UnifiedCatalogEntry entry, String version, String resolvedSha, Path skillPath,
            List<String> skills, List<String> mcpRefs, boolean hasMcp) {
        PluginInstallRecordEntity rec = records
            .findByUidAndUserId(entry.uid(), SecurityConstants.LOCAL_VIRTUAL_USER_ID)
            .orElseGet(() -> {
                PluginInstallRecordEntity e = new PluginInstallRecordEntity();
                e.setUid(entry.uid());
                e.setPluginName(entry.name());
                e.setSourceType(entry.sourceType().name());
                e.setOrigin(entry.origin());
                e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
                return e;
            });
        rec.setVersion(version);
        rec.setPinnedSha(resolvedSha);
        rec.setInstallPath(skillPath.toString());
        rec.setDeclaredSkills(jsonList(skills));
        rec.setMcpServerRefs(jsonList(mcpRefs));
        rec.setHasMcpServers(hasMcp);
        rec.setEnabled(true);
        rec.setUpdatedAt(LocalDateTime.now());
        records.save(rec);
    }

    private String jsonList(List<String> items) {
        try { return json.writeValueAsString(items); }
        catch (Exception e) { return "[]"; }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    static void deleteRecursive(Path p) {
        if (p == null || !Files.exists(p)) return;
        try {
            if (Files.isDirectory(p)) {
                try (var stream = Files.walk(p)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
                }
            } else {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) { }
    }
}
