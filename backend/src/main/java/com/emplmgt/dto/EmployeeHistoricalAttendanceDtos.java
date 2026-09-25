package com.emplmgt.dto;

import java.util.List;
import java.util.Map;

/**
 * DTOs backing the historical-attendance sections of the admin Employee
 * Profile. Everything is derived from the normalised tables
 * ({@code attendance_records} joined to {@code import_employees}) — never from
 * raw workbook parsing in the UI. Counts are aggregated per status code and
 * cover the full period this employee appears in the historical data.
 */
public final class EmployeeHistoricalAttendanceDtos {

    private EmployeeHistoricalAttendanceDtos() {
    }

    /** All-time overview: first recorded date, total days and per-status totals. */
    public record Overview(String dateJoined, long totalDays, Map<String, Long> byStatus) {
    }

    /** One month row of the Monthly Attendance breakdown (counts include "OTHER"). */
    public record MonthStat(String month, Map<String, Long> counts) {
    }

    /** One calendar day; {@code statusCode} is null when the day has no record. */
    public record CalendarDay(String date, String statusCode, String statusName,
                              boolean unknown, boolean weekend) {
    }

    /** Month calendar: every day of the month with its attendance status. */
    public record Calendar(String month, List<CalendarDay> days) {
    }

    /**
     * {@code found=false} (with null aggregates) when the employee has no
     * historical attendance at all — the UI shows an empty state instead of
     * a grid of zeros.
     */
    public record ProfileResponse(boolean found, String employeeId, String employeeName,
                                  Overview overview, List<String> months, List<MonthStat> monthly,
                                  Calendar calendar) {
    }
}