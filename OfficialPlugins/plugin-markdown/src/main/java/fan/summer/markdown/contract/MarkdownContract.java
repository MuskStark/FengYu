package fan.summer.markdown.contract;

import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.contract.FengYuContract;
import fan.summer.fengyu.sdk.contract.FengYuField;
import fan.summer.fengyu.sdk.contract.FengYuRpc;

/**
 * Code-first RPC contract for the Markdown plugin (implementation plan §5). The
 * DevKit annotation processor extracts this interface at compile time into the
 * contract IR; the CLI's Manifest Compiler merges it with {@code manifest.base.json}
 * into the final {@code manifest.json}. The Input/Output records below are the
 * plugin's own sources — the manifest-first Java DTO generator no longer emits them.
 */
@FengYuContract
public interface MarkdownContract {

    @FengYuRpc(name = "render",
            description = "Render Markdown source to sanitized HTML via commonmark (server-side).")
    RenderOutput render(RenderInput input, RpcContext context);

    record RenderInput(
            @FengYuField(description = "The Markdown source to render.", required = true)
            String markdown
    ) {}

    record RenderOutput(
            @FengYuField(description = "The rendered, sanitized HTML.", nullable = true)
            String html,

            @FengYuField(description = "true when the render completed.", required = true)
            boolean success,

            @FengYuField(description = "Short localized result summary.", required = true)
            String summary
    ) {}
}
