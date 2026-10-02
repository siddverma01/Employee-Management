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


    @Test
    void canonicaliseNormalisesTheWorkbooksSpellings() {
        assertThat(ShiftTime.canonicalise("05:30-14:30")).isEqualTo("05:30-14:30");
        assertThat(ShiftTime.canonicalise("21:00 - 06:00")).isEqualTo("21:00-06:00");
        assertThat(ShiftTime.canonicalise("21:00-06:00")).isEqualTo("21:00-06:00");
        assertThat(ShiftTime.canonicalise("  19:00- 04:00 ")).isEqualTo("19:00-04:00");
        assertThat(ShiftTime.canonicalise("9:00-18:00")).isEqualTo("09:00-18:00");
    }

    @Test
    void canonicaliseReturnsNullWhenTheValueIsNotATimeRange() {
        assertThat(ShiftTime.canonicalise(null)).isNull();
        assertThat(ShiftTime.canonicalise("")).isNull();
        assertThat(ShiftTime.canonicalise("General Shift")).isNull();
        assertThat(ShiftTime.canonicalise("24:00-14:30")).isNull();
        assertThat(ShiftTime.canonicalise("05:30")).isNull();
    }

    @Test
    void comparisonKeyCollapsesCosmeticallyDifferentSpellingsOfOneShift() {
        assertThat(ShiftTime.comparisonKey("21:00 - 06:00"))
                .isEqualTo(ShiftTime.comparisonKey("21:00-06:00"));
        assertThat(ShiftTime.comparisonKey("  19:00- 04:00 "))
                .isEqualTo(ShiftTime.comparisonKey("19:00-04:00"));
    }

    @Test
    void comparisonKeyStillDistinguishesGenuinelyDifferentShifts() {
        assertThat(ShiftTime.comparisonKey("05:30-14:30"))
                .isNotEqualTo(ShiftTime.comparisonKey("13:30-22:30"));
    }

    @Test
    void comparisonKeyFallsBackToATokenForNamedShifts() {
        assertThat(ShiftTime.comparisonKey("General Shift")).isEqualTo("generalshift");
        assertThat(ShiftTime.comparisonKey("general shift")).isEqualTo("generalshift");
    }

    @Test
    void comparisonKeyIsNullOnlyForBlankValues() {
        assertThat(ShiftTime.comparisonKey(null)).isNull();
        assertThat(ShiftTime.comparisonKey("")).isNull();
        assertThat(ShiftTime.comparisonKey("  ")).isNull();
    }

    @Test
    void isTimeRangeDistinguishesRealShiftsFromLabels() {
        assertThat(ShiftTime.isTimeRange("21:00 - 06:00")).isTrue();
        assertThat(ShiftTime.isTimeRange("General Shift")).isFalse();
    }
}
