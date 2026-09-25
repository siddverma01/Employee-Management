package com.emplmgt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "excel_import_rows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExcelImportRow extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "excel_import_id", nullable = false)
    private ExcelImport excelImport;

    @Column(name = "row_number", nullable = false)
    private Integer rowNumber;

    @Column(name = "raw_data", columnDefinition = "TEXT")
    private String rawData;

    @Column(name = "mapped_data", columnDefinition = "TEXT")
    private String mappedData;

    @Column(name = "validation_errors", columnDefinition = "TEXT")
    private String validationErrors;

    @Column(name = "error_details", columnDefinition = "TEXT")
    private String errorDetails;

    @Enumerated(EnumType.STRING)
    @Column(name = "row_status", nullable = false, length = 20)
    @Builder.Default
    private ExcelRowStatus rowStatus = ExcelRowStatus.VALID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "attendance_date")
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_type", length = 30)
    private AttendanceType attendanceType;
}