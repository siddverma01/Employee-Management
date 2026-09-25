package com.emplmgt.dto;

import com.emplmgt.entity.ExcelImportStatus;
import com.emplmgt.entity.ExcelRowStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ImportDtos {

    private ImportDtos() {
    }

    public record UploadResponse(
            Long importId,
            String fileName,
            String originalFileName,
            ExcelImportStatus status,
            List<String> headers,
            int totalRows,
            Map<String, String> suggestedMapping) {
    }

    /**
     * Maps a source column header to a target field key (e.g. "Emp No" -> "employeeCode").
     */
    public record MappingRequest(
            @NotNull Long importId,
            @NotEmpty Map<String, Object> mapping) {
    }

    public record PreviewResponse(
            Long importId,
            ExcelImportStatus status,
            List<String> headers,
            Map<String, Object> mapping,
            int totalRows,
            int validRows,
            int invalidRows,
            int duplicateRows,
            List<RowView> rows) {
    }

    public record RowView(
            int rowNumber,
            Map<String, Object> data,
            ExcelRowStatus status,
            List<String> errors,
            List<ImportError> details) {
    }

    /**
     * One structured validation failure for a spreadsheet cell, so the UI can show
     * exactly which sheet/row/column/raw value was rejected and why.
     */
    public record ImportError(
            String sheet,
            Integer row,
            String column,
            String employeeId,
            String employeeName,
            String date,
            String rawValue,
            String errorType,
            String message) {
    }

    public record CommitResponse(
            Long importId,
            ExcelImportStatus status,
            int totalRows,
            int validRows,
            int invalidRows,
            int duplicateRows,
            int importedRows) {
    }

    public record HistoryItem(
            Long id,
            String fileName,
            String originalFileName,
            String uploadedBy,
            Instant uploadedAt,
            ExcelImportStatus status,
            int totalRows,
            int validRows,
            int invalidRows,
            int duplicateRows,
            int importedRows,
            Instant committedAt) {
    }
}