package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import fan.summer.fengyu.plugin.offlinepython.domain.BuildSummary;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BuildSummaryTest {

    @Test
    void computeCountsWheelsSizesAndCacheHits(@TempDir Path tmp) throws Exception {
        Path wh = tmp.resolve("wheelhouse");
        Files.createDirectories(wh);
        Files.write(wh.resolve("numpy-1.26.4-cp312-cp312-win_amd64.whl"), new byte[19098624]);
        Files.write(wh.resolve("pandas-2.2.0-cp312-cp312-win_amd64.whl"), new byte[12000000]);

        BuildSummary s = BuildSummary.compute(wh, 1, 92_000);
        assertEquals(2, s.totalWheels());
        assertEquals(1, s.cacheHits());
        assertEquals(19098624L + 12000000L, s.totalBytes());
        assertEquals(92_000L, s.durationMs());
    }

    @Test
    void computeEmptyWheelhouse(@TempDir Path tmp) throws Exception {
        Path wh = tmp.resolve("wheelhouse");
        Files.createDirectories(wh);
        BuildSummary s = BuildSummary.compute(wh, 0, 0);
        assertEquals(0, s.totalWheels());
        assertEquals(0L, s.totalBytes());
    }
}
