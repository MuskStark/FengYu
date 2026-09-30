package fan.summer.fengyu.plugin.offlinepython.command;

import fan.summer.fengyu.plugin.offlinepython.domain.CheckResult;
import fan.summer.fengyu.plugin.offlinepython.domain.DependencySpec;
import fan.summer.fengyu.plugin.offlinepython.domain.Manifest;
import fan.summer.fengyu.plugin.offlinepython.domain.Status;
import fan.summer.fengyu.plugin.offlinepython.domain.VerifyResult;
import fan.summer.fengyu.plugin.offlinepython.domain.WheelEntry;
import fan.summer.fengyu.plugin.offlinepython.infra.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Verifies an output/ repository against its manifest (no subprocess calls). */
public class VerifyService {

    private static final Logger log = LoggerFactory.getLogger(VerifyService.class);

    public VerifyResult verify(Path outputDir, Manifest manifest) {
        return verify(outputDir, manifest, fan.summer.fengyu.plugin.offlinepython.domain.VerifyScope.ALL);
    }

    public fan.summer.fengyu.plugin.offlinepython.domain.VerifyResult verify(
            Path outputDir, Manifest manifest, fan.summer.fengyu.plugin.offlinepython.domain.VerifyScope scope) {
        boolean all = scope == fan.summer.fengyu.plugin.offlinepython.domain.VerifyScope.ALL;
        boolean integ = all || scope == fan.summer.fengyu.plugin.offlinepython.domain.VerifyScope.INTEGRITY;
        boolean sha = all || scope == fan.summer.fengyu.plugin.offlinepython.domain.VerifyScope.SHA256;
        return new fan.summer.fengyu.plugin.offlinepython.domain.VerifyResult(
                sha ? checkSha256(outputDir, manifest) : null,
                integ ? checkFileIntegrity(outputDir, manifest) : null,
                all ? checkWheels(manifest) : null,
                all ? checkRequirements(manifest) : null,
                integ ? checkManifest(manifest) : null);
    }

    /** Every manifest wheel file exists and is non-empty. */
    CheckResult checkFileIntegrity(Path outputDir, Manifest m) {
        List<String> problems = new ArrayList<>();
        for (WheelEntry w : m.getWheels()) {
            Path f = outputDir.resolve(w.getFile());
            try {
                if (!Files.exists(f)) problems.add("missing: " + w.getFile());
                else if (Files.size(f) == 0) problems.add("empty: " + w.getFile());
            } catch (IOException e) {
                log.debug("cannot stat wheel during integrity check: {}", w.getFile(), e);
                problems.add("cannot stat: " + w.getFile());
            }
        }
        Path installer = m.getPython().getInstaller() == null ? null
                : outputDir.resolve(m.getPython().getInstaller());
        if (m.getPython().getInstaller() != null) {
            if (installer == null || !Files.exists(installer))
                problems.add("missing python installer: " + m.getPython().getInstaller());
        }
        return problems.isEmpty()
                ? CheckResult.pass("all manifest files present and non-empty")
                : CheckResult.fail("integrity problems", problems);
    }

    /** Recompute SHA256 of each wheel and compare to manifest. */
    CheckResult checkSha256(Path outputDir, Manifest m) {
        List<String> problems = new ArrayList<>();
        for (WheelEntry w : m.getWheels()) {
            Path f = outputDir.resolve(w.getFile());
            if (!Files.exists(f)) { problems.add("cannot hash missing " + w.getFile()); continue; }
            String actual;
            try { actual = HashUtil.sha256Hex(f); }
            catch (IOException e) {
                log.debug("cannot hash wheel during sha256 check: {}", w.getFile(), e);
                problems.add("hash error " + w.getFile());
                continue;
            }
            if (!actual.equalsIgnoreCase(w.getSha256()))
                problems.add("hash mismatch: " + w.getFile());
        }
        return problems.isEmpty()
                ? CheckResult.pass("all checksums match")
                : CheckResult.fail("checksum mismatch", problems);
    }

    /** Wheel filenames look like *.whl. */
    CheckResult checkWheels(Manifest m) {
        List<String> problems = new ArrayList<>();
        for (WheelEntry w : m.getWheels()) {
            if (w.getFile() == null || !w.getFile().endsWith(".whl"))
                problems.add("not a wheel: " + w.getFile());
        }
        return problems.isEmpty()
                ? CheckResult.pass("all wheel entries valid")
                : CheckResult.warn("invalid wheel entries", problems);
    }

    /** Each requirements.txt package has at least one matching wheel by name. */
    CheckResult checkRequirements(Manifest m) {
        List<String> problems = new ArrayList<>();
        for (String req : m.getRequirements()) {
            DependencySpec spec = DependencySpec.parse(req);
            String want = DependencySpec.normalizeName(spec.name());
            boolean found = m.getWheels().stream()
                    .anyMatch(w -> DependencySpec.normalizeName(w.getName()).equals(want));
            if (!found) problems.add("no wheel satisfies: " + req);
        }
        return problems.isEmpty()
                ? CheckResult.pass("all requirements satisfied")
                : CheckResult.warn("unsatisfied requirements", problems);
    }

    /** Manifest must declare a usable schema version. Missing optional metadata
     *  (e.g. python version when no installer is bundled) is a soft warning, not corruption. */
    CheckResult checkManifest(Manifest m) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (m.getSchemaVersion() < 1) errors.add("schemaVersion missing");
        if (m.getPython().getVersion() == null) warnings.add("python version missing");
        if (!errors.isEmpty()) return CheckResult.fail("manifest inconsistent", errors);
        if (!warnings.isEmpty()) return CheckResult.warn("manifest incomplete", warnings);
        return CheckResult.pass("manifest consistent");
    }
}
