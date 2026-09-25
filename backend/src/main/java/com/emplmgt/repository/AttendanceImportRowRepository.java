package com.emplmgt.repository;

import com.emplmgt.entity.AttendanceImportRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendanceImportRowRepository extends JpaRepository<AttendanceImportRow, Long> {

    List<AttendanceImportRow> findByImportHistoryIdOrderByIdAsc(Long importId);

    List<AttendanceImportRow> findByImportHistoryIdAndActionInOrderByIdAsc(Long importId, List<String> actions);

    Page<AttendanceImportRow> findByImportHistoryId(Long importId, Pageable pageable);

    long countByImportHistoryId(Long importId);

    long countByImportHistoryIdAndAction(Long importId, String action);
}