package fan.summer.fengyu.plugin.offlinepython.infra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs subprocesses, streaming stdout/stderr line-by-line to a sink (the log console).
 * Supports cancellation via the volatile {@code destroyed} flag set by {@link #cancel()}.
 */
public class ProcessRunner {

    private Process process;
    private volatile boolean destroyed;

    /** Build the platform-targeted pip download command list. Requirement specs are passed
     *  inline as positional args (pip accepts multiple). One --platform is emitted per selected
     *  platform (empty selection falls back to "any"). recursive=false adds --no-deps. */
    public static List<String> pipDownloadCommand(String python, List<String> requirementSpecs,
                                                  String destDir, List<String> platforms,
                                                  String pythonVersion, String implementation,
                                                  boolean onlyBinary, boolean recursive) {
        List<String> cmd = new ArrayList<>();
        cmd.add(python);
        cmd.addAll(List.of("-m", "pip", "download"));
        if (requirementSpecs != null) cmd.addAll(requirementSpecs);
        cmd.addAll(List.of("-d", destDir));
        List<String> plats = (platforms == null || platforms.isEmpty()) ? List.of("any") : platforms;
        for (String p : plats) cmd.addAll(List.of("--platform", p));
        cmd.addAll(List.of("--python-version", pythonVersion, "--implementation", implementation));
        if (onlyBinary) cmd.add("--only-binary=:all:");
        if (!recursive) cmd.add("--no-deps");
        return cmd;
    }

    /** Run a command, sending each output line to {@code onLine}. Returns exit code. */
    public int run(List<String> command, Consumer<String> onLine) throws IOException, InterruptedException {
        destroyed = false;
        process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!destroyed && (line = r.readLine()) != null) {
                onLine.accept(line);
            }
        }
        if (destroyed) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            return -1;
        }
        return process.waitFor();
    }

    public void cancel() {
        destroyed = true;
        if (process != null) process.destroyForcibly();
    }

    /** Run a short command and return combined stdout (best-effort, quiet). */
    public static String captureQuiet(String... cmd) {
        try {
            return new String(Runtime.getRuntime().exec(cmd).getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
