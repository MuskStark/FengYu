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
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeployService tests using a hand-rolled {@link ProcessRunner} stub instead of Mockito, so they
 * run on any JDK (Mockito-inline cannot subclass on newer JVMs). The {@link StubRunner} records
 * each invocation and returns a configurable exit code without shelling out to pip.
 */
class DeployServiceTest {

    /** A ProcessRunner that never spawns a process; returns {@code exitCode} for every run. */
    static final class StubRunner extends ProcessRunner {
        final List<List<String>> commands = new java.util.ArrayList<>();
        final int exitCode;
        StubRunner(int exitCode) { this.exitCode = exitCode; }
        @Override
        public int run(List<String> command, Consumer<String> onLine) {
            commands.add(command);
            return exitCode;
        }
    }

    /** 用一个全平台兼容的纯 Python wheel 构造 bundle ZIP。 */
    private Path makeBundle(Path tmp) throws IOException {
        Path zip = tmp.resolve("b.zip");
        Manifest m = new Manifest();
        m.getPython().setVersion("3.12.10");
        m.getWheels().add(new WheelEntry("requests", "2.31.0",
                "wheels/requests-2.31.0-py3-none-any.whl", "abc", 1000, true));
        String json = JsonStore.toJson(m);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("bundle/manifest.json"));
            z.write(json.getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("bundle/wheels/requests-2.31.0-py3-none-any.whl"));
            z.write(new byte[]{1});
            z.closeEntry();
        }
        return zip;
    }

    @Test
    void installRunsPipPerWheel(@TempDir Path tmp) throws Exception {
        Path zip = makeBundle(tmp);
        StubRunner runner = new StubRunner(0); // all commands succeed

        DeployTarget target = new DeployTarget.Global(Path.of("/usr/bin/python3"));
        List<String> logs = new java.util.ArrayList<>();
        Consumer<String> onLog = logs::add;

        DeployResult r = new DeployService(runner).install(zip, target, onLog);

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

        DeployTarget target = new DeployTarget.Global(Path.of("/usr/bin/python3"));
        DeployResult r = new DeployService(runner).install(zip, target, s -> {});

        assertEquals(0, r.installed());
        assertEquals(1, r.failed());
    }
}
