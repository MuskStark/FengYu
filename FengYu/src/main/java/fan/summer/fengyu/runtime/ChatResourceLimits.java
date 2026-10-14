package fan.summer.fengyu.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Operator-tunable caps for chat resources and the plugin file-grant bound that mirrors them.
 * Desktop-calibrated defaults (2026-10): native picks are zero-copy live references, so a
 * generous single-file bound no longer doubles disk usage — the cap is a runaway guard, not a
 * storage budget. Uploads still land one physical copy in the host store.
 *
 * <p>Values bind from {@code fengyu.chat-resources.*} as Spring {@link DataSize} strings
 * ("1GB", "512MB"). Ordering is validated eagerly by every consumer:
 * {@code single ≤ tree ≤ scope}, and the single-file bound may never exceed
 * {@link #HARD_SINGLE_CEILING_BYTES} — a misconfigured yml fails startup instead of
 * surprising a user mid-send.
 */
@Component
@ConfigurationProperties(prefix = "fengyu.chat-resources")
public class ChatResourceLimits {

    /** Absolute ceiling for {@code max-single-file}; nothing may configure past it. */
    public static final long HARD_SINGLE_CEILING_BYTES = DataSize.ofGigabytes(2).toBytes();

    private DataSize maxSingleFileBytes = DataSize.ofGigabytes(1);
    private DataSize maxTreeBytes = DataSize.ofGigabytes(2);
    private DataSize maxScopeBytes = DataSize.ofGigabytes(4);
    /** Reserved for tool-result artifact overflow (batch 2); claimed now so the key is stable. */
    private DataSize toolArtifactsBytes = DataSize.ofGigabytes(1);

    /** Fresh limits with the documented defaults — the test fixture path. */
    public static ChatResourceLimits defaults() {
        ChatResourceLimits limits = new ChatResourceLimits();
        limits.validate();
        return limits;
    }

    public void validate() {
        if (maxSingleFileBytes == null || maxTreeBytes == null || maxScopeBytes == null || toolArtifactsBytes == null
                || maxSingleFileBytes.toBytes() <= 0 || maxTreeBytes.toBytes() <= 0
                || maxScopeBytes.toBytes() <= 0 || toolArtifactsBytes.toBytes() <= 0) {
            throw new IllegalStateException("fengyu.chat-resources limits must be positive");
        }
        if (maxSingleFileBytes.toBytes() > HARD_SINGLE_CEILING_BYTES) {
            throw new IllegalStateException("fengyu.chat-resources.max-single-file may not exceed "
                    + human(HARD_SINGLE_CEILING_BYTES));
        }
        if (maxSingleFileBytes.toBytes() > maxTreeBytes.toBytes() || maxTreeBytes.toBytes() > maxScopeBytes.toBytes()) {
            throw new IllegalStateException("fengyu.chat-resources limits must satisfy "
                    + "max-single-file ≤ max-tree ≤ max-scope");
        }
    }

    public long getMaxSingleFileBytes() { return maxSingleFileBytes.toBytes(); }
    public long getMaxTreeBytes() { return maxTreeBytes.toBytes(); }
    public long getMaxScopeBytes() { return maxScopeBytes.toBytes(); }
    public long getToolArtifactsBytes() { return toolArtifactsBytes.toBytes(); }

    public void setMaxSingleFileBytes(DataSize maxSingleFileBytes) { this.maxSingleFileBytes = maxSingleFileBytes; }
    public void setMaxTreeBytes(DataSize maxTreeBytes) { this.maxTreeBytes = maxTreeBytes; }
    public void setMaxScopeBytes(DataSize maxScopeBytes) { this.maxScopeBytes = maxScopeBytes; }
    public void setToolArtifactsBytes(DataSize toolArtifactsBytes) { this.toolArtifactsBytes = toolArtifactsBytes; }

    /** "1 GB" / "500 MB" — the human form used verbatim in user-facing rejection messages. */
    public static String human(long bytes) {
        long gib = bytes / (1024L * 1024 * 1024);
        if (gib > 0 && bytes % (1024L * 1024 * 1024) == 0) return gib + " GB";
        long mib = bytes / (1024L * 1024);
        if (mib > 0 && bytes % (1024L * 1024) == 0) return mib + " MB";
        return bytes + " bytes";
    }
}
