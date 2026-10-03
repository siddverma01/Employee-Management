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
import java.time.LocalDateTime;

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
        INSERT, UPDATE, DUPLICATE, INVALID,
        /** Admin explicitly chose to leave this cell out of the import. */
        SKIPPED
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

    @Column(name = "source_column")
    private Integer sourceColumn;

    /**
     * The value exactly as it appeared in the workbook, kept even after the admin
     * corrects {@link #incomingStatus} so the change stays traceable to its cell.
     */
    @Column(name = "original_status", length = 60)
    private String originalStatus;

    /** Why this cell still needs attention, or {@code null} once it is resolved. */
    @Column(length = 500)
    private String issue;

    @Column(nullable = false)
    @Builder.Default
    private Boolean corrected = Boolean.FALSE;

    @Column(nullable = false)
    @Builder.Default
    private Boolean skipped = Boolean.FALSE;

    /** Description/remark read from the source cell (comment or inline text). */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** EXCEL_COMMENT, EXCEL_LEGACY_COMMENT, EXCEL_CELL_TEXT or IMPORTED_UNPARSED. */
    @Column(name = "description_source", length = 30)
    private String descriptionSource;

    /** Author of the Excel comment, when the description came from a comment. */
    @Column(name = "description_author", length = 255)
    private String descriptionAuthor;

    /** The comment's own timestamp from the workbook, when it recorded one. */
    @Column(name = "description_at")
    private LocalDateTime descriptionAt;
}