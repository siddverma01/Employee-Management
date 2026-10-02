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
 * The week-off schedule an employee was rostered to for one attendance period.
 *
 * <p>Week off is not a permanent employee attribute: the source workbooks rotate
 * it, so an engineer can be {@code Sat-Sun} in one month and {@code Sun-Mon} in
 * the next. Storing it only on the employee master made the first imported month
 * stick for every later month, so it is kept here per employee and period
 * instead, mirroring {@link AttendanceShiftAssignment}.
 *
 * <p>{@code weekOffValue} keeps the exact source spelling for display;
 * {@code weekOffKey} is a canonical form used only to group and compare the
 * cosmetic variants the workbooks use.
 */
@Entity
@Table(name = "attendance_week_off_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_week_off_assignment_employee_period",
                columnNames = {"employee_id", "period_start"}),
        indexes = {
                @Index(name = "idx_week_off_assignments_period", columnList = "period_start"),
                @Index(name = "idx_week_off_assignments_employee", columnList = "employee_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceWeekOffAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, length = 40)
    private String employeeId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "week_off_value", length = 60)
    private String weekOffValue;

    @Column(name = "week_off_key", length = 60)
    private String weekOffKey;

    @Column(name = "import_id")
    private Long importId;

    @Column(name = "source_sheet", length = 200)
    private String sourceSheet;

    @Column(name = "source_row")
    private Integer sourceRow;

    @Column(name = "source_file", length = 255)
    private String sourceFile;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}