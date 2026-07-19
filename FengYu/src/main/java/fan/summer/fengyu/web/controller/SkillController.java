package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.skill.MarketplaceSkill;
import fan.summer.fengyu.ai.skill.Skill;
import fan.summer.fengyu.ai.skill.SkillManifest;
import fan.summer.fengyu.ai.skill.SkillMarketplaceService;
import fan.summer.fengyu.ai.skill.SkillPackageService;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP layer for runtime skills — the lifecycle twin of {@code PluginMarketplaceController}.
 *
 * <p>Exposes the full install/uninstall/enable lifecycle plus the marketplace merge, all under
 * {@code /api/skills}:
 * <ul>
 *   <li>{@code GET /api/skills} — every discovered skill (builtin + installed), summary only.</li>
 *   <li>{@code GET /api/skills/{id}} — full single-skill detail including the markdown body.</li>
 *   <li>{@code GET /api/skills/market} — marketplace merged view ({@link MarketplaceSkill}).</li>
 *   <li>{@code POST /api/skills/upload} — install a {@code .fys} archive (multipart).</li>
 *   <li>{@code POST /api/skills/upload-native} — install a {@code .fys} by absolute path (Tauri).</li>
 *   <li>{@code POST /api/skills/{id}/install} — install from the configured catalog.</li>
 *   <li>{@code POST /api/skills/{id}/update} — update from the catalog (reuses install).</li>
 *   <li>{@code PATCH /api/skills/{id}/enabled} — flip the {@code .disabled} marker.</li>
 *   <li>{@code DELETE /api/skills/{id}} — uninstall.</li>
 * </ul>
 *
 * <p><b>Builtin skills are read-only at this API.</b> They ship in the JAR with no install
 * directory, so uninstall and enable/disable against a builtin id return {@code 409 Conflict}.
 * The skill-injection path itself ({@code SkillPromptAppender} + the {@code skill} tool) is
 * backend-internal and never touches this controller.
 *
 * <p>Token auth applies (the {@code TokenAuthFilter} only bypasses {@code /api/health} and
 * {@code /api/setup/}); the frontend {@code client.ts} attaches {@code X-FengYu-Token}
 * automatically.
 *
 * @since 4.0.0
 */
@RestController
public class SkillController {

    private final SkillRegistry registry;
    private final SkillPackageService packages;
    private final SkillMarketplaceService marketplace;

    public SkillController(SkillRegistry registry, SkillPackageService packages,
                           SkillMarketplaceService marketplace) {
        this.registry = registry;
        this.packages = packages;
        this.marketplace = marketplace;
    }

    // ── discovery ────────────────────────────────────────────────────

    /** Lists every discovered skill without bodies. */
    @GetMapping("/api/skills")
    public List<Map<String, Object>> list() {
        return registry.all().stream().map(this::summary).toList();
    }

    /** Full detail for one skill, including its markdown body. */
    @GetMapping("/api/skills/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String id) {
        Optional<Map<String, Object>> body = registry.find(id).map(this::full);
        return body.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── marketplace ──────────────────────────────────────────────────

    /** Merged marketplace view: remote catalog entries joined with local install state. */
    @GetMapping("/api/skills/market")
    public List<MarketplaceSkill> market() {
        return marketplace.list();
    }

    // ── install lifecycle ────────────────────────────────────────────

    /** Install a {@code .fys} archive uploaded as multipart form data. */
    @PostMapping(value = "/api/skills/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SkillManifest> upload(@RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(packages.install(file));
    }

    /** Install a {@code .fys} archive by absolute filesystem path (Tauri sidecar path). */
    @PostMapping("/api/skills/upload-native")
    public ResponseEntity<SkillManifest> uploadNative(@RequestBody NativeUpload request) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(packages.install(java.nio.file.Path.of(request.path())));
    }

    /** Install a skill by id from the configured catalog. */
    @PostMapping("/api/skills/{id}/install")
    public ResponseEntity<SkillManifest> install(@PathVariable String id) throws IOException, InterruptedException {
        return ResponseEntity.status(HttpStatus.CREATED).body(marketplace.install(id));
    }

    /** Update an installed skill from the catalog (reuses the install path). */
    @PostMapping("/api/skills/{id}/update")
    public SkillManifest update(@PathVariable String id) throws IOException, InterruptedException {
        return marketplace.install(id);
    }

    /** Flip the {@code .disabled} marker; returns the post-update enabled state. */
    @PatchMapping("/api/skills/{id}/enabled")
    public ResponseEntity<Map<String, Object>> enabled(@PathVariable String id,
                                                       @RequestBody EnabledBody body) throws IOException {
        Skill skill = registry.find(id).orElse(null);
        if (skill == null) return ResponseEntity.notFound().build();
        if (skill.source() != Skill.Source.INSTALLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Builtin skills cannot be disabled");
        }
        boolean enabled = body != null && body.enabled();
        registry.setEnabled(id, enabled);
        return ResponseEntity.ok(Map.of("id", id, "enabled", enabled));
    }

    /** Uninstall a skill. Builtin skills (no install directory) are rejected with 409. */
    @DeleteMapping("/api/skills/{id}")
    public ResponseEntity<Void> uninstall(@PathVariable String id) throws IOException {
        Skill skill = registry.find(id).orElse(null);
        if (skill == null) return ResponseEntity.notFound().build();
        if (skill.source() != Skill.Source.INSTALLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Builtin skills cannot be uninstalled");
        }
        packages.uninstall(id);
        return ResponseEntity.noContent().build();
    }

    // ── DTOs ─────────────────────────────────────────────────────────

    /** {@code POST /api/skills/upload-native} body. */
    public record NativeUpload(String path) {}

    /** {@code PATCH /api/skills/{id}/enabled} body. */
    public record EnabledBody(boolean enabled) {}

    // ── response shaping ──────────────────────────────────────────────

    /** Summary view (no body) for the listing endpoint. */
    private Map<String, Object> summary(Skill s) {
        return Map.of(
                "id", s.id(),
                "name", s.name(),
                "description", s.description() == null ? "" : s.description(),
                "source", s.source().name(),
                "enabled", registry.isEnabled(s));
    }

    /** Full view (with body) for the detail endpoint. */
    private Map<String, Object> full(Skill s) {
        return Map.of(
                "id", s.id(),
                "name", s.name(),
                "description", s.description() == null ? "" : s.description(),
                "body", s.body() == null ? "" : s.body(),
                "source", s.source().name(),
                "enabled", registry.isEnabled(s));
    }

    /** Surface a bad body / invalid argument as 400 rather than a 500 stack trace. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage()));
    }
}
