package fan.summer.fengyu.plugin.offlinepython.infra;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates python/pip on the host and parses their version output.
 * Pure parsing helpers are unit-tested; {@link #detect(String)} shells out.
 */
public final class PythonDetector {

    private static final Pattern PY_VER = Pattern.compile("Python (\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern PIP_VER = Pattern.compile("pip (\\d+(?:\\.\\d+)*)");

    private PythonDetector() {}

    public static Optional<String> parsePythonVersion(String out) {
        if (out == null) return Optional.empty();
        Matcher m = PY_VER.matcher(out);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public static Optional<String> parsePipVersion(String out) {
        if (out == null) return Optional.empty();
        Matcher m = PIP_VER.matcher(out);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** True if actual >= required (compared component-wise). */
    public static boolean isAtLeast(String actual, String required) {
        int[] a = parts(actual);
        int[] r = parts(required);
        int n = Math.max(a.length, r.length);
        for (int i = 0; i < n; i++) {
            int ai = i < a.length ? a[i] : 0;
            int ri = i < r.length ? r[i] : 0;
            if (ai != ri) return ai > ri;
        }
        return true;
    }

    private static int[] parts(String v) {
        String[] s = v.split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) out[i] = Integer.parseInt(s[i]);
        return out;
    }

    /** Result of a detection attempt. */
    public record Detection(String executable, String pythonVersion, String pipVersion) {
        public boolean ok() { return executable != null && pythonVersion != null; }
    }

    /** Try the configured executable first, then python3 / python on PATH. */
    public static Detection detect(String configuredExecutable) {
        List<String> candidates = new ArrayList<>();
        if (configuredExecutable != null && !configuredExecutable.isBlank()) candidates.add(configuredExecutable);
        candidates.add("python3");
        candidates.add("python");
        for (String c : candidates) {
            String resolved = resolveOnPath(c);
            if (resolved == null) continue;
            String pyVer = capture(resolved, "--version");
            Optional<String> pv = parsePythonVersion(pyVer);
            if (pv.isEmpty()) continue;
            String pipVer = parsePipVersion(capture(resolved, "-m", "pip", "--version")).orElse(null);
            return new Detection(resolved, pv.get(), pipVer);
        }
        return new Detection(null, null, null);
    }

    static String resolveOnPath(String cmd) {
        // If it's an absolute/existing path, use directly; else trust PATH (returns input).
        File f = new File(cmd);
        if (f.isAbsolute()) return f.exists() ? cmd : null;
        return cmd; // ProcessBuilder resolves via PATH at runtime
    }

    /** Run a command, return combined stdout+stderr as a string (best-effort, short timeout). */
    static String capture(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return sb.toString();
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }
}
