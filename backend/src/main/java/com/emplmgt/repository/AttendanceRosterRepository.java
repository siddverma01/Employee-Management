package com.emplmgt.repository;

import com.emplmgt.entity.AttendanceRoster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttendanceRosterRepository extends JpaRepository<AttendanceRoster, Long> {

    Optional<AttendanceRoster> findByTeamIdAndMonthAndEmployeeCode(Long teamId, String month, String employeeCode);

    boolean existsByTeamIdAndMonthAndEmployeeCode(Long teamId, String month, String employeeCode);

    List<AttendanceRoster> findByTeamIdAndMonth(Long teamId, String month);

    List<AttendanceRoster> findByMonthAndEmployeeCode(String month, String employeeCode);

    List<AttendanceRoster> findByMonth(String month);

    List<AttendanceRoster> findByTeamId(Long teamId);

    @Query("select distinct r.month from AttendanceRoster r where r.team.id = :teamId")
    List<String> findMonthsByTeamId(@Param("teamId") Long teamId);

    @Query("select distinct r.month from AttendanceRoster r")
    List<String> findAllMonths();
}