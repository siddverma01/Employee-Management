package com.emplmgt.repository;

import com.emplmgt.entity.ImportEmployee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ImportEmployeeRepository extends JpaRepository<ImportEmployee, String> {

    @Query("select distinct e.teamId from ImportEmployee e where e.teamId is not null")
    List<Long> findDistinctTeamIds();

    @Query("""
            select e.employeeId from ImportEmployee e
            where (:teamId is null or e.teamId = :teamId)
              and (:q is null or lower(e.employeeName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(e.employeeId) like lower(concat('%', cast(:q as string), '%')))
              and (:location is null or e.location = :location)
            """)
    List<String> findFilteredEmployeeIds(@Param("teamId") Long teamId, @Param("q") String q,
                                         @Param("location") String location);

    @Query("""
            select e from ImportEmployee e
            where e.active = TRUE
              and (:teamId is null or e.teamId = :teamId)
              and (:q is null or lower(e.employeeName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(e.employeeId) like lower(concat('%', cast(:q as string), '%')))
              and (:location is null or e.location = :location)
              and exists (select r.id from AttendanceRecord r
                          where r.employeeId = e.employeeId
                            and r.attendanceDate between :from and :to
                            and (:status is null or r.statusCode = :status))
            """)
    List<ImportEmployee> findRosterEmployees(@Param("teamId") Long teamId, @Param("q") String q,
                                             @Param("location") String location,
                                             @Param("from") LocalDate from, @Param("to") LocalDate to,
                                             @Param("status") String status);

    /**
     * Find employees for roster including those in their exit month.
     * Includes employees who:
     * 1. Have attendance records in the selected month, OR
     * 2. Have their exit month equal to the selected month (exit date falls in this month)
     * This ensures employees in their exit month appear in the roster even if they
     * have no attendance records yet for that month.
     */
    @Query("""
            select e from ImportEmployee e
            where e.active = TRUE
              and (:teamId is null or e.teamId = :teamId)
              and (:q is null or lower(e.employeeName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(e.employeeId) like lower(concat('%', cast(:q as string), '%')))
              and (:location is null or e.location = :location)
              and (
                  exists (select r.id from AttendanceRecord r
                          where r.employeeId = e.employeeId
                            and r.attendanceDate between :from and :to
                            and (:status is null or r.statusCode = :status))
                  or (e.exitDate is not null and :from <= e.exitDate and e.exitDate <= :to)
              )
            """)
    List<ImportEmployee> findEmployeesForRosterWithExit(@Param("teamId") Long teamId, @Param("q") String q,
                                                        @Param("location") String location,
                                                        @Param("from") LocalDate from, @Param("to") LocalDate to,
                                                        @Param("status") String status);

    /**
     * Every employee matching the roster filters, regardless of whether they
     * have an attendance record in the requested range.
     *
     * <p>Deliberately differs from {@link #findRosterEmployees}: the grid only
     * lists employees who actually have records in the month, but an export must
     * also cover the filtered employees whose month is still blank — otherwise a
     * downloaded roster would silently omit people the admin expects to see.</p>
     */
    @Query("""
            select e from ImportEmployee e
            where (:teamId is null or e.teamId = :teamId)
              and (:q is null or lower(e.employeeName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(e.employeeId) like lower(concat('%', cast(:q as string), '%')))
              and (:location is null or e.location = :location)
            """)
    List<ImportEmployee> findEmployeesForExport(@Param("teamId") Long teamId, @Param("q") String q,
                                                @Param("location") String location);

    @Query("""
            select count(e) from ImportEmployee e
            where (:teamId is null or e.teamId = :teamId)
              and (:q is null or lower(e.employeeName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(e.employeeId) like lower(concat('%', cast(:q as string), '%')))
              and (:location is null or e.location = :location)
              and exists (select r.id from AttendanceRecord r
                          where r.employeeId = e.employeeId and r.attendanceDate between :from and :to)
            """)
    long countEmployeeRows(@Param("teamId") Long teamId, @Param("q") String q,
                           @Param("location") String location,
                           @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select distinct e.location from ImportEmployee e
            where e.location is not null and (:teamId is null or e.teamId = :teamId)
              and exists (select r.id from AttendanceRecord r
                          where r.employeeId = e.employeeId and r.attendanceDate between :from and :to)
            order by e.location asc
            """)
    List<String> findDistinctLocationsInMonth(@Param("teamId") Long teamId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    /**
     * Legacy fallback only: the employee master's shift, for employees with no
     * row in {@code attendance_shift_assignments}. The roster itself must not
     * use this - shift belongs to the period, not the person.
     */
    @Query("""
            select distinct e.defaultShift from ImportEmployee e
            where e.defaultShift is not null and (:teamId is null or e.teamId = :teamId)
              and exists (select r.id from AttendanceRecord r
                          where r.employeeId = e.employeeId and r.attendanceDate between :from and :to)
            order by e.defaultShift asc
            """)
    List<String> findDistinctShiftsInMonth(@Param("teamId") Long teamId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);

    @Query("select distinct e.location from ImportEmployee e where e.location is not null order by e.location asc")
    List<String> findDistinctLocations();

    @Query("select distinct e.defaultShift from ImportEmployee e where e.defaultShift is not null order by e.defaultShift asc")
    List<String> findDistinctShifts();

    /**
     * Keeps {@code default_shift} meaning "current shift" rather than "the shift
     * from whichever month happened to be imported first". Shift rotates, so the
     * master mirrors the most recently imported period; the per-period history
     * lives in {@code attendance_shift_assignments}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ImportEmployee e set e.defaultShift = :shift where e.employeeId = :employeeId")
    int refreshMasterShift(@Param("employeeId") String employeeId, @Param("shift") String shift);

    /**
     * Keeps {@code week_off} meaning "current week off" rather than "the week off
     * from whichever month happened to be imported first". The schedule rotates
     * independently of shift, so the master mirrors the most recently imported
     * period; the per-period history lives in
     * {@code attendance_week_off_assignments}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ImportEmployee e set e.weekOff = :weekOff where e.employeeId = :employeeId")
    int refreshMasterWeekOff(@Param("employeeId") String employeeId, @Param("weekOff") String weekOff);

    @Query(value = """
            select e.* from import_employees e
            where e.active = TRUE
              and e.employee_id ~ '^[0-9]+$'
              and exists (select r.id from attendance_records r
                          where r.employee_id = e.employee_id
                            and r.attendance_date between :from and :to)
              and (:q is null or lower(e.employee_name) like lower(concat('%', :q, '%'))
                   or lower(e.employee_id) like lower(concat('%', :q, '%')))
            order by e.employee_name asc
            """, nativeQuery = true)
    List<ImportEmployee> findAllActiveForDropdown(@Param("q") String q);

    @Query(value = """
            select e.* from import_employees e
            where e.active = TRUE
              and e.employee_id ~ '^[0-9]+$'
              and exists (select r.id from attendance_records r
                          where r.employee_id = e.employee_id
                            and r.attendance_date between :from and :to)
              and (:q is null or lower(e.employee_name) like lower(concat('%', :q, '%'))
                   or lower(e.employee_id) like lower(concat('%', :q, '%')))
            order by e.employee_name asc
            """, nativeQuery = true)
    List<ImportEmployee> findForMonthDropdown(@Param("from") java.time.LocalDate from,
                                              @Param("to") java.time.LocalDate to,
                                              @Param("q") String q);
}