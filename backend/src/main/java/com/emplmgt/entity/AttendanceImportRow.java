package com.emplmgt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Staged preview row shown to the admin before a historical import is
 * committed: Sheet | Employee | Date | Existing Status | Imported Status |
 * Action | Warning.
 */
@Entity
@Table(name = "attendance_import_rows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceImportRow {

    public enum RowAction {
        INSERT, UPDATE, DUPLICATE, INVALID
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_id", nullable = false)
    private AttendanceImportHistory importHistory;

    @Column(name = "sheet_name", length = 200)
    private String sheetName;

    @Column(name = "source_row")
    private Integer sourceRow;

    @Column(name = "employee_id", length = 40)
    private String employeeId;

    @Column(name = "employee_name", length = 200)
    private String employeeName;

    @Column(name = "employee_email", length = 255)
    private String employeeEmail;

    @Column(name = "employee_location", length = 150)
    private String employeeLocation;

    @Column(name = "employee_manager", length = 150)
    private String employeeManager;

    @Column(name = "employee_shift", length = 60)
    private String employeeShift;

    @Column(name = "employee_week_off", length = 60)
    private String employeeWeekOff;

    @Column(name = "attendance_date")
    private LocalDate attendanceDate;

    @Column(name = "existing_status", length = 30)
    private String existingStatus;

    @Column(name = "incoming_status", length = 30)
    private String incomingStatus;

    @Column(name = "status_name", length = 120)
    private String statusName;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String action = "INSERT";

    @Column(length = 500)
    private String warning;

    @Column(name = "is_unknown", nullable = false)
    @Builder.Default
    private Boolean isUnknown = Boolean.FALSE;
}