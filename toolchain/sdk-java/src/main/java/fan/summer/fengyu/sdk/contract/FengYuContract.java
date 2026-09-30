package fan.summer.fengyu.sdk.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a plugin's RPC contract for the code-first toolchain:
 * the FengYu annotation processor (devkit) reads its {@link FengYuRpc} methods
 * and generates the manifest {@code rpc.methods} / {@code aiTools} entries from
 * the method signatures and their record parameter types.
 *
 * <p>Source retention only — nothing here reaches the worker runtime; the
 * processor works purely on the compile-time type model.
 *
 * @since 2.1.0
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface FengYuContract {
}
