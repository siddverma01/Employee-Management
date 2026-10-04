package com.emplmgt.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * DTOs for the Employee Attendance Analytics page.
 * Aggregates both historical (Excel-imported) and website-maintained attendance.
 */
public final class AttendanceAnalyticsDtos {

    private AttendanceAnalyticsDtos() {
    }

    public enum ReportMode {
        MONTHLY,
        OVERALL
    }

    /**
     * Filter scope for the metadata call.
     *
     * <p>Locations depend on the selected roster month and team, so the dropdown offers
     * exactly the locations that can match a row of the employee list.</p>
     */
    public record MetaQuery(
            @Min(1) @Max(12) Integer month,
            @Min(2020) @Max(2099) Integer year,
            Long teamId
    ) {
    }

    public record AnalyticsQuery(
            @Min(1) @Max(12) Integer month,
            @Min(2020) @Max(2099) Integer year,
            ReportMode mode,
            Long teamId,
            String location,
            String employeeId,
            String employeeName,
            String status,
            Integer page,
            Integer size,
            String sortBy,
            String sortDir
    ) {
    }

    public record EmployeeSummary(
            Long sNo,
            String employeeId,
            String employeeName,
            String location,
            String shift,
            String weekOff,
            /**
             * Whether this employee is on the selected month's roster.
             *
             * <p>False marks a row that only a search surfaced: a former employee or one
             * present solely in historical imports. The UI labels these so a historical
             * result is never mistaken for a member of the current roster.</p>
             */
            @JsonProperty("inCurrentRoster") boolean inCurrentRoster,
            String employmentStatus,
            long wfo,
            long wfh,
            long wo,
            long pl,
            long co,
            long sl,
            long hd,
            long wkWrk,
            double shrinkage,
            String atr,
            long totalWorkingDays,
            long totalLeaves,
            double attendancePercentage,
            long attendanceRecorded
    ) {
    }

    public record EmployeeDetail(
            String employeeId,
            String employeeEmail,
            String employeeName,
            String teamName,
            String location,
            String shift,
            String weekOff,
            LocalDate joiningDate,
            String employmentStatus,
            long totalWorkingDays,
            long wfo,
            long wfh,
            long wo,
            long pl,
            long co,
            long sl,
            long hd,
            long wkWrk,
            double shrinkage,
            String atr,
            long totalLeaves,
            double attendancePercentage,
            long attendanceRecorded,
            List<MonthStat> monthlyBreakdown,
            List<AttendanceDayView> attendanceHistory
    ) {
    }

    public record MonthStat(
            String month,
            long workingDays,
            long wfo,
            long wfh,
            long wo,
            long pl,
            long co,
            long sl,
            long hd,
            long wkWrk,
            double shrinkage,
            long totalLeaves
    ) {
    }

    public record AttendanceDayView(
            String date,
            String day,
            String status,
            String description,
            String originalAuthor,
            String lastUpdated
    ) {
    }

    public record TeamOption(Long id, String name) {
    }

    public record LocationOption(String value) {
    }

    public record StatusOption(String code, String name) {
    }

    public record Meta(
            List<TeamOption> teams,
            List<String> months,
            List<String> years,
            List<LocationOption> locations,
            List<StatusOption> statuses
    ) {
    }

    public record EmployeeStatsResponse(
            List<EmployeeSummary> employees,
            EmployeeSummary overallSummary,
            long totalElements,
            int page,
            int size,
            int totalPages
    ) {
    }

    public record EmployeeDetailResponse(EmployeeDetail detail) {
    }

    public record MetaResponse(Meta meta) {
    }

    public record ExportRequest(
            @NotNull ReportMode mode,
            Integer month,
            Integer year,
            Long teamId,
            String location,
            String employeeId,
            String status
    ) {
    }
}