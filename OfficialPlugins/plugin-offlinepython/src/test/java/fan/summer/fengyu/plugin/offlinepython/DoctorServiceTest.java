package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import fan.summer.fengyu.plugin.offlinepython.command.DoctorService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DoctorServiceTest {

    @Test
    void runReturnsExpectedCheckNames() {
        List<DoctorService.Check> checks = new DoctorService().run(null);
        // ids are stable, locale-independent identifiers the UI translates.
        var ids = checks.stream().map(DoctorService.Check::id).toList();
        assertTrue(ids.contains("python_interpreter"));
        assertTrue(ids.contains("python_version"));
        assertTrue(ids.contains("pip"));
        assertTrue(ids.contains("pip_download"));
        assertTrue(ids.contains("network"));
        assertTrue(ids.contains("disk_space"));
        assertTrue(ids.contains("cache_dir"));
        assertTrue(ids.size() >= 7);
    }

    @Test
    void pipDownloadAvailableDetectsPlatformFlag() {
        assertTrue(DoctorService.parsePipDownloadSupportsPlatform(
                "usage: pip download ... --platform <platform> ..."));
        assertFalse(DoctorService.parsePipDownloadSupportsPlatform("usage: pip download ..."));
    }
}
