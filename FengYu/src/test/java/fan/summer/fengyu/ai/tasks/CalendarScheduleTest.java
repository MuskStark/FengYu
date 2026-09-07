package fan.summer.fengyu.ai.tasks;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CalendarScheduleTest {
    @Test
    void dailyUsesLocalTimeAndStrictlyAdvancesAtTheBoundary() {
        var rule = new CalendarSchedule("DAILY", "09:00", "Asia/Shanghai", null, null);
        assertEquals(Instant.parse("2026-09-07T01:00:00Z"),
                rule.nextAfter(Instant.parse("2026-09-07T00:59:59Z")));
        assertEquals(Instant.parse("2026-09-08T01:00:00Z"),
                rule.nextAfter(Instant.parse("2026-09-07T01:00:00Z")));
    }

    @Test
    void weeklySelectsMultipleWeekdaysAndWrapsAcrossWeeks() {
        var rule = new CalendarSchedule("WEEKLY", "09:00", "UTC", List.of(1, 5), null);
        assertEquals(Instant.parse("2026-09-11T09:00:00Z"),
                rule.nextAfter(Instant.parse("2026-09-07T09:00:00Z")));
        assertEquals(Instant.parse("2026-09-14T09:00:00Z"),
                rule.nextAfter(Instant.parse("2026-09-11T09:00:00Z")));
    }

    @Test
    void monthlyClampsShortMonthsAndSupportsLeapYearsAndLastDay() {
        var rule = new CalendarSchedule("MONTHLY", "09:00", "UTC", null, 31);
        assertEquals(Instant.parse("2026-02-28T09:00:00Z"),
                rule.nextAfter(Instant.parse("2026-01-31T09:00:00Z")));
        assertEquals(Instant.parse("2028-02-29T09:00:00Z"),
                rule.nextAfter(Instant.parse("2028-01-31T09:00:00Z")));
        var last = new CalendarSchedule("MONTHLY", "09:00", "UTC", null, -1);
        assertEquals(Instant.parse("2027-01-31T09:00:00Z"),
                last.nextAfter(Instant.parse("2026-12-31T09:00:00Z")));
    }

    @Test
    void daylightSavingGapShiftsForwardAndOverlapFiresOnlyOnce() {
        var gap = new CalendarSchedule("DAILY", "02:30", "America/New_York", null, null);
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"),
                gap.nextAfter(Instant.parse("2026-03-07T07:30:00Z")));
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"),
                gap.nextAfter(Instant.parse("2026-03-08T07:30:00Z")));
        var overlap = new CalendarSchedule("DAILY", "01:30", "America/New_York", null, null);
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"),
                overlap.nextAfter(Instant.parse("2026-11-01T05:30:00Z")));
    }

    @Test
    void rejectsInvalidCalendarInputs() {
        assertThrows(IllegalArgumentException.class, () -> new CalendarSchedule("YEARLY", "09:00", "UTC", null, null));
        assertThrows(IllegalArgumentException.class, () -> new CalendarSchedule("DAILY", "25:00", "UTC", null, null));
        assertThrows(IllegalArgumentException.class, () -> new CalendarSchedule("DAILY", "09:00", "invalid", null, null));
        assertThrows(IllegalArgumentException.class, () -> new CalendarSchedule("WEEKLY", "09:00", "UTC", List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new CalendarSchedule("WEEKLY", "09:00", "UTC", List.of(8), null));
        assertThrows(IllegalArgumentException.class, () -> new CalendarSchedule("MONTHLY", "09:00", "UTC", null, 0));
    }
}
