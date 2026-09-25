package com.emplmgt.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class HistoricalImportDtos {

    private HistoricalImportDtos() {
    }

    // ------------------------------------------------------------ inspection

    public record InspectResponse(String fileName, long size, int sheetCount, List<String> sheets) {
    }

    // ------------------------------------------------------------ analysis

    public record SheetAnalysis(
            String sheetName,
            String month,
            Integer headerRow,
            int employeeColumns,
            int dateColumns,
            int employeeCount,
            int cellCount,
            int unknownCodeCount,
            int emptyCellCount,
            boolean skipped,
            String skipReason,
            boolean importable,
            List<String> warnings) {
    }

    public record ValidationIssue(
            String severity,
            String type,
            String message,
            String sheetName,
            Integer row,
            String employeeId,
            long count) {
    }

    public record UnknownCodeDetail(
            String code,
            String example,
            long count,
            String suggested) {
    }

    /**
     * Temporary diagnostic: unknown statuses grouped by the exact cell value,
     * with sample locations so the admin can see precisely which cells are
     * flagged unknown before deciding on aliases/remapping.
     */
    public record UnknownValueDetail(
            String rawValue,
            String normalizedValue,
            long count,
            List<UnknownValueSample> examples) {
    }

    public record UnknownValueSample(
            String sheetName,
            Integer rowNumber,
            Integer columnNumber,
            String employeeId,
            String employeeName,
            LocalDate date) {
    }

    public record Summary(
            int totalSheets,
            int sheetsImported,
            int sheetsSkipped,
            int employeesDetected,
            int recordsDetected,
            int newRecords,
            int updatedRecords,
            int duplicateRecords,
            int unknownCodes,
            int invalidRows,
            int warnings,
            int errors,
            int importableRows,
            int failedRows,
            List<SheetAnalysis> sheetDetails) {
    }

    public record RowView(
            long id,
            String sheetName,
            int sourceRow,
            String employeeId,
            String employeeName,
            LocalDate attendanceDate,
            String existingStatus,
            String incomingStatus,
            String statusName,
            String action,
            String warning,
            boolean unknown,
            String location,
            String shift) {
    }

    public record PreviewResponse(
            long importId,
            String fileName,
            String originalFileName,
            String status,
            Summary summary,
            long totalRows,
            int page,
            int size,
            List<RowView> rows,
            List<SheetAnalysis> analysis,
            List<ValidationIssue> issues,
            List<UnknownCodeDetail> unknownCodes,
            List<UnknownValueDetail> unknownValues) {
    }

    public record CommitResponse(
            long importId,
            String fileName,
            String originalFileName,
            String status,
            Summary summary,
            int committedRows,
            ImportResult result) {
    }

    public record ImportResult(
            int employees,
            int records,
            int inserted,
            int updated,
            int duplicatesSkipped,
            int warnings,
            int unknownStatuses,
            int failedRows) {
    }

    public record HistoryItem(
            long id,
            String fileName,
            String originalFileName,
            Instant importedAt,
            String importedBy,
            String status,
            int totalSheets,
            int sheetsImported,
            int sheetsSkipped,
            int employeesDetected,
            int recordsDetected,
            int newRecords,
            int updatedRecords,
            int duplicateRecords,
            int unknownCodes,
            int invalidRows,
            int warnings,
            int errors,
            int failedRows) {
    }

    // ------------------------------------------------- global unknown-code ops

    public record UnknownCodeItem(String code, long count) {
    }

    public record MapUnknownRequest(
            @NotBlank String from,
            @NotBlank String to) {
    }

    public record MapUnknownResponse(long mapped, String to, String toName) {
    }

    // --------------------------------------------- per-import staged mapping

    /** {@code to} may be null / empty / "KEEP" to leave the code as unknown. */
    public record MapStagedRequest(
            @NotBlank String from,
            String to) {
    }

    public record MapStagedResponse(long mapped, String from, String to, String toName) {
    }

    public record StatusItem(String code, String name, String description, String displayColor) {
    }

    public record RecordsPage(List<RecordView> records, long total, int page, int size) {
    }

    public record RecordView(
            long id,
            String employeeId,
            String employeeName,
            LocalDate attendanceDate,
            String statusCode,
            String statusName,
            boolean unknown,
            String sourceSheet,
            Integer sourceRow,
            String sourceFile,
            Instant importedAt) {
    }
}