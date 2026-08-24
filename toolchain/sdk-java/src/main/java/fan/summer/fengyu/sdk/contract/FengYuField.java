package fan.summer.fengyu.sdk.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one record component of a contract Input/Output DTO: its schema
 * title, requiredness, nullability, and numeric/enum constraints. Field
 * documentation comments are not reliably visible to annotation processors, so
 * the description lives here explicitly.
 *
 * <p>Mapping rules: primitives are required by default; reference components
 * are optional unless {@code required = true}. {@code nullable} models a
 * JSON-Schema {@code nullable: true} separately from omit-ability.
 *
 * @since 2.1.0
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.RECORD_COMPONENT)
public @interface FengYuField {

    /** Human-facing field description. */
    String description() default "";

    /** Human-facing label (JSON-Schema {@code title}). */
    String title() default "";

    /** Whether the field must be present. Primitives default to true. */
    boolean required() default false;

    /** Whether the value may be JSON {@code null} (distinct from omittable). */
    boolean nullable() default false;

    /** Numeric minimum ({@code minimum}). */
    double minimum() default Double.NaN;

    /** Numeric maximum ({@code maximum}). */
    double maximum() default Double.NaN;

    /**
     * JSON-Schema default encoded as a scalar string and converted according to the
     * record component type. Supported targets are string, enum, boolean, integer,
     * and number. Empty means no default.
     */
    String defaultValue() default "";

    /** Canvas hint {@code x-fengyu-analyze}: render an analyze affordance beside this input. */
    String analyze() default "";

    /** Canvas hint {@code x-fengyu-advanced}: fold this input into Advanced settings. */
    boolean advanced() default false;

    /** Canvas hint {@code x-fengyu-options-from}: datalist candidates for this input. */
    String optionsFrom() default "";
}
