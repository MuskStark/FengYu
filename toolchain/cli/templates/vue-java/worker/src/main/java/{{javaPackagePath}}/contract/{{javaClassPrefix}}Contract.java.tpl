package {{javaPackage}}.contract;

import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.contract.FengYuContract;
import fan.summer.fengyu.sdk.contract.FengYuField;
import fan.summer.fengyu.sdk.contract.FengYuRpc;

/**
 * The single source of truth for this plugin's RPC surface. `fengyu generate`,
 * `check`, and `build` extract the manifest schemas from this interface; do not
 * duplicate them in manifest.base.json.
 */
@FengYuContract
public interface {{javaClassPrefix}}Contract {

    @FengYuRpc(name = "hello", description = "Echo a greeting back to the UI.")
    HelloOutput hello(HelloInput input, RpcContext context);

    record HelloInput(
            @FengYuField(description = "Name to greet.", required = true)
            String name
    ) {}

    record HelloOutput(
            @FengYuField(description = "Rendered greeting.", required = true)
            String message
    ) {}
}
