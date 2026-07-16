package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import fan.summer.fengyu.plugin.offlinepython.command.DoctorService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DoctorServiceTest {

    @Test
    void runReturnsExpectedCheckNames() {
        List<DoctorService.Check> checks = new DoctorService().run(null);
        var names = checks.stream().map(DoctorService.Check::name).toList();
        assertTrue(names.contains("Python 解释器"));
        assertTrue(names.contains("Python 版本"));
        assertTrue(names.contains("pip"));
        assertTrue(names.contains("pip download 可用"));
        assertTrue(names.contains("网络 (PyPI)"));
        assertTrue(names.contains("磁盘空间"));
        assertTrue(names.contains("缓存目录"));
        assertTrue(names.size() >= 7);
    }

    @Test
    void pipDownloadAvailableDetectsPlatformFlag() {
        assertTrue(DoctorService.parsePipDownloadSupportsPlatform(
                "usage: pip download ... --platform <platform> ..."));
        assertFalse(DoctorService.parsePipDownloadSupportsPlatform("usage: pip download ..."));
    }
}
