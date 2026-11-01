package fan.summer.fengyu.ai.tasks;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Recurrence given by a cron expression in the classic unix five-field form
 * (minute hour day-of-month month day-of-week), evaluated in the server's local
 * time zone.
 *
 * <p><b>Why five fields only.</b> Spring's {@link CronExpression} demands a six-field
 * expression (seconds first). We deliberately expose only the unix five-field dialect —
 * the one users know from crontab — and pin the seconds field to {@code 0} internally,
 * so every accepted expression means the same thing in the UI, in the REST validation
 * error and in the scheduler. Seconds-level and year-bearing six/seven-field forms are
 * rejected with a field-count error before Spring ever sees them.
 *
 * <p><b>Why server-local time.</b> Like a bare crontab entry (and ZCode's scheduling
 * semantics), the expression is parsed against the machine's local clock and never
 * converted to UTC — a user writing {@code 30 9 * * *} means 09:30 where the server
 * stands. DST behaviour therefore follows {@link CronExpression}: a wall-clock time
 * skipped by a spring-forward gap yields no fire that day, an autumn overlap fires once.
 *
 * <p>Validation is two-layered. A field-by-field check runs first so rejections name the
 * offending field ("Cron 'month' field: 13 is out of range 1-12"); the expression is then
 * handed to {@link CronExpression} as the authoritative syntax oracle. The accepted atom
 * grammar is the unix subset — {@code *}, plain numbers, {@code a-b} ranges, {@code /n}
 * steps and comma lists, plus three-letter month/day-of-week names ({@code JAN}, {@code MON})
 * as single values or lists. Quartz-only tokens ({@code L}, {@code W}, {@code #}) belong to
 * the six-field dialect and are rejected here; name <em>ranges</em> ({@code MON-FRI}) are
 * rejected too because Spring 7's parser does not accept them — the error suggests the
 * equivalent comma list instead of surfacing Spring's opaque one.
 */
public record CronSchedule(String expression) {

    private static final String FIELD_SHAPE =
            "Cron expression must have exactly 5 fields (minute hour day-of-month month day-of-week)";
    private static final String[] FIELD_NAMES =
            {"minute", "hour", "day-of-month", "month", "day-of-week"};
    private static final int[] FIELD_MIN = {0, 0, 1, 1, 0};
    private static final int[] FIELD_MAX = {59, 23, 31, 12, 7};
    private static final List<String> MONTH_NAMES = List.of(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final List<String> DAY_NAMES = List.of(
            "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");

    public CronSchedule {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("A cron expression is required");
        }
        expression = expression.trim();
        String[] fields = expression.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException(FIELD_SHAPE + "; found " + fields.length);
        }
        for (int i = 0; i < 5; i++) {
            validateField(i, fields[i]);
        }
        // Authoritative final check: everything the per-field grammar above accepts must
        // also be accepted by the engine that will compute the fires. Note this cannot
        // call cron() — inside a compact constructor the record field is only assigned
        // after the body returns, so the instance method would still read null.
        CronExpression.parse("0 " + String.join(" ", fields));
    }

    /** Strictly-after semantics, same contract as {@link CalendarSchedule#nextAfter}. */
    public Instant nextAfter(Instant after) {
        ZonedDateTime next = cron().next(after.atZone(ZoneId.systemDefault()));
        if (next == null) {
            throw new IllegalArgumentException("No next cron occurrence");
        }
        return next.toInstant();
    }

    /** Spring needs the six-field form; seconds are pinned to 0 (minute granularity). */
    private CronExpression cron() {
        return CronExpression.parse("0 " + expression);
    }

    private static void validateField(int index, String field) {
        String name = FIELD_NAMES[index];
        for (String atom : field.split(",", -1)) {
            if (atom.isBlank()) {
                throw new IllegalArgumentException(
                        "Cron '" + name + "' field: empty value in list '" + field + "'");
            }
            // '?' is the Quartz alias for '*' and is only meaningful (and only accepted by
            // Spring) in the two day fields.
            boolean dayField = index == 2 || index == 4;
            if ("*".equals(atom) || ("?".equals(atom) && dayField)) continue;
            String[] stepParts = atom.split("/", -1);
            if (stepParts.length > 2) {
                throw new IllegalArgumentException(
                        "Cron '" + name + "' field: at most one '/' is allowed in '" + atom + "'");
            }
            if (stepParts.length == 2 && !validStep(stepParts[1])) {
                throw new IllegalArgumentException("Cron '" + name + "' field: step must be "
                        + "a positive number in '" + atom + "'");
            }
            // A bare wildcard base ("*/n") is fine — the step itself was validated above.
            String range = stepParts[0];
            if ("*".equals(range) || ("?".equals(range) && dayField)) continue;
            String[] bounds = range.split("-", -1);
            if (bounds.length > 2) {
                throw new IllegalArgumentException(
                        "Cron '" + name + "' field: at most one '-' is allowed in '" + atom + "'");
            }
            if (validValue(index, bounds[0]) && (bounds.length == 1 || validValue(index, bounds[1]))) {
                if (bounds.length == 2 && Integer.parseInt(bounds[0]) > Integer.parseInt(bounds[1])) {
                    throw new IllegalArgumentException("Cron '" + name
                            + "' field: range start must not exceed its end in '" + atom + "'");
                }
                continue;
            }
            // Month/day-of-week accept three-letter names as single values or list members.
            if (bounds.length == 1 && isName(index, bounds[0])) continue;
            // Spring 7 parses names but not name ranges — intercept with an actionable hint.
            if (bounds.length == 2 && isName(index, bounds[0]) && isName(index, bounds[1])) {
                throw new IllegalArgumentException("Cron '" + name
                        + "' field: name ranges are not supported; use a list like MON,WED,FRI");
            }
            String expected = FIELD_MIN[index] + "-" + FIELD_MAX[index]
                    + ((index == 3 || index == 4) ? ", names" : "") + ", or */n";
            throw new IllegalArgumentException("Cron '" + name + "' field: '" + range
                    + "' is not a valid " + name + " value (expected " + expected + ")");
        }
    }

    private static boolean validStep(String raw) {
        if (!raw.matches("\\d+") || raw.length() > 3) return false;
        return Integer.parseInt(raw) >= 1;
    }

    private static boolean validValue(int index, String raw) {
        if (!raw.matches("\\d+") || raw.length() > 2) return false;
        int value = Integer.parseInt(raw);
        return value >= FIELD_MIN[index] && value <= FIELD_MAX[index];
    }

    private static boolean isName(int index, String raw) {
        if (raw.length() != 3) return false;
        String upper = raw.toUpperCase(Locale.ROOT);
        return index == 3 ? MONTH_NAMES.contains(upper)
                : index == 4 && DAY_NAMES.contains(upper);
    }
}
