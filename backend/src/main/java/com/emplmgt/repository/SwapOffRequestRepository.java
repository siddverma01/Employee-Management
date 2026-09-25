package com.emplmgt.repository;

import com.emplmgt.entity.LeaveStatus;
import com.emplmgt.entity.SwapOffRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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

    @Query("select case when count(s) > 0 then true else false end from SwapOffRequest s "
           + "where s.workedForEmployee.id = :employeeId and s.status = 'APPROVED' "
           + "and s.requestedOffDate = :date")
    boolean existsApprovedForEmployeeAndDate(@Param("employeeId") Long employeeId,
                                              @Param("date") LocalDate date);

    @Query("""
        select s from SwapOffRequest s
        join fetch s.employee e left join fetch e.department
        where (:status is null or s.status = :status)
          and (:employeeId is null or s.employee.id = :employeeId)
          and (:departmentId is null or e.department.id = :departmentId)
          and (:from is null or s.createdAt >= :from)
          and (:to is null or s.createdAt <= :to)
          and (:search = '' or lower(e.fullName) like lower(concat('%', cast(:search as string), '%'))
                or lower(e.employeeCode) like lower(concat('%', cast(:search as string), '%')))
        """)
    Page<SwapOffRequest> search(@Param("status") LeaveStatus status,
                                @Param("employeeId") Long employeeId,
                                @Param("departmentId") Long departmentId,
                                @Param("from") java.time.Instant from,
                                @Param("to") java.time.Instant to,
                                @Param("search") String search,
                                Pageable pageable);

    @Query(value = """
        select s.id, s.attachment, s.comp_off_credited, s.created_at, s.decided_at, s.decided_by, s.employee_id, s.reason, s.rejection_reason, s.requested_off_date, s.status, s.updated_at, s.worked_date, s.worked_for_employee_id
        from swap_off_requests s
        join employees e on e.id = s.employee_id
        left join departments d on d.id = e.department_id
        where (:status is null or s.status = :status)
          and (:employeeId is null or s.employee_id = :employeeId)
          and (:departmentId is null or e.department_id = :departmentId)
          and (s.created_at >= COALESCE(:from, '1900-01-01'::timestamp))
          and (s.created_at <= COALESCE(:to, '2100-12-31'::timestamp))
        order by s.created_at desc
        limit :limit offset :offset
        """, nativeQuery = true)
    List<SwapOffRequest> searchWithoutSearchParamNative(@Param("status") String status,
                                                        @Param("employeeId") Long employeeId,
                                                        @Param("departmentId") Long departmentId,
                                                        @Param("from") java.time.Instant from,
                                                        @Param("to") java.time.Instant to,
                                                        @Param("limit") int limit,
                                                        @Param("offset") int offset);

    @Query(value = """
        select count(s.id) from swap_off_requests s
        join employees e on e.id = s.employee_id
        left join departments d on d.id = e.department_id
        where (:status is null or s.status = :status)
          and (:employeeId is null or s.employee_id = :employeeId)
          and (:departmentId is null or e.department_id = :departmentId)
          and (s.created_at >= COALESCE(:from, '1900-01-01'::timestamp))
          and (s.created_at <= COALESCE(:to, '2100-12-31'::timestamp))
        """, nativeQuery = true)
    long countWithoutSearchParam(@Param("status") String status,
                                 @Param("employeeId") Long employeeId,
                                 @Param("departmentId") Long departmentId,
                                 @Param("from") java.time.Instant from,
                                 @Param("to") java.time.Instant to);

    /** Approved swap-off requests of the given employee code that touch a
     *  single date (requested off date or worked date) — used to resolve the
     *  "source request" of a roster status cell. */
    @Query("select s from SwapOffRequest s join fetch s.employee e where s.status = 'APPROVED' "
            + "and e.employeeCode = :employeeCode and (s.workedDate = :date or s.requestedOffDate = :date)")
    List<SwapOffRequest> findApprovedByCodeAndDate(@Param("employeeCode") String employeeCode,
                                                   @Param("date") LocalDate date);
}