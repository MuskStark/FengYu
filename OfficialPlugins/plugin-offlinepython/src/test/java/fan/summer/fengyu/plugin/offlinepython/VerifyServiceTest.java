package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import fan.summer.fengyu.plugin.offlinepython.domain.Manifest;
import fan.summer.fengyu.plugin.offlinepython.domain.Status;
import fan.summer.fengyu.plugin.offlinepython.domain.VerifyResult;
import fan.summer.fengyu.plugin.offlinepython.domain.WheelEntry;
import fan.summer.fengyu.plugin.offlinepython.command.VerifyService;
import fan.summer.fengyu.plugin.offlinepython.infra.HashUtil;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VerifyServiceTest {

    private void seedRepo(Path output, Manifest m) throws Exception {
        for (WheelEntry w : m.getWheels()) {
            Path f = output.resolve(w.getFile());
            Files.createDirectories(f.getParent());
            Files.writeString(f, "wheel-" + w.getName());
            w.setSha256(HashUtil.sha256Hex(f));
            w.setSize(Files.size(f));
        }
    }

    @Test
    void passesWhenAllFilesPresentAndHashesMatch(@TempDir Path tmp) throws Exception {
        Path output = tmp.resolve("output");
        Files.createDirectories(output);
        Manifest m = new Manifest();
        m.getWheels().add(new WheelEntry("numpy", "1.26.4", "wheelhouse/numpy.whl", "", 0, true));
        m.getRequirements().add("numpy==1.26.4");
        seedRepo(output, m);

        VerifyResult r = new VerifyService().verify(output, m);
        assertEquals(Status.PASS, r.fileIntegrity().status());
        assertEquals(Status.PASS, r.sha256().status());
        assertEquals(Status.PASS, r.requirements().status());
        assertTrue(r.isOk());
    }

    @Test
    void failsFileIntegrityWhenWheelMissing(@TempDir Path tmp) throws Exception {
        Path output = tmp.resolve("output");
        Files.createDirectories(output);
        Manifest m = new Manifest();
        m.getWheels().add(new WheelEntry("numpy", "1.26.4", "wheelhouse/numpy.whl", "", 0, true));
        // intentionally do NOT create the file
        VerifyResult r = new VerifyService().verify(output, m);
        assertEquals(Status.FAIL, r.fileIntegrity().status());
        assertFalse(r.isOk());
    }

    @Test
    void warnsWhenRequirementNotSatisfied(@TempDir Path tmp) throws Exception {
        Path output = tmp.resolve("output");
        Files.createDirectories(output);
        Manifest m = new Manifest();
        m.getRequirements().add("scipy==1.13.0");  // no wheel for scipy
        m.getWheels().add(new WheelEntry("numpy", "1.26.4", "wheelhouse/numpy.whl", "", 0, true));
        seedRepo(output, m);
        VerifyResult r = new VerifyService().verify(output, m);
        assertEquals(Status.WARN, r.requirements().status());
    }

    @Test
    void failsSha256WhenHashMismatches(@TempDir Path tmp) throws Exception {
        Path output = tmp.resolve("output");
        Files.createDirectories(output);
        Manifest m = new Manifest();
        m.getWheels().add(new WheelEntry("numpy", "1.26.4", "wheelhouse/numpy.whl", "00wrong", 0, true));
        seedRepo(output, m);
        // seedRepo recomputed the hash, so force a wrong one back:
        m.getWheels().get(0).setSha256("00wrong");
        VerifyResult r = new VerifyService().verify(output, m);
        assertEquals(Status.FAIL, r.sha256().status());
    }

    @Test
    void dashedRequirementMatchesUnderscoreWheel(@TempDir Path tmp) throws Exception {
        Path output = tmp.resolve("output");
        Files.createDirectories(output);
        Manifest m = new Manifest();
        m.getRequirements().add("scikit-learn==1.4.0");
        // wheel filename uses underscore (PEP 427)
        m.getWheels().add(new WheelEntry("scikit_learn", "1.4.0", "wheelhouse/scikit_learn.whl", "", 0, true));
        seedRepo(output, m);
        VerifyResult r = new VerifyService().verify(output, m);
        assertEquals(Status.PASS, r.requirements().status(),
                "scikit-learn requirement must be satisfied by scikit_learn wheel");
    }
}
