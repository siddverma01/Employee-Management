package com.emplmgt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One normalised attendance record per employee per day.
 * Traceability back to the source workbook is kept on every row:
 * source_file / source_sheet / source_row.
 *
 * status_code is a free VARCHAR so unknown / unstandardised codes are preserved
 * (is_unknown=true) instead of being silently dropped or forced into an enum.
 *
 * Unique key: employee_id (code) + attendance_date. Re-importing the same
 * workbook UPDATES existing rows instead of duplicating.
 */
@Entity
@Table(name = "attendance_records",
        uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "attendance_date"}),
        indexes = {
                @Index(name = "idx_attendance_records_date", columnList = "attendance_date"),
                @Index(name = "idx_attendance_records_employee", columnList = "employee_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, length = 40)
    private String employeeId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "status_code", nullable = false, length = 30)
    private String statusCode;

    @Column(name = "status_name", length = 120)
    private String statusName;

    @Column(length = 60)
    private String shift;

    @Column(length = 150)
    private String location;

    @Column(name = "source_sheet", length = 200)
    private String sourceSheet;

    @Column(name = "source_row")
    private Integer sourceRow;

    @Column(name = "source_file", length = 500)
    private String sourceFile;

    @Column(name = "is_unknown", nullable = false)
    @Builder.Default
    private Boolean isUnknown = Boolean.FALSE;

    @Column(name = "imported_at", nullable = false)
    @Builder.Default
    private Instant importedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}