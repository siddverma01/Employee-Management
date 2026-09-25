package com.emplmgt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public final class RosterDtos {

    private RosterDtos() {
    }

    public enum SaveMode { IMPORT, MERGE }

    /** A single row returned to the UI (import preview or saved roster). */
    public record RowView(
            Long id,
            Long teamId,
            String teamName,
            String month,
            String employeeCode,
            String email,
            String employeeName,
            String location,
            String shift,
            String weekOff,
            Map<String, String> days,
            String status,
            List<String> errors) {
    }

    /** Response of GET roster (a full month grid for a team or across all teams). */
    public record MonthResponse(
            Long teamId,
            String teamName,
            String month,
            List<RowView> rows,
            List<String> usedStatuses) {
    }

    /** Parsed + validated preview of an uploaded roster file. */
    public record ImportPreviewResponse(
            Long teamId,
            String teamName,
            String month,
            String fileName,
            int totalRows,
            int newRows,
            int existingRows,
            int invalidRows,
            List<RowView> rows,
            List<String> usedStatuses,
            List<String> warnings) {
    }

    /** One row to persist (import or merge). */
    public record RowItem(
            Long id,
            @NotBlank String employeeCode,
            String email,
            @NotBlank String employeeName,
            String location,
            String shift,
            String weekOff,
            @NotNull Map<String, String> days) {
    }

    /** Save payload — merge/insert rows into the team-month roster. */
    public record SaveRequest(
            @NotNull Long teamId,
            @NotBlank String month,
            @NotNull SaveMode mode,
            @NotEmpty List<RowItem> rows) {
    }

    public record SaveResponse(
            Long teamId,
            String month,
            int imported,
            int updated,
            int skipped,
            List<String> errors) {
    }
}