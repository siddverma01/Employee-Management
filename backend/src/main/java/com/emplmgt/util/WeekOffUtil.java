package com.emplmgt.util;

import com.emplmgt.entity.Employee;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves an employee's weekly-off schedule.
 *
 * An employee's {@code week_off} value is a free-text schedule, e.g. "Sat-Sun",
 * "Sun-Mon", "Fri", or "Sat, Sun". When no value is configured the globally
 * configured weekly offs from {@link LeaveDaysCalculator} are used.
 */
@Component
public class WeekOffUtil {

    private final LeaveDaysCalculator leaveDaysCalculator;

    public WeekOffUtil(LeaveDaysCalculator leaveDaysCalculator) {
        this.leaveDaysCalculator = leaveDaysCalculator;
    }

    public boolean isWeekOff(Employee employee, LocalDate date) {
        if (employee == null || date == null) {
            return false;
        }
        return resolve(employee).contains(date.getDayOfWeek());
    }

    public Set<DayOfWeek> resolve(Employee employee) {
        if (employee == null || employee.getWeekOff() == null || employee.getWeekOff().isBlank()) {
            return leaveDaysCalculator.weeklyOffs();
        }
        Set<DayOfWeek> offs = parse(employee.getWeekOff());
        return offs.isEmpty() ? leaveDaysCalculator.weeklyOffs() : offs;
    }

    public Set<DayOfWeek> parse(String raw) {
        Set<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String[] groups = raw.split(",");
        for (String group : groups) {
            if (group == null || group.isBlank()) {
                continue;
            }
            String[] bounds = group.trim().split("-");
            DayOfWeek start = parseDay(bounds[0]);
            if (start == null) {
                continue;
            }
            DayOfWeek end = bounds.length > 1 ? parseDay(bounds[1]) : start;
            if (end == null) {
                continue;
            }
            DayOfWeek cursor = start;
            result.add(cursor);
            while (!cursor.equals(end)) {
                cursor = cursor.plus(1);
                result.add(cursor);
            }
        }
        return result;
    }

    private DayOfWeek parseDay(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // fall through to short-name lookup
        }
        String upper = value.toUpperCase(Locale.ROOT);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().substring(0, 3).equals(upper.substring(0, Math.min(3, upper.length())))) {
                return day;
            }
        }
        return null;
    }
}