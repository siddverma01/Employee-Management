package com.emplmgt.util;

import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.HolidayType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeaveDaysCalculatorTest {

    private LeaveDaysCalculator defaultCalculator;

    @BeforeEach
    void setUp() {
        // default config: exclude weekends (Sat+Sun) and holidays
        defaultCalculator = new LeaveDaysCalculator(true, "6,7", true);
    }

    @Test
    void countsWorkingDaysExcludingWeekends() {
        // Mon 2026-09-14 .. Fri 2026-09-18 => 5 days
        long days = defaultCalculator.countLeaveDays(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 18), List.of());
        assertThat(days).isEqualTo(5);
    }

    @Test
    void returnsZeroWhenRangeIsEntirelyWeekend() {
        long days = defaultCalculator.countLeaveDays(
                LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13), List.of());
        assertThat(days).isZero();
    }

    @Test
    void excludesHolidaysInRange() {
        Holiday h = Holiday.builder()
                .name("Test Holiday")
                .holidayDate(LocalDate.of(2026, 9, 16))
                .holidayType(HolidayType.PUBLIC)
                .build();
        long days = defaultCalculator.countLeaveDays(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 18), List.of(h));
        assertThat(days).isEqualTo(4);
    }

    @Test
    void honorsCustomWeeklyOffs() {
        // Friday + Saturday off
        LeaveDaysCalculator fridaySatOff = new LeaveDaysCalculator(true, "5,6", true);
        // Sun-Mon-Wed-Thu across the week of Sep 14..Sep 20
        long days = fridaySatOff.countLeaveDays(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), List.of());
        // 14 Mon, 15 Tue, 16 Wed, 17 Thu, 18 Fri (off), 19 Sat (off), 20 Sun => 5
        assertThat(days).isEqualTo(5);
    }

    @Test
    void canTurnOffHolidayExclusion() {
        // holidays included as working: still filter weekends only
        LeaveDaysCalculator noHolidayRule = new LeaveDaysCalculator(true, "6,7", false);
        Holiday h = Holiday.builder()
                .name("Test Holiday")
                .holidayDate(LocalDate.of(2026, 9, 16))
                .holidayType(HolidayType.PUBLIC)
                .build();
        long days = noHolidayRule.countLeaveDays(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 18), List.of(h));
        assertThat(days).isEqualTo(5);
    }

    @Test
    void isWeeklyOffDetectsSaturdays() {
        assertThat(defaultCalculator.isWeeklyOff(LocalDate.of(2026, 9, 12))).isTrue();
        assertThat(defaultCalculator.isWeeklyOff(LocalDate.of(2026, 9, 13))).isTrue();
        assertThat(defaultCalculator.isWeeklyOff(LocalDate.of(2026, 9, 14))).isFalse();
    }
}