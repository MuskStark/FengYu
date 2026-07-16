package fan.summer.fengyu.plugin.offlinepython.domain;

/** 部署(逐包安装)的结果汇总。 */
public record DeployResult(int installed, int skipped, int failed, long durationMs) {
    public boolean ok() { return failed == 0; }
}
