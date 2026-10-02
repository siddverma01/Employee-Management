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

    /** Trailing schedule notes such as the "(Sat 9-6)" in "Sun-mon(Sat 9-6)". */
    private static final java.util.regex.Pattern NOTE = java.util.regex.Pattern.compile("\\([^)]*\\)");

    /**
     * Full and informal day names folded to the three-letter abbreviation used by
     * {@link DayOfWeek}. Longer names must be listed first so "TUESDAY" is not left
     * half-replaced by the "TUES" rule. Migration V24 applies the identical list in
     * SQL; if one side changes the other must change with it.
     */
    private static final String[][] DAY_ALIASES = {
            {"MONDAY", "MON"}, {"TUESDAY", "TUE"}, {"WEDNESDAY", "WED"}, {"THURSDAY", "THU"},
            {"FRIDAY", "FRI"}, {"SATURDAY", "SAT"}, {"SUNDAY", "SUN"},
            {"TUES", "TUE"}, {"THURS", "THU"}, {"WEDS", "WED"},
    };

    /**
     * A canonical key must consist only of weekday tokens joined by single
     * separators. Mirrors the regex in migration V24 so a value accepted by the
     * backfill is exactly a value the importer will accept.
     */
    private static final java.util.regex.Pattern RECOGNISED =
            java.util.regex.Pattern.compile("^(MON|TUE|WED|THU|FRI|SAT|SUN)(-(MON|TUE|WED|THU|FRI|SAT|SUN))*$");

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

    /**
     * Canonical form of a week-off value, used only to decide whether two
     * spellings are the same schedule.
     *
     * <p>The workbooks vary purely cosmetically between sheets - "Sat-Sun" and
     * "sat-sun", "Mon-Tues" and "Mon-Tues (9-6)" - and they also mix full and
     * short day names, so "Monday", "MON" and "mon" have to fold together while
     * genuinely different schedules such as "Sat-Sun" and "Sun-Mon" stay distinct.
     *
     * <p>The result is deliberately a string rather than a sorted day set:
     * {@link #parse} reads "TUE-MON" as a range wrapping through the week, which is
     * not the same schedule as "MON-TUE", so reordering the tokens would lose a real
     * distinction.
     *
     * <p>The original spelling is always what gets stored and displayed; this key
     * never replaces it.
     *
     * @return the canonical key, or {@code null} when nothing usable remains
     */
    public static String comparisonKey(String raw) {
        if (raw == null) {
            return null;
        }
        String value = NOTE.matcher(raw).replaceAll(" ")   // drop "(Sat 9-6)"-style notes
                .replace('\u00A0', ' ')
                .replaceAll("[\\s,/_&]+", "-")            // "Mon , Tues" -> "Mon-Tues"
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "")
                .toUpperCase(Locale.ROOT);
        for (String[] alias : DAY_ALIASES) {
            value = value.replace(alias[0], alias[1]);
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * True when the value names at least one real weekday.
     *
     * <p>Non-roster sheets contribute placeholder text in this column - "WeekOff",
     * a manager's name, "NA VOICE ROSTER FOR October-26" - which must not be
     * mistaken for a schedule, so those are reported for review rather than
     * stored as an assignment.
     *
     * <p>This checks the canonical key with the same token grammar migration V24
     * uses, rather than {@link #parse}. {@code parse} falls back to a three-letter
     * prefix, which would quietly accept nonsense that merely starts with a real
     * day name.
     */
    public boolean isRecognised(String raw) {
        String key = comparisonKey(raw);
        return key != null && RECOGNISED.matcher(key).matches();
    }
}