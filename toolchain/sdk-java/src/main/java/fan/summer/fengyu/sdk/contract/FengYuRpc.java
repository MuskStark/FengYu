package fan.summer.fengyu.sdk.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one RPC method on a {@link FengYuContract} interface. The processor
 * maps the method's record parameter to {@code inputSchema} and its return
 * record to {@code outputSchema}. Defaults the wire name to the Java method
 * name.
 *
 * @since 2.1.0
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface FengYuRpc {

    /** JSON-RPC method name; defaults to the Java method name. */
    String name() default "";

    /** Human-facing description of the method (host tool surface + UI). */
    String description() default "";

    /** Per-call timeout in seconds (1–600, mirroring the manifest bounds). */
    int timeoutSeconds() default -1;
}
