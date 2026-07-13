package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.sdk.JsonRpcWorker;

public final class MarkdownWorkerMain {
    private MarkdownWorkerMain() {}
    public static void main(String[] args) throws Exception {
        MarkdownPlugin plugin = new MarkdownPlugin();
        new JsonRpcWorker().on("render", params -> plugin.invoke("render", params)).run();
    }
}
