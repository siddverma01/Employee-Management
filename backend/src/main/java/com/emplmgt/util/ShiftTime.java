package com.emplmgt.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands shift values as they appear in the source Excel rosters.
 *
 * <p>Monthly workbooks write the same shift in several cosmetically different
 * ways ({@code "21:00 - 06:00"}, {@code "21:00-06:00"}, {@code "9:00-18:00"}),
 * so a raw string comparison splits one real shift into several and breaks the
 * roster's shift filter and banding. {@link #canonicalise(String)} maps those
 * variants onto the single canonical {@code "HH:mm-HH:mm"} form used for
 * comparison, while the original text stays untouched on the assignment record.
 *
 * <p>Only the shift start time is used for ordering; the end time just makes the
 * value a valid shift (midnight/overnight end times like {@code "19:00-04:00"}
 * are independent of the start's AM/PM).
 */
public final class ShiftTime {

    private static final Pattern SHIFT_PATTERN = Pattern.compile(
            "^\\s*(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\s*$");

    private ShiftTime() {
    }

    /** Minutes since midnight of the shift start, or {@code null} when the shift is unknown/invalid. */
    public static Integer parseShiftStartTime(String shift) {
        Matcher m = match(shift);
        if (m == null) {
            return null;
        }
        int start = timeComponent(m.group(1), m.group(2));
        if (start < 0 || timeComponent(m.group(3), m.group(4)) < 0) {
            return null;
        }
        return start;
    }

    /**
     * Canonical comparison form of a shift value: {@code "HH:mm-HH:mm"} with
     * zero-padded hours and no stray whitespace.
     *
     * <p>Returns {@code null} when the value is absent, or when it is not a
     * parsable time range (a named shift such as {@code "General"}). Named
     * shifts are still comparable through {@link #comparisonKey(String)} — they
     * simply cannot be ordered by start time.
     */
    public static String canonicalise(String shift) {
        Matcher m = match(shift);
        if (m == null) {
            return null;
        }
        int startH = parseComponent(m.group(1));
        int startM = parseComponent(m.group(2));
        int endH = parseComponent(m.group(3));
        int endM = parseComponent(m.group(4));
        if (startH < 0 || startM < 0 || endH < 0 || endM < 0
                || startH > 23 || startM > 59 || endH > 23 || endM > 59) {
            return null;
        }
        return String.format("%02d:%02d-%02d:%02d", startH, startM, endH, endM);
    }

    /** {@code true} when the value is a parsable {@code HH:mm-HH:mm} shift. */
    public static boolean isTimeRange(String shift) {
        return canonicalise(shift) != null;
    }

    /**
     * Stable key used to decide whether two recorded shifts are the same shift.
     * Time ranges collapse to their canonical form; any other non-blank label
     * collapses to a case/space-insensitive token so named shifts still filter.
     */
    public static String comparisonKey(String shift) {
        if (shift == null || shift.isBlank()) {
            return null;
        }
        String canonical = canonicalise(shift);
        return canonical != null ? canonical : HistoricalImportCodes.compactCode(shift);
    }

    private static Matcher match(String shift) {
        if (shift == null) {
            return null;
        }
        Matcher m = SHIFT_PATTERN.matcher(shift);
        // group() is only legal once the matcher has run; callers read groups directly.
        return m.matches() ? m : null;
    }

    private static int parseComponent(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int timeComponent(String hour, String minute) {
        try {
            int h = Integer.parseInt(hour);
            int min = Integer.parseInt(minute);
            if (h > 23 || min > 59) {
                return -1;
            }
            return h * 60 + min;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}