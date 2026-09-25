package com.emplmgt.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShiftTimeTest {

    @Test
    void parseShiftStartTimeReturnsMinutesSinceMidnight() {
        assertThat(ShiftTime.parseShiftStartTime("05:30-14:30")).isEqualTo(330);
        assertThat(ShiftTime.parseShiftStartTime("13:30-22:30")).isEqualTo(810);
        assertThat(ShiftTime.parseShiftStartTime("17:00-02:00")).isEqualTo(1020);
        assertThat(ShiftTime.parseShiftStartTime("19:00-04:00")).isEqualTo(1140);
        assertThat(ShiftTime.parseShiftStartTime("21:00-06:00")).isEqualTo(1260);
        assertThat(ShiftTime.parseShiftStartTime("08:00-17:00")).isEqualTo(480);
        assertThat(ShiftTime.parseShiftStartTime("00:00-08:00")).isEqualTo(0);
        assertThat(ShiftTime.parseShiftStartTime("12:00-21:00")).isEqualTo(720);
    }

    @Test
    void parseShiftStartTimeIgnoresSurroundingWhitespace() {
        assertThat(ShiftTime.parseShiftStartTime("  17:00 - 02:00  ")).isEqualTo(1020);
    }

    @Test
    void parseShiftStartTimeReturnsNullForUnknownOrInvalid() {
        assertThat(ShiftTime.parseShiftStartTime(null)).isNull();
        assertThat(ShiftTime.parseShiftStartTime("")).isNull();
        assertThat(ShiftTime.parseShiftStartTime("   ")).isNull();
        assertThat(ShiftTime.parseShiftStartTime("Unknown Band")).isNull();
        assertThat(ShiftTime.parseShiftStartTime("Day Shift")).isNull();
        assertThat(ShiftTime.parseShiftStartTime("05:30")).isNull();
        assertThat(ShiftTime.parseShiftStartTime("05:30-14:30-18:00")).isNull();
        assertThat(ShiftTime.parseShiftStartTime("24:00-14:30")).isNull();
        assertThat(ShiftTime.parseShiftStartTime("05:30-25:00")).isNull();
        assertThat(ShiftTime.parseShiftStartTime("05:99-14:30")).isNull();
    }
}