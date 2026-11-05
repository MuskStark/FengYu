package fan.summer.fengyu.sdk.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes the annotated {@link FengYuRpc} method to the AI as a tool. The tool
 * {@code effect} is mandatory authorization metadata, exactly as in manifest v2.
 *
 * @since 2.1.0
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface FengYuAiTool {

    /** AI-facing tool name; defaults to the {@link FengYuRpc} wire name. */
    String name() default "";

    /** AI-facing description (this is what the model reads). */
    String description();

    /** Authorization effect: read, write, or external. */
    ToolEffect effect();

    /** Whether an identical write/external invocation is retry-safe. */
    boolean idempotent() default false;

    /** Per-tool timeout override in seconds (1–600); -1 keeps the method's. */
    int timeoutSeconds() default -1;

    /**
     * Optional chat app-card view path inside the ui.entry subtree (e.g. "ui/tool.html");
     * blank (default) declares no view. See {@link AppMode}.
     *
     * @since 2.2.0
     */
    String appPath() default "";

    /** How the host renders the {@link #appPath()} view; {@link AppMode#CARD} is the default.
     *
     * @since 2.2.0
     */
    AppMode appMode() default AppMode.CARD;

    /** Card title for the {@link #appPath()} view; blank keeps the host's fallback.
     *
     * @since 2.2.0
     */
    String appTitle() default "";

    /** Authorization effects, mirroring the manifest {@code aiTools.effect} enum. */
    enum ToolEffect { READ, WRITE, EXTERNAL }

    /** App-card render modes, mirroring the manifest {@code aiTools[].app.mode} enum.
     *
     * @since 2.2.0
     */
    enum AppMode { CARD, DIALOG }
}
