package fan.summer.fengyu.sdk.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a contract input field as sensitive (credential, secret, private body
 * text). The processor emits {@code x-fengyu-sensitive: true} on the field's
 * schema; the CLI and the host then refuse to log it or pass it through as a
 * Flow input-passthrough output.
 *
 * @since 2.1.0
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.RECORD_COMPONENT)
public @interface FengYuSensitive {
}
