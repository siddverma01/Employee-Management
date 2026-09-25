package com.emplmgt.util;

import com.emplmgt.entity.Holiday;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business rule engine for leave-day counting.
 *
 * Weekend and holiday exclusions are configurable rather than hardcoded:
 *  - application.leave.exclude-weekends
 *  - application.leave.weekly-offs        (comma separated ISO day numbers, 1=Mon .. 7=Sun)
 *  - application.leave.exclude-holidays
 */
@Component
public class LeaveDaysCalculator {

    private final boolean excludeWeekends;
    private final boolean excludeHolidays;
    private final Set<DayOfWeek> weeklyOffs;

    public LeaveDaysCalculator(@Value("${application.leave.exclude-weekends:true}") boolean excludeWeekends,
                               @Value("${application.leave.weekly-offs:6,7}") String weeklyOffs,
                               @Value("${application.leave.exclude-holidays:true}") boolean excludeHolidays) {
        this.excludeWeekends = excludeWeekends;
        this.excludeHolidays = excludeHolidays;

        if (excludeWeekends && weeklyOffs != null && !weeklyOffs.isBlank()) {
            this.weeklyOffs = java.util.Arrays.stream(weeklyOffs.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .map(DayOfWeek::of)
                    .collect(Collectors.toSet());
        } else {
            this.weeklyOffs = Set.of();
        }
    }

    /**
     * Counts the number of leave days for a range after applying configured exclusions.
     */
    public long countLeaveDays(LocalDate start, LocalDate end, List<Holiday> holidaysInRange) {
        Set<LocalDate> holidayDates = excludeHolidays && holidaysInRange != null
                ? holidaysInRange.stream().map(Holiday::getHolidayDate).collect(Collectors.toSet())
                : Set.of();

        long days = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (excludeWeekends && weeklyOffs.contains(date.getDayOfWeek())) {
                continue;
            }
            if (excludeHolidays && holidayDates.contains(date)) {
                continue;
            }
            days++;
        }
        return days;
    }

    public boolean isWeeklyOff(LocalDate date) {
        return weeklyOffs.contains(date.getDayOfWeek());
    }

    public Set<DayOfWeek> weeklyOffs() {
        return weeklyOffs;
    }
}