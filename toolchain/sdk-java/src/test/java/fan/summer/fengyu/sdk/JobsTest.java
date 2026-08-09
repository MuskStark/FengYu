package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobsTest {

    @Test
    void cursorRemainsAbsoluteAfterOldestLinesAreEvicted() {
        Jobs.Job job = new Jobs.Job("job_test", "TEST");
        for (int i = 0; i < Jobs.Job.MAX_LOG_LINES; i++) job.append("old-" + i);

        int cursor = ((Number) job.snapshot(0).get("cursor")).intValue();
        for (int i = 0; i < 10; i++) job.append("new-" + i);

        Map<String, Object> snapshot = job.snapshot(cursor);
        @SuppressWarnings("unchecked")
        List<String> logs = (List<String>) snapshot.get("logs");
        assertEquals(10, logs.size(), "an old absolute cursor must map past evicted entries");
        assertEquals("new-0", logs.getFirst());
        assertEquals("new-9", logs.getLast());
        assertEquals(cursor + 10, ((Number) snapshot.get("cursor")).intValue());
    }

    @Test
    void byteCapCountsUtf8BytesNotUtf16Chars() {
        Jobs.Job job = new Jobs.Job("job_utf8", "TEST");
        // U+1F600 is one Unicode code point, two UTF-16 chars, and four UTF-8 wire bytes.
        String oversized = "😀".repeat((Jobs.Job.MAX_LOG_BYTES / 4) + 1);

        job.append(oversized);

        Map<String, Object> snapshot = job.snapshot(0);
        @SuppressWarnings("unchecked")
        List<String> logs = (List<String>) snapshot.get("logs");
        assertTrue(logs.isEmpty(), "one line larger than the byte budget must be evicted");
        assertEquals(1, ((Number) snapshot.get("droppedLogs")).intValue());
        assertEquals(1, ((Number) snapshot.get("cursor")).intValue());
    }
}
