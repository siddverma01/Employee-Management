package com.emplmgt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "excel_imports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExcelImport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "original_file_name", nullable = false, length = 500)
    private String originalFileName;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    @Builder.Default
    private java.time.Instant uploadedAt = java.time.Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ExcelImportStatus status = ExcelImportStatus.UPLOADED;

    @Column(name = "total_rows", nullable = false)
    @Builder.Default
    private Integer totalRows = 0;

    @Column(name = "valid_rows", nullable = false)
    @Builder.Default
    private Integer validRows = 0;

    @Column(name = "invalid_rows", nullable = false)
    @Builder.Default
    private Integer invalidRows = 0;

    @Column(name = "duplicate_rows", nullable = false)
    @Builder.Default
    private Integer duplicateRows = 0;

    @Column(name = "imported_rows", nullable = false)
    @Builder.Default
    private Integer importedRows = 0;

    @Column(name = "mapping_json", columnDefinition = "TEXT")
    private String mappingJson;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    @Column(name = "committed_at")
    private Instant committedAt;
}