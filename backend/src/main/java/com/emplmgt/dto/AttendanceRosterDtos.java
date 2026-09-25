package com.emplmgt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
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

    public record TodayResponse(LocalDate date, Long teamId, String teamName, String weekday,
                                boolean weekend, boolean holiday, String holidayName,
                                List<EmployeeRow> employees, Map<String, Long> counters,
                                long totalEmployees, long matchedEmployees) {
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

    /** Read model shown in the status description popup for any authenticated
     *  user. Names/dates are null when no description has been written yet.
     *
     *  <p>When the roster status comes from an approved Leave / Swap Off
     *  request, {@code sourceRequestType}/{@code sourceRequestId} are set and
     *  {@code sourceReason}, {@code submittedByName}, {@code approvedByName}
     *  and {@code approvedAt} carry the original request's details (resolved
     *  server-side from the linked request — never from the client). The
     *  free-text {@code description} is the admin's own manual note and stays
     *  untouched by approval writes.</p>
     *
     *  <p>For Swap Off, additional fields {@code workedForName} and
     *  {@code workedDate} identify the employee who was worked for and the
     *  date the requester worked, enabling the full relationship display.</p> */
    public record StatusDetail(String employeeId, LocalDate date, String statusCode, String statusName,
                               String description, String createdByName, Instant createdAt,
                               String updatedByName, Instant updatedAt,
                               Long sourceRequestId, String sourceRequestType,
                               String sourceReason, String submittedByName,
                               String approvedByName, Instant approvedAt,
                               String workedForName, LocalDate workedDate) {
    }

    /** Admin-only write: upsert (or clear when description is blank) the free-
     *  text reason for an employee + date record. */
    public record DescriptionUpsertRequest(@NotBlank String employeeId, @NotNull LocalDate date,
                                           String description) {
    }
}