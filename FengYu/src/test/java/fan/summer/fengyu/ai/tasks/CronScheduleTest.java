package fan.summer.fengyu.ai.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Five-field cron parsing/validation and next-occurrence math, plus the scheduler-level
 * "missed occurrences are coalesced, never replayed" contract for cron schedules
 * (same mock-scheduler technique as BackgroundTaskSchedulerTest). All next-run
 * expectations are built from the machine's local zone — the documented evaluation
 * zone — so the test is independent of the machine it runs on.
 */
class CronScheduleTest {

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.systemDefault())
                .toInstant();
    }

    @Test
    void parsesFiveFieldExpressionsIncludingNamesStepsAndLists() {
        // Constructor completing IS the parse test: an invalid atom throws before returning.
        new CronSchedule("30 9 * * *");
        new CronSchedule("*/15 * * * *");
        new CronSchedule("5/15 * * * *");
        new CronSchedule("1-15/2 * * * *");
        new CronSchedule("*/15 9-18 * * 1-5");
        new CronSchedule("0 9 * * MON,WED,FRI");
        new CronSchedule("0 9 1 JAN,DEC *");
        // Whitespace between fields is normalized away.
        new CronSchedule("  30   9  *  *  *  ");
    }

    @Test
    void rejectsNonFiveFieldShapesWithACountMessage() {
        for (String expression : List.of(
                "30 9 * *",                // four fields
                "0 30 9 * * *",            // six fields (seconds)
                "*/5 * * * * *",           // six fields (seconds, step form)
                "0 30 9 * * * 2030")) {    // seven fields (year)
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> new CronSchedule(expression), expression);
            assertTrue(rejected.getMessage().contains("exactly 5 fields"), rejected.getMessage());
            assertTrue(rejected.getMessage().contains("found"), rejected.getMessage());
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new CronSchedule(" "))
                .getMessage().contains("required"));
    }

    @Test
    void rejectsInvalidFieldValuesNamingTheField() {
        String month = assertThrows(IllegalArgumentException.class,
                () -> new CronSchedule("* * * 13 *")).getMessage();
        assertTrue(month.contains("'month'") && month.contains("13"), month);
        String dayOfWeek = assertThrows(IllegalArgumentException.class,
                () -> new CronSchedule("* * * * 8")).getMessage();
        assertTrue(dayOfWeek.contains("'day-of-week'") && dayOfWeek.contains("8"), dayOfWeek);
        String step = assertThrows(IllegalArgumentException.class,
                () -> new CronSchedule("*/0 * * * *")).getMessage();
        assertTrue(step.contains("'minute'") && step.contains("step"), step);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new CronSchedule("60 * * * *"))
                .getMessage().contains("'minute'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new CronSchedule("* 24 * * *"))
                .getMessage().contains("'hour'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new CronSchedule("* * 32 * *"))
                .getMessage().contains("'day-of-month'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new CronSchedule("* * 0 * *"))
                .getMessage().contains("'day-of-month'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new CronSchedule("* * * 2,13 *"))
                .getMessage().contains("'month'"));
        // Spring 7 cannot parse name ranges — the error must say so and suggest the list form.
        String nameRange = assertThrows(IllegalArgumentException.class,
                () -> new CronSchedule("* * * * MON-FRI")).getMessage();
        assertTrue(nameRange.contains("name ranges"), nameRange);
    }

    @Test
    void computesNextOccurrenceStrictlyAfterInLocalTime() {
        var daily = new CronSchedule("30 9 * * *");
        assertEquals(at(2026, 10, 28, 9, 30), daily.nextAfter(at(2026, 10, 28, 9, 29)));
        // At the boundary itself the next day's occurrence is due — never the same instant.
        assertEquals(at(2026, 10, 29, 9, 30), daily.nextAfter(at(2026, 10, 28, 9, 30)));

        var quarterHours = new CronSchedule("*/15 9-18 * * *");
        assertEquals(at(2026, 10, 28, 10, 15), quarterHours.nextAfter(at(2026, 10, 28, 10, 0)));
        // Still inside the hour range: the next quarter-hour slot of the same hour fires.
        assertEquals(at(2026, 10, 28, 18, 30), quarterHours.nextAfter(at(2026, 10, 28, 18, 15)));
        // Past the last slot of the hour range the next morning's first slot fires.
        assertEquals(at(2026, 10, 29, 9, 0), quarterHours.nextAfter(at(2026, 10, 28, 18, 45)));
    }

    @Test
    void weekdayNameListsSelectDaysAcrossWeeks() {
        var monWedFri = new CronSchedule("0 9 * * MON,WED,FRI");
        // 2026-10-28 is a Wednesday; 2026-10-30 a Friday; 2026-11-02 the next Monday.
        assertEquals(at(2026, 10, 28, 9, 0), monWedFri.nextAfter(at(2026, 10, 28, 8, 0)));
        assertEquals(at(2026, 10, 30, 9, 0), monWedFri.nextAfter(at(2026, 10, 28, 9, 0)));
        assertEquals(at(2026, 11, 2, 9, 0), monWedFri.nextAfter(at(2026, 10, 30, 9, 0)));
    }

    @Test
    void crossesMonthAndYearBoundaries() {
        assertEquals(at(2026, 2, 1, 0, 0),
                new CronSchedule("0 0 1 * *").nextAfter(at(2026, 1, 31, 12, 0)));
        assertEquals(at(2027, 1, 1, 0, 0),
                new CronSchedule("0 0 1 1 *").nextAfter(at(2026, 12, 31, 23, 59)));
        // Month names, comma-listed: December then January of the following year.
        var janDec = new CronSchedule("0 9 1 JAN,DEC *");
        assertEquals(at(2026, 12, 1, 9, 0), janDec.nextAfter(at(2026, 11, 1, 12, 0)));
        assertEquals(at(2027, 1, 1, 9, 0), janDec.nextAfter(at(2026, 12, 1, 9, 0)));
    }

    @Test
    void monthEndSkipsShortFebruariesAndLandsOnLeapDay() {
        // Day 31 exists in no February: the fire skips straight to March 31.
        assertEquals(at(2026, 3, 31, 0, 0),
                new CronSchedule("0 0 31 * *").nextAfter(at(2026, 1, 31, 0, 0)));
        // Feb 29 only in leap years: from March 2026 the next occurrence is 2028-02-29.
        assertEquals(at(2028, 2, 29, 0, 0),
                new CronSchedule("0 0 29 2 *").nextAfter(at(2026, 3, 1, 0, 0)));
        // And from the leap day itself the next one is four years later.
        assertEquals(at(2032, 2, 29, 0, 0),
                new CronSchedule("0 0 29 2 *").nextAfter(at(2028, 2, 29, 0, 0)));
    }

    /**
     * Missed occurrences are not replayed: a cron schedule found several periods overdue
     * fires ONCE (the recovery fire — recorded here as a failure because the test has no
     * execution service), counts the excess as missedFires, and jumps its clock to the
     * first future occurrence. Mirrors the interval-schedule test in
     * BackgroundTaskSchedulerTest.
     */
    @Test
    @SuppressWarnings("unchecked")
    void missedCronOccurrencesCoalesceInsteadOfReplaying() {
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowExecutionService> executions =
                mock(ObjectProvider.class);
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowService> workflowProvider =
                mock(ObjectProvider.class);
        when(workflowProvider.getIfAvailable()).thenReturn(mock(
                fan.summer.fengyu.ai.workflow.WorkflowService.class));
        fan.summer.fengyu.database.repository.ai.WorkflowScheduleRepository repository =
                mock(fan.summer.fengyu.database.repository.ai.WorkflowScheduleRepository.class);
        when(repository.save(any(fan.summer.fengyu.database.entity.ai.WorkflowScheduleEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByClaimedAtIsNotNull()).thenReturn(List.of());
        fan.summer.fengyu.security.SecurityContext security =
                mock(fan.summer.fengyu.security.SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(new BackgroundTaskRegistry(),
                executions, workflowProvider, repository, security, false);

        BackgroundTaskScheduler.Schedule schedule = scheduler.create("wf-1", Map.of(), 3600,
                true, false, null, new CronSchedule("* * * * *"), null);

        // Pretend the app was down for four whole minutes plus the current (partial) one.
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        schedule.nextFireAt = minute.minus(4, ChronoUnit.MINUTES);
        scheduler.tick();

        assertEquals(4, schedule.missedFires, "five due minutes coalesce into one recovery fire");
        assertEquals(0, schedule.fires, "nothing actually ran (no execution service in the test)");
        assertNotNull(schedule.lastError, "the single recovery fire attempt is recorded");
        assertEquals(minute.plus(1, ChronoUnit.MINUTES), schedule.nextFireAt,
                "the clock jumps to the next future minute, never backwards");
    }
}
