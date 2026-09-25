package com.emplmgt.repository;

import com.emplmgt.entity.AttendanceRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    Optional<AttendanceRecord> findByEmployeeIdAndAttendanceDate(String employeeId, LocalDate attendanceDate);

    boolean existsByEmployeeIdAndAttendanceDate(String employeeId, LocalDate attendanceDate);

    List<AttendanceRecord> findByAttendanceDateBetweenOrderByAttendanceDateAsc(LocalDate from, LocalDate to);

    Page<AttendanceRecord> findByAttendanceDateBetween(Pageable pageable, LocalDate from, LocalDate to);

    Page<AttendanceRecord> findByAttendanceDateBetweenAndEmployeeId(Pageable pageable, LocalDate from, LocalDate to,
                                                                    String employeeId);

    Page<AttendanceRecord> findByEmployeeId(Pageable pageable, String employeeId);

    List<String> findDistinctEmployeeIdByAttendanceDateBetweenOrderByEmployeeId(LocalDate from, LocalDate to);

    @Query("select distinct r.statusCode from AttendanceRecord r where r.isUnknown = true and r.statusCode is not null")
    List<String> findDistinctUnknownCodes();

    @Query("""
            select r.statusCode, count(r) from AttendanceRecord r
            where r.isUnknown = true and r.statusCode is not null
            group by r.statusCode order by count(r) desc
            """)
    List<Object[]> countUnknownCodes();

    @Modifying
    @Query("""
            update AttendanceRecord r
            set r.statusCode = :to, r.isUnknown = false, r.statusName = :toName, r.updatedAt = :now
            where r.statusCode = :from and r.isUnknown = true
            """)
    int remapUnknown(@Param("from") String from, @Param("to") String to,
                     @Param("toName") String toName, @Param("now") Instant now);

    @Modifying
    @Query("""
            delete from AttendanceRecord r
            where r.employeeId = :employeeId and r.attendanceDate = :attendanceDate
            """)
    int deleteByEmployeeIdAndAttendanceDate(@Param("employeeId") String employeeId,
                                            @Param("attendanceDate") LocalDate attendanceDate);

    List<AttendanceRecord> findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
            LocalDate from, LocalDate to, java.util.Collection<String> employeeIds);

    @Query("""
            select r.statusCode, count(r) from AttendanceRecord r
            where r.attendanceDate between :from and :to
              and (:status is null or r.statusCode = :status)
              and r.employeeId in :employeeIds
            group by r.statusCode order by r.statusCode asc
            """)
    List<Object[]> countByStatusCodes(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                      @Param("status") String status,
                                      @Param("employeeIds") java.util.Collection<String> employeeIds);

    @Query("select distinct r.attendanceDate from AttendanceRecord r order by r.attendanceDate asc")
    List<LocalDate> findDistinctAttendanceDatesAsc();

    List<AttendanceRecord> findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            String employeeId, LocalDate from, LocalDate to);

    @Query("""
            select r.attendanceDate, r.statusCode, count(r) from AttendanceRecord r
            where r.employeeId = :employeeId
            group by r.attendanceDate, r.statusCode
            order by r.attendanceDate asc
            """)
    List<Object[]> countByDateAndStatusCode(@Param("employeeId") String employeeId);

    @Query("select distinct r.location from AttendanceRecord r where r.location is not null order by r.location asc")
    List<String> findDistinctLocations();

    @Query("select distinct r.shift from AttendanceRecord r where r.shift is not null order by r.shift asc")
    List<String> findDistinctShifts();
}