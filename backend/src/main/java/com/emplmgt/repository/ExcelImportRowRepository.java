package com.emplmgt.repository;

import com.emplmgt.entity.ExcelImportRow;
import com.emplmgt.entity.ExcelRowStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExcelImportRowRepository extends JpaRepository<ExcelImportRow, Long> {

    List<ExcelImportRow> findByExcelImportIdOrderByRowNumber(Long importId);

    long countByExcelImportId(Long importId);

    long countByExcelImportIdAndRowStatus(Long importId, ExcelRowStatus status);

    long countByExcelImportIdAndRowStatusIn(Long importId, List<ExcelRowStatus> statuses);

    void deleteByExcelImportId(Long importId);
}