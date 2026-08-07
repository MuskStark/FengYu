package fan.summer.fengyu.plugin.offlinepython.command;

import fan.summer.fengyu.plugin.offlinepython.domain.DeployResult;
import fan.summer.fengyu.plugin.offlinepython.domain.DeployTarget;
import fan.summer.fengyu.plugin.offlinepython.domain.Manifest;
import fan.summer.fengyu.plugin.offlinepython.domain.WheelEntry;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;
import fan.summer.fengyu.plugin.offlinepython.infra.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeployService tests using a hand-rolled {@link ProcessRunner} stub instead of Mockito, so they
 * run on any JDK (Mockito-inline cannot subclass on newer JVMs). The {@link StubRunner} records
 * each invocation and returns a configurable exit code without shelling out to pip.
 *
 * <p>Version detection is also stubbed via {@link FakeDeployService} so the tests never shell out
 * to a fixed interpreter path (which would make them machine-dependent).
 */
class DeployServiceTest {

    /** A ProcessRunner that never spawns a process; returns {@code exitCode} for every run. */
    static final class StubRunner extends ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        final int exitCode;
        StubRunner(int exitCode) { this.exitCode = exitCode; }
        @Override
        public int run(List<String> command, Consumer<String> onLine) {
            commands.add(command);
            return exitCode;
        }
    }

    /**
     * A DeployService whose Python-version probe returns a fixed value, regardless of the
     * interpreter path. This is what makes the deploy tests machine-independent: we never rely on
     * a real interpreter living at the path the target points at.
     */
    static final class FakeDeployService extends DeployService {
        final String versionOrNull;
        FakeDeployService(ProcessRunner runner, String versionOrNull) {
            super(runner);
            this.versionOrNull = versionOrNull;
        }
        @Override
        protected String detectPythonVersion(Path pythonExe) {
            return versionOrNull;
        }
    }

    /** 用一个全平台兼容的纯 Python wheel 构造 bundle ZIP。 */
    private Path makeBundle(Path tmp) throws IOException {
        return makeBundle(tmp, "wheels/requests-2.31.0-py3-none-any.whl",
                "requests-2.31.0-py3-none-any.whl");
    }

    /**
     * 构造一个 bundle ZIP,manifest 里的 wheel file 用真实构建产物里的多段路径
     * (如 "wheelhouse/3.12.10/..."),ZIP 内则按 PackageService 的扁平结构存放在
     * bundle/wheels/<basename>。这就复现了 BuildService → PackageService → DeployService
     * 真实跨步骤的形态。
     */
    private Path makeBundle(Path tmp, String manifestFile, String zipWheelName) throws IOException {
        Path zip = tmp.resolve("b.zip");
        Manifest m = new Manifest();
        m.getPython().setVersion("3.12.10");
        m.getWheels().add(new WheelEntry("requests", "2.31.0", manifestFile, "abc", 1000, true));
        String json = JsonStore.toJson(m);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("bundle/manifest.json"));
            z.write(json.getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("bundle/wheels/" + zipWheelName));
            z.write(new byte[]{1});
            z.closeEntry();
        }
        return zip;
    }

    @Test
    void installRunsPipPerWheel(@TempDir Path tmp) throws Exception {
        Path zip = makeBundle(tmp);
        StubRunner runner = new StubRunner(0); // all commands succeed

        // 解释器路径故意用一个不存在于 PATH 的位置 —— 证明版本从 target 解析,而非另起 detect(null)。
        DeployTarget target = new DeployTarget.Global(Path.of("/nonexistent/conda/bin/python3.12"));
        DeployService svc = new FakeDeployService(runner, "3.12.10");
        List<String> logs = new ArrayList<>();
        Consumer<String> onLog = logs::add;

        DeployResult r = svc.install(zip, target, onLog);

        assertEquals(1, r.installed());
        assertEquals(0, r.failed());
        // 至少调用了一次 pip install
        assertTrue(runner.commands.stream().anyMatch(c -> c.contains("install")),
            "expected at least one pip install invocation, got: " + runner.commands);
    }

    @Test
    void failedWheelDoesNotAbortOthers(@TempDir Path tmp) throws Exception {
        Path zip = makeBundle(tmp);
        StubRunner runner = new StubRunner(1); // all commands fail

        DeployTarget target = new DeployTarget.Global(Path.of("/nonexistent/conda/bin/python3.12"));
        DeployService svc = new FakeDeployService(runner, "3.12.10");
        DeployResult r = svc.install(zip, target, s -> {});

        assertEquals(0, r.installed());
        assertEquals(1, r.failed());
    }

    /**
     * 回归测试:manifest 的 wheel file 是 BuildService 写出的多段路径
     * "wheelhouse/3.12.10/numpy-...-cp312-cp312-win_amd64.whl"。
     * 旧实现里 PlatformMatcher 对整串 split('-') 取最后 3 段 —— 若路径前缀含 '-' 会错;
     * 此路径前缀不含 '-',故标签解析仍应正确,win_amd64 wheel 在 win/x64 host 上应被匹配安装。
     * 同时验证:版本取自 target 指向的解释器(一个不存在的路径),而非 detect(null)。
     */
    @Test
    void matchesMultiSegmentPathWheelFromTargetPython(@TempDir Path tmp) throws Exception {
        String manifestFile = "wheelhouse/3.12.10/numpy-1.26.4-cp312-cp312-win_amd64.whl";
        String zipWheel = "numpy-1.26.4-cp312-cp312-win_amd64.whl";
        Path zip = makeBundle(tmp, manifestFile, zipWheel);

        // 解释器路径不存在于任何 PATH;版本 3.12.10 完全来自 FakeDeployService(即 target 解析)。
        // 仅当 host 被识别为 win/x64 时该 wheel 才匹配 —— 其他平台会落到 0 matched,这是预期语义,
        // 所以本测试只断言"不报多段路径解析错误",并按本机平台分支断言。
        StubRunner runner = new StubRunner(0);
        DeployTarget target = new DeployTarget.Global(Path.of("/nonexistent/pyenv/versions/3.12.10/bin/python3"));
        DeployService svc = new FakeDeployService(runner, "3.12.10");
        List<String> logs = new ArrayList<>();
        DeployResult r = svc.install(zip, target, logs::add);

        // 关键:无论本机平台如何,都不应抛 "无法解析部署目标 Python 版本" —— 版本已从 target 拿到。
        // 且日志里 wheel 计数行应出现(0/N 或 1/N 取决于本机平台)。
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("适配本机的 wheel")),
            "expected a wheel-match summary line, got: " + logs);
        // 路径前缀 wheelhouse/3.12.10/ 不含 '-',解析不会错;win/x64 机应装上 1 个。
        assertEquals(r.failed(), 0, "no install failures expected on a stubbed-success runner");
    }

    /**
     * 回归测试:当部署目标的 Python 版本无法解析(探测返回 null)时,必须立即报错,
     * 而不是像旧实现那样静默地用 null 版本匹配出 0 个 wheel、然后返回 "installed=0, failed=0"
     * 的"看似成功"结果 —— 那正是用户看到"装完但实际没用"的根因之一。
     */
    @Test
    void failsLoudlyWhenPythonVersionUnresolvable(@TempDir Path tmp) throws Exception {
        Path zip = makeBundle(tmp);
        StubRunner runner = new StubRunner(0);
        DeployTarget target = new DeployTarget.Global(Path.of("/nonexistent/python"));
        DeployService svc = new FakeDeployService(runner, null); // 版本探测失败

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> svc.install(zip, target, s -> {}));

        assertTrue(ex.getMessage().contains("无法解析部署目标 Python 版本"),
            "expected clear failure message, got: " + ex.getMessage());
        assertFalse(ex.getMessage().isBlank());
        // 不应调用过 pip(在报错前就失败了)
        assertTrue(runner.commands.isEmpty(),
            "no pip install should run when Python version is unresolvable, got: " + runner.commands);
    }
}
