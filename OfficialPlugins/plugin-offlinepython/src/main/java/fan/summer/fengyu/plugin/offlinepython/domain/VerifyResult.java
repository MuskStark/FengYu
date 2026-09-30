package fan.summer.fengyu.plugin.offlinepython.domain;

public record VerifyResult(
        CheckResult sha256,
        CheckResult fileIntegrity,
        CheckResult wheels,
        CheckResult requirements,
        CheckResult manifest) {

    public boolean isOk() {
        return sha256.status() != Status.FAIL
                && fileIntegrity.status() != Status.FAIL
                && wheels.status() != Status.FAIL
                && requirements.status() != Status.FAIL
                && manifest.status() != Status.FAIL;
    }
}
