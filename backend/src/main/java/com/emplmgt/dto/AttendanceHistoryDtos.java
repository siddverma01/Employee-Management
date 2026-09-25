package com.emplmgt.dto;

import java.util.List;
import java.util.Map;

/**
 * DTOs backing the admin Attendance History search/analysis page. The page is
 * a server-side paginated view over {@code attendance_records} joined to
 * {@code import_employees} (and its team), so indexing years of history never
 * pushes all rows into the browser. Every {@code RecordView} also carries
 * traceability back to the source workbook/sheet/row.
 */
public final class AttendanceHistoryDtos {

    private AttendanceHistoryDtos() {
    }

    /** Raw filter + sorting controls, built from controller request params. */
    public record HistoryQuery(String dateFrom, String dateTo, String month, String year,
                               String employeeName, String employeeId, Long teamId,
                               String location, String shift, String status, String q,
                               int page, int size, String sortBy, String sortDir) {
    }

    public record RecordView(String date, String employeeId, String employeeName,
                             Long teamId, String teamName, String location, String shift,
                             String statusCode, String statusName, String sourceMonth,
                             String sourceSheet, String sourceFile, Integer sourceRow,
                             String importedAt, boolean unknown) {
    }

    public record Summary(long total, Map<String, Long> byStatus) {
    }

    public record SearchResponse(List<RecordView> records, Summary summary,
                                 int page, int size, long totalElements, int totalPages) {
    }

    /** Reuses {@link AttendanceRosterDtos.TeamOption}/{@code StatusOption}. */
    public record Meta(List<AttendanceRosterDtos.TeamOption> teams, List<String> months,
                       List<String> years, List<String> locations, List<String> shifts,
                       List<AttendanceRosterDtos.StatusOption> statuses) {
    }
}