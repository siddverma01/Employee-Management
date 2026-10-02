package com.emplmgt.repository;

import com.emplmgt.entity.AttendanceWeekOffAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AttendanceWeekOffAssignmentRepository
        extends JpaRepository<AttendanceWeekOffAssignment, Long> {

    Optional<AttendanceWeekOffAssignment> findByEmployeeIdAndPeriodStart(String employeeId, LocalDate periodStart);

    List<AttendanceWeekOffAssignment> findByEmployeeIdIn(Collection<String> employeeIds);

    /**
     * Week-off values actually rostered for one period, restricted to employees
     * who have attendance in the requested range so the filter dropdown only
     * offers values the grid can show.
     */
    @Query("""
            select distinct a.weekOffValue from AttendanceWeekOffAssignment a, ImportEmployee e
            where e.employeeId = a.employeeId
              and a.periodStart = :periodStart
              and a.weekOffValue is not null
              and (:teamId is null or e.teamId = :teamId)
              and exists (select r.id from AttendanceRecord r
                          where r.employeeId = a.employeeId
                            and r.attendanceDate between :from and :to)
            order by a.weekOffValue asc
            """)
    List<String> findDistinctWeekOffValuesInPeriod(@Param("teamId") Long teamId,
                                                   @Param("periodStart") LocalDate periodStart,
                                                   @Param("from") LocalDate from,
                                                   @Param("to") LocalDate to);
}