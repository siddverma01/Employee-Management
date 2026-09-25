package com.emplmgt.repository;

import com.emplmgt.entity.Attendance;
import com.emplmgt.entity.AttendanceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    Optional<Attendance> findByEmployeeIdAndAttendanceDate(Long employeeId, LocalDate date);

    List<Attendance> findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(Long employeeId, LocalDate from, LocalDate to);

    @Query("select count(a) from Attendance a where a.employee.id = :employeeId and a.attendanceType = :type and a.attendanceDate between :from and :to")
    long countByEmployeeAndTypeAndRange(@Param("employeeId") Long employeeId,
                                        @Param("type") AttendanceType type,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    @Query("select a from Attendance a join fetch a.employee e left join fetch e.department where a.attendanceDate = :date and a.attendanceType in :types")
    List<Attendance> findByDateAndTypes(@Param("date") LocalDate date, @Param("types") List<AttendanceType> types);

    boolean existsByEmployeeIdAndAttendanceDate(Long employeeId, LocalDate date);

    List<Attendance> findByAttendanceDateBetweenAndAttendanceType(LocalDate from, LocalDate to, AttendanceType type);

    @Query("select count(a) from Attendance a where a.attendanceType = :type and a.attendanceDate between :from and :to")
    long countByTypeInRange(@Param("type") AttendanceType type, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(a) from Attendance a where a.attendanceType = :type and a.attendanceDate between :from and :to " +
            "and (:departmentId is null or a.employee.department.id = :departmentId)")
    long countByTypeInRange(@Param("type") AttendanceType type, @Param("from") LocalDate from,
                            @Param("to") LocalDate to, @Param("departmentId") Long departmentId);

    @Query("select count(a) from Attendance a " +
            "where a.attendanceDate = :date and a.attendanceType = :type " +
            "and (:departmentId is null or a.employee.department.id = :departmentId)")
    long countByDateAndType(@Param("date") LocalDate date, @Param("type") AttendanceType type,
                            @Param("departmentId") Long departmentId);

    @Query("""
        select a from Attendance a
        join fetch a.employee e left join fetch e.department
        where (:dateFrom is null or a.attendanceDate >= :dateFrom)
          and (:dateTo is null or a.attendanceDate <= :dateTo)
          and (:employeeId is null or a.employee.id = :employeeId)
          and (:departmentId is null or e.department.id = :departmentId)
          and (:type is null or a.attendanceType = :type)
        """)
    Page<Attendance> search(@Param("dateFrom") LocalDate dateFrom,
                            @Param("dateTo") LocalDate dateTo,
                            @Param("employeeId") Long employeeId,
                            @Param("departmentId") Long departmentId,
                            @Param("type") AttendanceType type,
                            Pageable pageable);
}