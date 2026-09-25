package com.emplmgt.repository;

import com.emplmgt.entity.ImportEmployee;
import org.springframework.data.jpa.repository.JpaRepository;
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
              and (:shift is null or e.defaultShift = :shift)
            """)
    List<String> findFilteredEmployeeIds(@Param("teamId") Long teamId, @Param("q") String q,
                                         @Param("location") String location, @Param("shift") String shift);

    @Query("""
            select e from ImportEmployee e
            where (:teamId is null or e.teamId = :teamId)
              and (:q is null or lower(e.employeeName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(e.employeeId) like lower(concat('%', cast(:q as string), '%')))
              and (:location is null or e.location = :location)
              and (:shift is null or e.defaultShift = :shift)
              and exists (select r.id from AttendanceRecord r
                          where r.employeeId = e.employeeId
                            and r.attendanceDate between :from and :to
                            and (:status is null or r.statusCode = :status))
            """)
    List<ImportEmployee> findRosterEmployees(@Param("teamId") Long teamId, @Param("q") String q,
                                             @Param("location") String location, @Param("shift") String shift,
                                             @Param("from") LocalDate from, @Param("to") LocalDate to,
                                             @Param("status") String status);

    @Query("""
            select count(e) from ImportEmployee e
            where (:teamId is null or e.teamId = :teamId)
              and (:q is null or lower(e.employeeName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(e.employeeId) like lower(concat('%', cast(:q as string), '%')))
              and (:location is null or e.location = :location)
              and (:shift is null or e.defaultShift = :shift)
              and exists (select r.id from AttendanceRecord r
                          where r.employeeId = e.employeeId and r.attendanceDate between :from and :to)
            """)
    long countEmployeeRows(@Param("teamId") Long teamId, @Param("q") String q,
                           @Param("location") String location, @Param("shift") String shift,
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

    @Query(value = """
            select e.* from import_employees e
            where e.active = true
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
            where e.active = true
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