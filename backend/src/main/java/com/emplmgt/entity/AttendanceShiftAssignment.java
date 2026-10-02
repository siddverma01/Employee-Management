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
 * The shift an employee was rostered to for one attendance period (a month).
 *
 * <p>Shift is a property of the <em>rostering period</em>, not of the person:
 * the source workbooks rotate engineers between shifts from month to month, so
 * a single value on the employee master cannot describe a year of attendance.
 * Each monthly sheet therefore gets its own row here, and the Attendance Roster
 * reads the row matching the month the user selected.
 *
 * <p>{@code shiftValue} keeps the Excel text verbatim (including cosmetic
 * differences such as {@code "21:00 - 06:00"}) for display and audit, while
 * {@code shiftKey} holds the canonical form used to compare, group and filter.
 *
 * <p>Unique key: employee_id + period_start. Re-importing a workbook updates the
 * existing assignment for that month instead of creating a duplicate or
 * conflicting row, and importing a different month never touches this one.
 */
@Entity
@Table(name = "attendance_shift_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_shift_assignment_employee_period",
                columnNames = {"employee_id", "period_start"}),
        indexes = {
                @Index(name = "idx_shift_assignments_period", columnList = "period_start"),
                @Index(name = "idx_shift_assignments_employee", columnList = "employee_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceShiftAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, length = 40)
    private String employeeId;

    /** First day of the attendance period, so it joins directly to record date ranges. */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /** Shift text exactly as the source workbook wrote it. */
    @Column(name = "shift_value", length = 60)
    private String shiftValue;

    /** Canonical comparison form of {@link #shiftValue}; see {@code ShiftTime.comparisonKey}. */
    @Column(name = "shift_key", length = 60)
    private String shiftKey;

    @Column(name = "import_id")
    private Long importId;

    @Column(name = "source_sheet", length = 200)
    private String sourceSheet;

    @Column(name = "source_row")
    private Integer sourceRow;

    @Column(name = "source_file", length = 500)
    private String sourceFile;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}