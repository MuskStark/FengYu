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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Installs Claude/Codex plugins by cloning their git source (JGit), verifying the pinned sha,
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
        Path cloneDir = null;
        try {
            cloneDir = cloneSource(entry);
            Path pluginRoot = resolvePluginRoot(cloneDir, entry);
            Path manifest = manifestPath(pluginRoot, entry);
            JsonNode pluginJson = json.readTree(Files.readString(manifest));
            String version = text(pluginJson, "version");

            Path skillDest = runtimeRoot.resolve("skills").resolve(entry.uid());
            deleteRecursive(skillDest);
            List<String> skillPaths = extractSkills(pluginJson, pluginRoot, skillDest, entry.uid());

            boolean hasMcp = pluginJson.has("mcpServers") && !pluginJson.get("mcpServers").isNull()
                && !pluginJson.get("mcpServers").isEmpty();
            List<String> mcpRefs = hasMcp
                ? List.of(writeMcpConfig(pluginJson.get("mcpServers"), entry.uid()))
                : List.of();

            upsertRecord(entry, version, skillDest, skillPaths, mcpRefs, hasMcp);
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
            deleteRecursive(runtimeRoot.resolve("skills").resolve(uid));
            deleteRecursive(runtimeRoot.resolve("mcp-servers").resolve(uid + ".json"));
            records.delete(rec);
        });
    }

    public void setEnabled(String uid, boolean enabled) {
        records.findByUidAndUserId(uid, SecurityConstants.LOCAL_VIRTUAL_USER_ID).ifPresent(rec -> {
            rec.setEnabled(enabled);
            rec.setUpdatedAt(LocalDateTime.now());
            records.save(rec);
        });
    }

    // --- internals ------------------------------------------------------------------

    private Path cloneSource(UnifiedCatalogEntry entry) throws Exception {
        Path cloneRoot = runtimeRoot.resolve(".clone-");
        Files.createDirectories(cloneRoot);
        Path dest = Files.createTempDirectory(cloneRoot, "agent-");
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
        Git git = (refName == null)
            ? Git.cloneRepository().setURI(url).setDirectory(dest.toFile()).call()
            : Git.cloneRepository().setURI(url).setDirectory(dest.toFile())
                .setBranchesToClone(Collections.singletonList("refs/heads/" + refName))
                .setBranch("refs/heads/" + refName).call();
        try {
            verifySha(git, entry);
        } finally {
            git.close();
        }
        return dest;
    }

    private void verifySha(Git git, UnifiedCatalogEntry entry) throws Exception {
        if (entry.pinnedSha() == null) return;
        Repository repo = git.getRepository();
        ObjectId head = repo.resolve("HEAD");
        if (head == null || !head.getName().equalsIgnoreCase(entry.pinnedSha())) {
            throw new IntegrityException(entry.pinnedSha(), head == null ? "<none>" : head.getName());
        }
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
        Path rel = entry.sourceType() == StoreSourceType.CLAUDE
            ? Path.of(".claude-plugin", "plugin.json")
            : Path.of(".codex-plugin", "plugin.json");
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
            names.addAll(copySkillDir(pluginRoot.resolve(skills.asText()), skillDest, skills.asText()));
        } else if (skills.isArray()) {
            for (JsonNode s : skills) {
                if (s.isTextual()) names.addAll(copySkillDir(pluginRoot.resolve(s.asText()), skillDest, s.asText()));
            }
        }
        return names;
    }

    private List<String> copySkillDir(Path src, Path destBase, String rel) throws IOException {
        if (!Files.exists(src)) return List.of();
        Files.createDirectories(destBase);
        List<String> copied = new ArrayList<>();
        if (Files.isRegularFile(src)) {
            // A skill entry may point at a single file; mirror its relative path under destBase.
            Path target = destBase.resolve(rel).normalize();
            if (PluginContentPathSafety.isInside(destBase, target)) {
                Files.createDirectories(target.getParent());
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                copied.add(rel);
            }
            return copied;
        }
        if (!Files.isDirectory(src)) return List.of();
        Path srcDir = src;
        Files.walkFileTree(srcDir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relPath = srcDir.getParent() == null ? dir : srcDir.getParent().relativize(dir);
                Path target = destBase.resolve(relPath).normalize();
                if (!PluginContentPathSafety.isInside(destBase, target)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relPath = srcDir.getParent() == null ? file : srcDir.getParent().relativize(file);
                Path target = destBase.resolve(relPath).normalize();
                if (!PluginContentPathSafety.isInside(destBase, target)) return FileVisitResult.SKIP_SUBTREE;
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        copied.add(rel);
        return copied;
    }

    private String writeMcpConfig(JsonNode mcpServers, String uid) throws IOException {
        Path mcpDir = runtimeRoot.resolve("mcp-servers");
        Files.createDirectories(mcpDir);
        Path file = mcpDir.resolve(uid + ".json");
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(mcpServers));
        return file.toString();
    }

    private void upsertRecord(UnifiedCatalogEntry entry, String version, Path skillPath,
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
        rec.setPinnedSha(entry.pinnedSha());
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
