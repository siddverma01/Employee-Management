package com.emplmgt.repository;

import com.emplmgt.entity.HPEEntitlement;
import com.emplmgt.entity.HPEEntitlementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}