package fan.summer.fengyu.ai.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** Wall-clock recurrence, independent of elapsed seconds and daylight-saving changes. */
public record CalendarSchedule(String frequency, String time, String zoneId,
                               List<Integer> weekdays, Integer monthDay) {
    public CalendarSchedule {
        if (!List.of("DAILY", "WEEKLY", "MONTHLY").contains(frequency == null ? "" : frequency)) {
            throw new IllegalArgumentException("Unsupported calendar frequency");
        }
        try {
            ZoneId.of(zoneId);
            if (time == null || !time.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException();
            LocalTime.parse(time);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("A valid time (HH:mm) and time zone are required", invalid);
        }
        weekdays = weekdays == null ? List.of() : List.copyOf(weekdays);
        if ("WEEKLY".equals(frequency) && (weekdays.isEmpty()
                || weekdays.stream().anyMatch(day -> day < 1 || day > 7))) {
            throw new IllegalArgumentException("Select weekdays from 1 (Monday) to 7 (Sunday)");
        }
        if ("MONTHLY".equals(frequency)
                && (monthDay == null || (monthDay != -1 && (monthDay < 1 || monthDay > 31)))) {
            throw new IllegalArgumentException("Select a month day from 1 to 31, or -1 for the last day");
        }
    }

    /** Short months use their last day. DST gaps shift forward; overlaps fire only once. */
    public Instant nextAfter(Instant after) {
        ZoneId zone = ZoneId.of(zoneId);
        LocalTime clockTime = LocalTime.parse(time);
        LocalDate date = after.atZone(zone).toLocalDate();
        for (int i = 0; i < 370; i++, date = date.plusDays(1)) {
            boolean matches = switch (frequency) {
                case "DAILY" -> true;
                case "WEEKLY" -> weekdays.contains(date.getDayOfWeek().getValue());
                case "MONTHLY" -> date.getDayOfMonth() == (monthDay == -1
                        ? date.lengthOfMonth() : Math.min(monthDay, date.lengthOfMonth()));
                default -> false;
            };
            if (matches) {
                Instant candidate = date.atTime(clockTime).atZone(zone).toInstant();
                if (candidate.isAfter(after)) return candidate;
            }
        }
        throw new IllegalArgumentException("No next calendar occurrence");
    }
}
