package fan.summer.fengyu.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsJobSandboxTest {

    @Test
    void extendedLimitInitializesNestedNativeStructures() {
        WindowsJobSandbox.ExtendedLimit limits = new WindowsJobSandbox.ExtendedLimit();
        assertNotNull(limits.basicLimitInformation);
        assertNotNull(limits.ioInfo);
    }

    /** On non-Windows the JNA Win32 classes must not load; isAvailable() returns false cleanly. */
    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "JNA Win32 binding only loads on Windows")
    void isAvailableReflectsHost() {
        // On Windows this runs and asserts true; on mac/linux it is skipped.
        assertTrue(WindowsJobSandbox.isAvailable());
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "Job Object API is Windows-only")
    void jobCreatesAndAssignsAndKillsOnClose() throws Exception {
        long job = WindowsJobSandbox.createAndConfigureJob();
        assertNotEquals(0L, job, "job handle should be non-zero");
        // Spawn a long-sleeping child and assign it. `timeout /T 30 /NOBREAK` sleeps ~30s.
        Process sleepChild = new ProcessBuilder("cmd", "/c", "timeout", "/T", "30", "/NOBREAK").start();
        try {
            WindowsJobSandbox.assign(job, sleepChild);
            assertTrue(sleepChild.isAlive(), "child alive after assign");
            // Closing the job handle triggers KILL_ON_JOB_CLOSE — the child dies.
            WindowsJobSandbox.closeHandle(job);
            boolean exited = sleepChild.waitFor(5, TimeUnit.SECONDS);
            assertTrue(exited, "child exited within 5s of job handle close");
            assertFalse(sleepChild.isAlive(), "child killed when job handle closed");
        } finally {
            sleepChild.destroyForcibly();
        }
    }
}
