package com.emplmgt.repository;

import com.emplmgt.entity.LeaveRequest;
import com.emplmgt.entity.LeaveStatus;
import com.emplmgt.entity.LeaveType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    long countByStatus(LeaveStatus status);

    @Query("select coalesce(sum(l.days), 0) from LeaveRequest l where l.status = 'APPROVED' and l.leaveType = :type " +
            "and not (l.endDate < :from or l.startDate > :to)")
    BigDecimal sumApprovedDaysInRange(@Param("type") LeaveType type, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select coalesce(sum(l.days), 0) from LeaveRequest l where l.status = 'APPROVED' and l.leaveType = :type " +
            "and not (l.endDate < :from or l.startDate > :to) " +
            "and (:departmentId is null or l.employee.department.id = :departmentId)")
    BigDecimal sumApprovedDaysInRange(@Param("type") LeaveType type, @Param("from") LocalDate from,
                                      @Param("to") LocalDate to, @Param("departmentId") Long departmentId);

    @Query("select count(l) from LeaveRequest l where l.status = 'APPROVED' and not (l.endDate < :from or l.startDate > :to)")
    long countApprovedInRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(l) from LeaveRequest l where l.status = 'APPROVED' " +
            "and not (l.endDate < :from or l.startDate > :to) " +
            "and (:departmentId is null or l.employee.department.id = :departmentId)")
    long countApprovedInRange(@Param("from") LocalDate from, @Param("to") LocalDate to,
                              @Param("departmentId") Long departmentId);

    long countByEmployeeDepartmentIdAndStatus(Long departmentId, LeaveStatus status);

    @Query("""
        select coalesce(d.name, 'Unassigned') , coalesce(sum(l.days), 0)
        from LeaveRequest l left join l.employee e left join e.department d
        where l.status = 'APPROVED' and not (l.endDate < :from or l.startDate > :to)
        group by d.name
        order by sum(l.days) desc
        """)
    List<Object[]> sumApprovedDaysByDepartment(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
        select case when count(l) > 0 then true else false end
        from LeaveRequest l
        where l.employee.id = :employeeId
          and l.status in ('PENDING', 'APPROVED')
          and l.startDate <= :endDate
          and l.endDate   >= :startDate
        """)
    boolean existsOverlapping(@Param("employeeId") Long employeeId,
                              @Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate);

    long countByEmployeeIdAndStatus(Long employeeId, LeaveStatus status);

    List<LeaveRequest> findByEmployeeIdAndStatus(Long employeeId, LeaveStatus status);

    @Query("select coalesce(sum(l.days), 0) from LeaveRequest l " +
           "where l.employee.id = :employeeId and l.leaveType = :type and l.status = 'APPROVED'")
    BigDecimal sumApprovedDays(@Param("employeeId") Long employeeId, @Param("type") LeaveType type);

    @Query("""
        select l from LeaveRequest l
        join fetch l.employee e left join fetch e.department
        where (:status is null or l.status = :status)
          and (:leaveType is null or l.leaveType = :leaveType)
          and (:employeeId is null or l.employee.id = :employeeId)
          and (:departmentId is null or e.department.id = :departmentId)
          and (:from is null or l.startDate >= :from)
          and (:to is null or l.endDate <= :to)
          and (:search is null or lower(e.fullName) like lower(concat('%', cast(:search as string), '%'))
                or lower(e.employeeCode) like lower(concat('%', cast(:search as string), '%')))
        """)
    Page<LeaveRequest> search(@Param("status") LeaveStatus status,
                              @Param("leaveType") LeaveType leaveType,
                              @Param("employeeId") Long employeeId,
                              @Param("departmentId") Long departmentId,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to,
                              @Param("search") String search,
                              Pageable pageable);

    List<LeaveRequest> findByStatusAndEndDateBetween(LeaveStatus status, LocalDate from, LocalDate to);

    @Query("select l from LeaveRequest l where l.employee.id = :employeeId and l.status in ('PENDING','APPROVED') and l.endDate >= :from order by l.startDate")
    List<LeaveRequest> findUpcomingForEmployee(@Param("employeeId") Long employeeId, @Param("from") LocalDate from);

    @Query("select l from LeaveRequest l join fetch l.employee e where l.status = 'APPROVED' and not (l.endDate < :from or l.startDate > :to)")
    List<LeaveRequest> findApprovedInRange(@Param("from") LocalDate from, @Param("to") LocalDate to);
}