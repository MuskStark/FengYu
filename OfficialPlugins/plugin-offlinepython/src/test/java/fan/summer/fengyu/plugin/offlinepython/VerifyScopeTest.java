package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import fan.summer.fengyu.plugin.offlinepython.command.VerifyService;
import fan.summer.fengyu.plugin.offlinepython.domain.Manifest;
import fan.summer.fengyu.plugin.offlinepython.domain.VerifyResult;
import fan.summer.fengyu.plugin.offlinepython.domain.VerifyScope;
import fan.summer.fengyu.plugin.offlinepython.domain.WheelEntry;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VerifyScopeTest {

    private final VerifyService svc = new VerifyService();

    @Test
    void allRunsEveryCheck(@TempDir Path tmp) throws Exception {
        Path output = tmp.resolve("output"); Files.createDirectories(output);
        Manifest m = new Manifest();
        m.getWheels().add(new WheelEntry("numpy", "1.26.4", "wheelhouse/numpy.whl", "", 0, true));
        Path wh = output.resolve("wheelhouse"); Files.createDirectories(wh);
        Files.writeString(wh.resolve("numpy.whl"), "x");
        VerifyResult r = svc.verify(output, m, VerifyScope.ALL);
        assertNotNull(r.sha256()); assertNotNull(r.fileIntegrity());
        assertNotNull(r.wheels()); assertNotNull(r.requirements()); assertNotNull(r.manifest());
    }

    @Test
    void integrityScopeSkipsSha256(@TempDir Path tmp) throws Exception {
        Path output = tmp.resolve("output"); Files.createDirectories(output);
        VerifyResult r = svc.verify(output, new Manifest(), VerifyScope.INTEGRITY);
        assertNotNull(r.fileIntegrity());
        assertNull(r.sha256());
    }

    @Test
    void sha256ScopeOnlySha256(@TempDir Path tmp) throws Exception {
        Path output = tmp.resolve("output"); Files.createDirectories(output);
        VerifyResult r = svc.verify(output, new Manifest(), VerifyScope.SHA256);
        assertNotNull(r.sha256());
        assertNull(r.fileIntegrity());
        assertNull(r.wheels());
    }
}
