package com.emplmgt.repository;

import com.emplmgt.entity.AttendanceShiftAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AttendanceShiftAssignmentRepository
        extends JpaRepository<AttendanceShiftAssignment, Long> {

    Optional<AttendanceShiftAssignment> findByEmployeeIdAndPeriodStart(String employeeId, LocalDate periodStart);

    List<AttendanceShiftAssignment> findByEmployeeIdIn(Collection<String> employeeIds);

    /**
     * Shifts actually rostered for one period, restricted to employees who have
     * attendance in the requested range so the filter dropdown only offers
     * values the grid can show.
     */
    @Query("""
            select distinct a.shiftValue from AttendanceShiftAssignment a, ImportEmployee e
            where e.employeeId = a.employeeId
              and a.periodStart = :periodStart
              and a.shiftValue is not null
              and (:teamId is null or e.teamId = :teamId)
              and exists (select r.id from AttendanceRecord r
                          where r.employeeId = a.employeeId
                            and r.attendanceDate between :from and :to)
            order by a.shiftValue asc
            """)
    List<String> findDistinctShiftValuesInPeriod(@Param("teamId") Long teamId,
                                                 @Param("periodStart") LocalDate periodStart,
                                                 @Param("from") LocalDate from,
                                                 @Param("to") LocalDate to);

    /** Employees that have at least one period assignment, used to detect legacy rows. */
    @Query("select distinct a.employeeId from AttendanceShiftAssignment a where a.employeeId in (:employeeIds)")
    List<String> findEmployeesWithAssignments(@Param("employeeIds") Collection<String> employeeIds);
}