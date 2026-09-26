package com.emplmgt.repository;

import com.emplmgt.entity.HPEEntitlement;
import com.emplmgt.entity.HPEEntitlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HPEEntitlementRepository extends JpaRepository<HPEEntitlement, Long> {

    List<HPEEntitlement> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    Optional<HPEEntitlement> findByEmployeeIdAndHolidayId(Long employeeId, Long holidayId);

    List<HPEEntitlement> findByEmployeeIdAndStatus(Long employeeId, HPEEntitlementStatus status);

    @Query("select e from HPEEntitlement e where e.employee.id = :employeeId and e.status = 'AVAILABLE' and e.expiryDate >= :today")
    List<HPEEntitlement> findAvailableByEmployeeId(@Param("employeeId") Long employeeId, @Param("today") java.time.LocalDate today);

    @Query("select e from HPEEntitlement e where e.employee.id = :employeeId and e.holiday.id = :holidayId and e.status != 'EXPIRED'")
    Optional<HPEEntitlement> findByEmployeeIdAndHolidayIdNotExpired(@Param("employeeId") Long employeeId, @Param("holidayId") Long holidayId);

    @Query("select e from HPEEntitlement e where e.status = 'AVAILABLE' and e.expiryDate < :today")
    List<HPEEntitlement> findExpired(@Param("today") java.time.LocalDate today);

    @Query("select e from HPEEntitlement e join e.holiday h where e.employee.id = :employeeId and h.holidayDate = :date and h.holidayType = 'HPE_HOLIDAY'")
    List<HPEEntitlement> findByEmployeeIdAndHolidayDate(@Param("employeeId") Long employeeId, @Param("date") java.time.LocalDate date);

    @Query("select count(e) from HPEEntitlement e where e.employee.id = :employeeId and e.status = 'AVAILABLE'")
    long countAvailableByEmployeeId(@Param("employeeId") Long employeeId);

    /**
     * Entitlements that can still back a Compensatory Off request: AVAILABLE and not past
     * their expiry date. One entitlement backs one compensatory off day.
     */
    @Query("select count(e) from HPEEntitlement e where e.employee.id = :employeeId and e.status = 'AVAILABLE' and e.expiryDate >= :today")
    long countUsableByEmployeeId(@Param("employeeId") Long employeeId, @Param("today") java.time.LocalDate today);

    /**
     * Reads a single entitlement under a row-level <b>pessimistic write</b> lock
     * ({@code SELECT ... FOR UPDATE}).
     *
     * <p>This is what makes reserving an entitlement safe under concurrency. Two employees'
     * leave applications can otherwise both read the same row as AVAILABLE, both pass the
     * "is it still free?" check, and both write - the last writer wins and the entitlement
     * would end up attached to two different requests. Taking the lock before the status
     * check serialises the read-check-write so the second transaction re-reads the already
     * RESERVED row and is rejected.
     *
     * <p>Complements the {@code uq_leave_requests_active_hpe_entitlement} partial unique
     * index, which remains the last line of defence (it also covers the direct-use endpoint,
     * which does not go through this path). Must be called inside a write transaction.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from HPEEntitlement e where e.id = :id")
    Optional<HPEEntitlement> findByIdForUpdate(@Param("id") Long id);
}