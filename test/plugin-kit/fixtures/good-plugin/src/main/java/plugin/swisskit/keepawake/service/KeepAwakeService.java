package plugin.swisskit.keepawake.service;

import java.awt.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KeepAwakeService {

    private static final KeepAwakeService INSTANCE = new KeepAwakeService();

    public static KeepAwakeService getInstance() {
        return INSTANCE;
    }

    private enum Method { SYSTEM_API, MOUSE_SIMULATION }

    private Process caffeinateProcess;
    private ScheduledExecutorService mouseScheduler;
    private volatile boolean running;
    private volatile Method activeMethod;
    private volatile long startTimeMs;
    private final Robot robot;

    /** User preference: true = system API (with fallback), false = mouse simulation only. */
    private volatile boolean useSystemApi = true;

    private KeepAwakeService() {
        Robot r;
        try {
            r = new Robot();
        } catch (AWTException e) {
            r = null;
        }
        this.robot = r;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        startTimeMs = System.currentTimeMillis();

        if (useSystemApi) {
            if (trySystemApi()) {
                activeMethod = Method.SYSTEM_API;
            } else {
                startMouseSimulation();
                activeMethod = Method.MOUSE_SIMULATION;
            }
        } else {
            startMouseSimulation();
            activeMethod = Method.MOUSE_SIMULATION;
        }
    }

    public synchronized void stop() {
        running = false;
        stopSystemApi();
        stopMouseSimulation();
        activeMethod = null;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isSystemApi() {
        return activeMethod == Method.SYSTEM_API;
    }

    public boolean isMouseSimulation() {
        return activeMethod == Method.MOUSE_SIMULATION;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public boolean isUseSystemApi() {
        return useSystemApi;
    }

    public void setUseSystemApi(boolean useSystemApi) {
        this.useSystemApi = useSystemApi;
    }

    private boolean trySystemApi() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                caffeinateProcess = new ProcessBuilder("caffeinate", "-i").start();
                return true;
            } else if (os.contains("win")) {
                String script = "Add-Type @\"\n" +
                        "using System;\n" +
                        "using System.Runtime.InteropServices;\n" +
                        "public class Sleep {\n" +
                        "  [DllImport(\\\"kernel32.dll\\\")] public static extern uint SetThreadExecutionState(uint esFlags);\n" +
                        "}\n" +
                        "\"@ -PassThru; while($true) { [Sleep]::SetThreadExecutionState(0x80000003); Start-Sleep -Seconds 30 }";
                caffeinateProcess = new ProcessBuilder("powershell", "-Command", script).start();
                return true;
            } else if (os.contains("nux") || os.contains("nix")) {
                caffeinateProcess = new ProcessBuilder("xdg-screensaver", "reset").start();
                caffeinateProcess.destroy();
                caffeinateProcess = null;
                return false;
            }
        } catch (Exception e) {
            // fallback to mouse simulation
        }
        return false;
    }

    private void startMouseSimulation() {
        if (robot == null) return;
        mouseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KeepAwake-Mouse");
            t.setDaemon(true);
            return t;
        });
        mouseScheduler.scheduleAtFixedRate(() -> {
            try {
                Point loc = MouseInfo.getPointerInfo().getLocation();
                int dx = 1;
                robot.mouseMove(loc.x + dx, loc.y);
                robot.delay(50);
                robot.mouseMove(loc.x, loc.y);
            } catch (Exception ignored) {
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    private void stopSystemApi() {
        if (caffeinateProcess != null) {
            caffeinateProcess.destroy();
            caffeinateProcess = null;
        }
    }

    private void stopMouseSimulation() {
        if (mouseScheduler != null) {
            mouseScheduler.shutdownNow();
            mouseScheduler = null;
        }
    }
}
