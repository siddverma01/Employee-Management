package com.emplmgt.repository;

import com.emplmgt.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
        select a from AuditLog a
        where (:action is null or lower(a.action) like lower(concat('%', cast(:action as string), '%')))
          and (:entityType is null or lower(a.entityType) like lower(concat('%', cast(:entityType as string), '%')))
        """)
    Page<AuditLog> search(@Param("action") String action, @Param("entityType") String entityType, Pageable pageable);
}