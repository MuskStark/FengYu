package fan.summer.fengyu.plugin.offlinepython.domain;

import java.util.List;

public record CheckResult(Status status, String detail, List<String> problems) {
    public static CheckResult pass(String detail) { return new CheckResult(Status.PASS, detail, List.of()); }
    public static CheckResult warn(String detail, List<String> problems) { return new CheckResult(Status.WARN, detail, problems); }
    public static CheckResult fail(String detail, List<String> problems) { return new CheckResult(Status.FAIL, detail, problems); }
}
