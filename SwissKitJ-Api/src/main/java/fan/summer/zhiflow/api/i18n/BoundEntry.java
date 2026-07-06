package fan.summer.zhiflow.api.i18n;

import javafx.beans.property.StringProperty;

import java.lang.ref.WeakReference;

/**
 * A lightweight binding entry that connects a JavaFX {@link StringProperty} to an
 * internationalized message key in {@link I18n}, supporting parameterized messages.
 *
 * <p>{@code BoundEntry} holds a {@link WeakReference} to the property, allowing the
 * property to be garbage-collected without preventing cleanup of the entry.
 *
 * <p>Call {@link #update()} to refresh the property with the current localized text.
 *
 * @see I18n
 * @see StringProperty
 */
record BoundEntry(WeakReference<StringProperty> propertyRef, String key, Object[] args) {
    /**
     * Updates the bound property with the localized text for this entry's key.
     * If the property has been garbage-collected, this method is a no-op.
     * If no format arguments were provided at construction, the key is looked up
     * without substitution; otherwise the arguments are passed to
     * {@link I18n#get(String, Object...)}.
     */
    void update() {
        StringProperty prop = propertyRef.get();
        if (prop == null) return;
        if (args == null || args.length == 0) {
            prop.set(I18n.get(key));
        } else {
            prop.set(I18n.get(key, args));
        }
    }
}
