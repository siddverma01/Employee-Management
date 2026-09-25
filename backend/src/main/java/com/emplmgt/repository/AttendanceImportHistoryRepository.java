package com.emplmgt.repository;

import com.emplmgt.entity.AttendanceImportHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendanceImportHistoryRepository extends JpaRepository<AttendanceImportHistory, Long> {

    List<AttendanceImportHistory> findAllByOrderByImportedAtDesc();
}