package fan.summer.api.i18n;

import javafx.beans.property.StringProperty;

import java.lang.ref.WeakReference;

record BoundEntry(WeakReference<StringProperty> propertyRef, String key, Object[] args) {
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
