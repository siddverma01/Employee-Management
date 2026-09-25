package com.emplmgt.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the normalized {@code "HH:mm-HH:mm"} shift values stored on
 * {@code import_employees}. Only the shift start time is used for ordering;
 * the end time just makes the dance a valid shift (midnight/overnight end
 * times like {@code "19:00-04:00"} are independent of the start's AM/PM).
 */
public final class ShiftTime {

    private static final Pattern SHIFT_PATTERN = Pattern.compile(
            "^\\s*(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\s*$");

    private ShiftTime() {
    }

    /** Minutes since midnight of the shift start, or {@code null} when the shift is unknown/invalid. */
    public static Integer parseShiftStartTime(String shift) {
        if (shift == null) {
            return null;
        }
        Matcher m = SHIFT_PATTERN.matcher(shift);
        if (!m.matches()) {
            return null;
        }
        int start = timeComponent(m.group(1), m.group(2));
        if (start < 0 || timeComponent(m.group(3), m.group(4)) < 0) {
            return null;
        }
        return start;
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