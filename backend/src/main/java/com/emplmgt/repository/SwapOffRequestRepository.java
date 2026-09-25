package com.emplmgt.repository;

import com.emplmgt.entity.LeaveStatus;
import com.emplmgt.entity.SwapOffRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SwapOffRequestRepository extends JpaRepository<SwapOffRequest, Long> {

    List<SwapOffRequest> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    long countByStatus(LeaveStatus status);

    long countByEmployeeDepartmentIdAndStatus(Long departmentId, LeaveStatus status);

    long countByEmployeeIdAndStatus(Long employeeId, LeaveStatus status);

    List<SwapOffRequest> findByEmployeeIdAndStatus(Long employeeId, LeaveStatus status);

    @Query("select count(s) from SwapOffRequest s where s.employee.id = :employeeId "
           + "and s.status = 'APPROVED' and s.compOffCredited = true "
           + "and year(s.requestedOffDate) = :year")
    long countApprovedCredits(@Param("employeeId") Long employeeId, @Param("year") int year);

    @Query("select case when count(s) > 0 then true else false end from SwapOffRequest s "
           + "where s.employee.id = :employeeId and s.status = 'APPROVED' "
           + "and s.requestedOffDate <= :end and s.requestedOffDate >= :start")
    boolean hasApprovedBetween(@Param("employeeId") Long employeeId,
                               @Param("start") LocalDate start,
                               @Param("end") LocalDate end);

    @Query("""
        select s from SwapOffRequest s
        join fetch s.employee e left join fetch e.department
        where (:status is null or s.status = :status)
          and (:employeeId is null or s.employee.id = :employeeId)
          and (:departmentId is null or e.department.id = :departmentId)
          and (:from is null or s.createdAt >= :from)
          and (:to is null or s.createdAt <= :to)
          and (:search is null or lower(e.fullName) like lower(concat('%', cast(:search as string), '%'))
                or lower(e.employeeCode) like lower(concat('%', cast(:search as string), '%')))
        """)
    Page<SwapOffRequest> search(@Param("status") LeaveStatus status,
                                @Param("employeeId") Long employeeId,
                                @Param("departmentId") Long departmentId,
                                @Param("from") java.time.Instant from,
                                @Param("to") java.time.Instant to,
                                @Param("search") String search,
                                Pageable pageable);
}