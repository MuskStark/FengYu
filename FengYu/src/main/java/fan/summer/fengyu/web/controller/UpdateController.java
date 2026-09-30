package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ExitCodes;
import fan.summer.fengyu.update.SelfUpdateService;
import fan.summer.fengyu.update.UpdateCheckService;
import fan.summer.fengyu.update.UpdateInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Application update check + portable-mode self-update endpoints.
 *
 * <p>{@code GET /api/updates/check} works in every deployment mode (desktop shell, portable Web,
 * browser) and reports the latest GitHub release against the running build. The actual install is
 * mode-specific: the desktop Electron shell handles it via electron-updater (the renderer triggers
 * that over its own IPC bridge), while the portable {@code java -jar} deployment self-updates
 * through {@code POST /api/updates/apply} below.
 *
 * <p>Both endpoints stay behind the token filter (the SPA already carries the {@code X-FengYu-Token}
 * header); they are NOT added to the {@code TokenAuthFilter} bypass list.
 */
@RestController
@RequestMapping("/api/updates")
public class UpdateController {
    private final UpdateCheckService updateCheck;
    private final SelfUpdateService selfUpdate;
    private final Runnable exitAction;

    @Autowired
    public UpdateController(UpdateCheckService updateCheck, SelfUpdateService selfUpdate) {
        this(updateCheck, selfUpdate, defaultExitAction());
    }

    /** Test constructor: lets unit tests pass a no-op exit action. */
    UpdateController(UpdateCheckService updateCheck, SelfUpdateService selfUpdate, Runnable exitAction) {
        this.updateCheck = updateCheck;
        this.selfUpdate = selfUpdate;
        this.exitAction = exitAction;
    }

    @GetMapping("/check")
    public UpdateInfo check(@RequestParam(defaultValue = "false") boolean force) {
        return updateCheck.check(force);
    }

    /**
     * Portable-mode only: download + verify the new JAR, spawn a detached restart script, then exit
     * the JVM so the script can swap the JAR and relaunch. Desktop builds (electron-updater-owned)
     * get a 400 so the frontend routes them to the shell's IPC path instead.
     */
    @PostMapping("/apply")
    public Map<String, Object> apply() {
        if (!updateCheck.isPortableMode()) {
            throw new IllegalArgumentException(
                    "Self-update is unavailable in this mode — use the desktop app's built-in updater");
        }
        UpdateInfo info = updateCheck.check(false);
        selfUpdate.applyUpdate(info, exitAction);
        return Map.of("success", true, "action", "restart");
    }

    /**
     * Delayed exit (1s) so the HTTP response flushes before the JVM shuts down — mirrors
     * {@code SettingsController}'s restart-on-database-reset pattern.
     */
    private static Runnable defaultExitAction() {
        return () -> {
            Thread exitHook = new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                System.exit(ExitCodes.SETUP_DONE);
            }, "self-update-exit");
            exitHook.setDaemon(true);
            exitHook.start();
        };
    }
}
