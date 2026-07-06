package fan.summer.zhiflow.api.component;

/**
 * Deprecated alias of {@link SkNotification}.
 *
 * <p>Renamed in 3.2.0 as part of the project-wide glass → sk rename
 * (see the {@code [3.2.0]} section of CHANGELOG.md). All static methods and the
 * nested {@code Type} enum remain accessible through this class via inheritance,
 * so existing plugin code keeps compiling — but new code should use
 * {@link SkNotification} directly.</p>
 *
 * @deprecated since 3.2.0, use {@link SkNotification}; scheduled for removal in 4.0.
 */
@Deprecated(since = "3.2.0", forRemoval = true)
public final class GlassNotification extends SkNotification {

    private GlassNotification() {}
}
