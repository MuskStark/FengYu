package fan.summer.api.host;

import fan.summer.api.component.SkNotification;
import javafx.scene.Node;

/**
 * Notification access for plugins, delegating to {@link SkNotification}.
 *
 * @since 3.2.0
 */
public interface NotificationFacade {

    /**
     * Non-modal toast that auto-dismisses after ~2.5 s.
     *
     * @param context a node used to locate the owner window; may be null
     * @param type    the visual style
     * @param message the message
     */
    void toast(Node context, SkNotification.Type type, String message);

    /**
     * Modal notification with an OK button.
     *
     * @param context a node used to locate the owner window; may be null
     * @param type    the visual style
     * @param message the message
     */
    void notify(Node context, SkNotification.Type type, String message);

    /**
     * Modal OK/Cancel confirmation. Safe to call from any thread.
     *
     * @param context a node used to locate the owner window; may be null
     * @param title   the confirmation title
     * @param message the body message
     * @return true if the user clicked OK
     */
    boolean confirm(Node context, String title, String message);
}
