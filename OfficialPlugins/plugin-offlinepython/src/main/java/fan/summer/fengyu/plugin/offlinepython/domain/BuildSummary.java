package fan.summer.fengyu.plugin.offlinepython.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Outcome of a build, surfaced as result tiles on the BuildPanel. */
public record BuildSummary(int totalWheels, int cacheHits, long totalBytes, long durationMs) {

    /** Count wheels, sum sizes. preExisting = wheels already in the wheelhouse before build (cache hits). */
    public static BuildSummary compute(Path wheelhouse, int preExisting, long durationMs) {
        int total = 0;
        long bytes = 0;
        try (Stream<Path> files = Files.list(wheelhouse)) {
            var list = files.filter(p -> p.getFileName().toString().endsWith(".whl")).toList();
            total = list.size();
            for (Path p : list) bytes += Files.size(p);
        } catch (IOException e) {
            // fall through with whatever counted
        }
        return new BuildSummary(total, Math.min(preExisting, total), bytes, durationMs);
    }
}
