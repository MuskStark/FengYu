package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ComputerAppsTest {

    /** Records every command and returns a canned stdout. */
    private static final class RecordingRunner implements ComputerApps.CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        String output = "";

        @Override
        public String run(List<String> command, long timeoutMillis) {
            commands.add(command);
            return output;
        }
    }

    @Test
    void launchUsesFixedArgvPerOs() {
        var mac = new RecordingRunner();
        new ComputerApps("Mac OS X", mac).launch("Safari");
        assertEquals(List.of("open", "-a", "Safari"), mac.commands.get(0));

        var win = new RecordingRunner();
        new ComputerApps("Windows 11", win).launch("notepad");
        assertEquals(List.of("powershell", "-NoProfile", "-Command", "Start-Process -FilePath 'notepad'"),
                win.commands.get(0));

        var linux = new RecordingRunner();
        new ComputerApps("Linux", linux).launch("code");
        assertEquals(List.of("gtk-launch", "code"), linux.commands.get(0));
    }

    @Test
    void activateQuotesAppNameIntoScript() {
        var mac = new RecordingRunner();
        new ComputerApps("Mac OS X", mac).activate("Visual Studio Code");
        assertEquals(List.of("osascript", "-e", "tell application \"Visual Studio Code\" to activate"),
                mac.commands.get(0));

        var win = new RecordingRunner();
        new ComputerApps("Windows 11", win).activate("notepad");
        assertEquals(4, win.commands.get(0).size());
        assertTrue(win.commands.get(0).get(3).contains("AppActivate"));
        assertTrue(win.commands.get(0).get(3).contains("'notepad'"));
    }

    @Test
    void windowsActivateStripsExeSuffixAndFallsBackToTitleMatch() {
        var win = new RecordingRunner();
        new ComputerApps("Windows 11", win).activate("Notepad.EXE");
        String script = win.commands.get(0).get(3);
        // Get-Process wants the bare process name — ".exe" must be stripped case-insensitively.
        assertTrue(script.contains("$n = 'Notepad';"), script);
        assertFalse(script.contains("Notepad.EXE"), script);
        // Chain: PID focus first, then window-title match, then a hard failure.
        assertTrue(script.contains("AppActivate($p.Id)"), script);
        assertTrue(script.contains("AppActivate($n)"), script);
        assertTrue(script.contains("throw ('no window found for ' + $n)"), script);

        // Friendly product names (no matching process name) still resolve through the
        // title fallback — e.g. "Visual Studio Code" windows hosted by the "Code" process.
        var friendly = new RecordingRunner();
        new ComputerApps("Windows 11", friendly).activate("Visual Studio Code");
        assertTrue(friendly.commands.get(0).get(3).contains("$n = 'Visual Studio Code';"));
    }

    @Test
    void listParsesMacCommaSeparatedAndWindowsLines() {
        var mac = new RecordingRunner();
        mac.output = "Finder, Safari, FengYu, Some App";
        assertEquals(List.of("Finder", "Safari", "FengYu", "Some App"),
                new ComputerApps("Mac OS X", mac).list());

        var win = new RecordingRunner();
        win.output = "explorer\r\nnotepad\r\n";
        assertEquals(List.of("explorer", "notepad"),
                new ComputerApps("Windows 11", win).list());
    }

    @Test
    void listParsesWmctrlLinesOnLinux() {
        var linux = new RecordingRunner();
        linux.output = "0x03a00007  0 hostname Terminal — zsh\n0x03a0000b  0 hostname Notes";
        assertEquals(List.of("Terminal — zsh", "Notes"),
                new ComputerApps("Linux", linux).list());
    }

    @Test
    void listFailureAddsPlatformHint() {
        var failing = new ComputerApps("Mac OS X", (command, timeout) -> {
            throw new IllegalStateException("osascript is not allowed assistive access");
        });
        IllegalStateException e = assertThrows(IllegalStateException.class, failing::list);
        assertTrue(e.getMessage().contains("Accessibility"), e.getMessage());
    }

    @Test
    void rejectsDangerousAppNames() {
        ComputerApps mac = new ComputerApps("Mac OS X", new RecordingRunner());
        assertThrows(IllegalArgumentException.class, () -> mac.launch("Safari & rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> mac.launch("x\"; tell app \"Finder\" to quit"));
        assertThrows(IllegalArgumentException.class, () -> mac.launch("  "));
        assertThrows(IllegalArgumentException.class, () -> mac.launch(null));
        assertThrows(IllegalArgumentException.class, () -> mac.activate("$HOME"));
    }

    @Test
    void commandTimeoutIsNotBlockedByAnInheritedOpenStdout() {
        if (System.getProperty("os.name", "").toLowerCase().startsWith("win")) return;

        long started = System.nanoTime();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ComputerApps.runCommand(List.of("sh", "-c", "sleep 30"), 100));

        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000,
                "command timeout took too long");
    }
}
