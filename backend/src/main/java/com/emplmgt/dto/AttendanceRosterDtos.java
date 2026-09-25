package com.emplmgt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTOs backing the month-wise Attendance Roster grid admin page. The grid is
 * built from the normalised historical tables ({@code attendance_records} +
 * {@code import_employees}) with server-side month/filter/pagination.
 */
public final class AttendanceRosterDtos {

    private AttendanceRosterDtos() {
    }

    public record DayInfo(String date, int dayNumber, String weekday, boolean weekend,
                          boolean holiday, String holidayName) {
    }

    public record EmployeeRow(String employeeId, String employeeName, String email, String location,
                              String shift, String weekOff, Long teamId, String teamName,
                              Map<String, String> days) {
    }

    public record Counters(Map<String, Long> counts) {
    }

    public record TeamOption(Long id, String name) {
    }

    public record StatusOption(String code, String name, String displayColor) {
    }

    public record MonthlyResponse(String month, Long teamId, String teamName, List<DayInfo> days,
                                  List<EmployeeRow> employees, Map<String, Long> counters,
                                  long totalEmployees, long matchedEmployees,
                                  int page, int size, long totalElements, int totalPages) {
    }

    public record PageMeta(List<TeamOption> teams, List<String> months, List<String> locations,
                           List<String> shifts, List<StatusOption> statuses) {
    }

    public record CellEdit(@NotBlank String employeeId, @NotNull LocalDate date,
                           @NotBlank String statusCode) {
    }

    public record BatchSaveRequest(@NotEmpty List<CellEdit> changes) {
    }

    public record BatchSaveResponse(int saved) {
    }
}