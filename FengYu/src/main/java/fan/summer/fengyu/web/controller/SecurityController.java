package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.security.ProcessSandbox;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Read-only disclosure of the active process-isolation posture. */
@RestController
public class SecurityController {

    private final ProcessSandbox sandbox;

    public SecurityController(ProcessSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @GetMapping("/api/security/process-isolation")
    public Map<String, Object> processIsolation() {
        boolean sandboxed = sandbox.backend() != ProcessSandbox.Backend.NONE;
        return Map.of(
                "backend", sandbox.backend().id(),
                "sandboxed", sandboxed,
                "compatibilityMode", !sandboxed,
                "policy", "compatibility-first-explicit-approval");
    }
}
