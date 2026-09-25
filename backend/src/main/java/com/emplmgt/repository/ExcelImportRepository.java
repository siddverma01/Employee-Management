package com.emplmgt.repository;

import com.emplmgt.entity.ExcelImport;
import com.emplmgt.entity.ExcelImportRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExcelImportRepository extends JpaRepository<ExcelImport, Long> {

    List<ExcelImport> findAllByOrderByUploadedAtDesc();

    Optional<ExcelImport> findByFileName(String fileName);
}