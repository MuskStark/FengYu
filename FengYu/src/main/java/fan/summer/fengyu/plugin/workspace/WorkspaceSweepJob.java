package fan.summer.fengyu.plugin.workspace;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Hourly TTL sweep of plugin workspaces (24h retention). Complements the shutdown-hook wipe. */
@Component
public class WorkspaceSweepJob {
    private final PluginWorkspaceService workspace;
    public WorkspaceSweepJob(PluginWorkspaceService workspace) { this.workspace = workspace; }

    @Scheduled(fixedRate = 3_600_000L)
    public void sweep() { workspace.sweep(Duration.ofHours(24)); }
}
