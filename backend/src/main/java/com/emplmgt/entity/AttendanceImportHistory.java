package com.emplmgt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per import run (upload → preview → commit). Tracks the parse
 * summary so the admin can review "Total sheets scanned / Employees detected /
 * New records / Updated records / Duplicate records / Unknown codes / ...".
 */
@Entity
@Table(name = "attendance_import_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceImportHistory {

    public enum ImportStatus {
        DRAFT, PREVIEWED, COMMITTED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "original_file_name", length = 500)
    private String originalFileName;

    @Column(name = "imported_at", nullable = false)
    @Builder.Default
    private Instant importedAt = Instant.now();

    @Column(name = "imported_by", length = 150)
    private String importedBy;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "total_sheets", nullable = false)
    @Builder.Default
    private Integer totalSheets = 0;

    @Column(name = "sheets_imported", nullable = false)
    @Builder.Default
    private Integer sheetsImported = 0;

    @Column(name = "sheets_skipped", nullable = false)
    @Builder.Default
    private Integer sheetsSkipped = 0;

    @Column(name = "employees_detected", nullable = false)
    @Builder.Default
    private Integer employeesDetected = 0;

    @Column(name = "records_detected", nullable = false)
    @Builder.Default
    private Integer recordsDetected = 0;

    @Column(name = "inserted_records", nullable = false)
    @Builder.Default
    private Integer insertedRecords = 0;

    @Column(name = "updated_records", nullable = false)
    @Builder.Default
    private Integer updatedRecords = 0;

    @Column(name = "duplicate_records", nullable = false)
    @Builder.Default
    private Integer duplicateRecords = 0;

    @Column(name = "unknown_codes", nullable = false)
    @Builder.Default
    private Integer unknownCodes = 0;

    @Column(name = "invalid_rows", nullable = false)
    @Builder.Default
    private Integer invalidRows = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer warnings = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer errors = 0;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;
}