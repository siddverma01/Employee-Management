package com.emplmgt.service;

import com.emplmgt.entity.Attendance;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.Employee;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceRepository;
import com.emplmgt.util.WeekOffUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves whether an employee actually <b>worked</b> on a given date, reusing the
 * application's existing attendance sources instead of introducing a second system:
 *
 * <ol>
 *   <li>{@code attendance_records} — the attendance roster status cell
 *       ({@code WFO | WFH | HPEH | WO | PL | SL | CO | ...}), written by roster edits,
 *       leave/swap approvals and the historical workbook import.</li>
 *   <li>{@code attendance} — the daily attendance record
 *       ({@code WORK_FROM_OFFICE | WORK_FROM_HOME | LEAVE | COMP_OFF | ...}).</li>
 *   <li>The employee's configured weekly off ({@link WeekOffUtil}).</li>
 * </ol>
 *
 * <p>When none of the sources records anything for that date the result is
 * {@link WorkStatus#UNKNOWN} — "no record" must never be read as "worked"
 * (on a holiday an empty day means the employee took the day, not that they worked).</p>
 */
@Service
@RequiredArgsConstructor
public class RosterWorkStatusService {

    /**
     * Roster status codes that mean the employee actually worked that day.
     * Taken from {@code attendance_status}: WFO (present in office), WFH (working
     * remotely), SW WK (swap working), WK WRK (weekend working), WDT (working for
     * another team), HD (half day present) and TR (on training).
     */
    public static final Set<String> WORKED_CODES =
            Set.of("WFO", "WFH", "SW WK", "WK WRK", "WDT", "HD", "TR");

    /**
     * Roster status codes that mean the employee did not work that day.
     * Includes {@code HPEH} (took the HPE holiday), week off, leaves, comp off,
     * swap off, wellness and attrition.
     */
    public static final Set<String> NOT_WORKED_CODES =
            Set.of("WO", "PL", "SL", "CO", "FL", "HPEH", "SW OFF", "WX", "ITS", "LEAVE");

    /** Where the deciding status came from. */
    public enum WorkSource { ROSTER, ATTENDANCE, WEEK_OFF, NONE }

    /** Did the employee work? UNKNOWN = no source recorded anything for the date. */
    public enum WorkStatus { WORKED, NOT_WORKED, UNKNOWN }

    /** Decision plus the source/status code that produced it (for audit/messages). */
    public record DayWorkStatus(WorkStatus status, WorkSource source, String code) {

        public boolean worked() {
            return status == WorkStatus.WORKED;
        }

        public static DayWorkStatus of(WorkStatus status, WorkSource source) {
            return new DayWorkStatus(status, source, null);
        }
    }

    private final AttendanceRecordRepository recordRepository;
    private final AttendanceRepository attendanceRepository;
    private final WeekOffUtil weekOffUtil;

    @Transactional(readOnly = true)
    public DayWorkStatus resolve(Employee employee, LocalDate date) {
        if (employee == null || date == null) {
            return DayWorkStatus.of(WorkStatus.UNKNOWN, WorkSource.NONE);
        }

        DayWorkStatus fromRoster = fromRosterCell(employee, date);
        if (fromRoster != null) {
            return fromRoster;
        }

        DayWorkStatus fromAttendance = fromAttendance(employee, date);
        if (fromAttendance != null) {
            return fromAttendance;
        }

        if (weekOffUtil.isWeekOff(employee, date)) {
            return new DayWorkStatus(WorkStatus.NOT_WORKED, WorkSource.WEEK_OFF, "WO");
        }

        return DayWorkStatus.of(WorkStatus.UNKNOWN, WorkSource.NONE);
    }

    /** Roster cell for (employee code, date); {@code null} when absent or unrecognised. */
    private DayWorkStatus fromRosterCell(Employee employee, LocalDate date) {
        if (employee.getEmployeeCode() == null) {
            return null;
        }
        AttendanceRecord record = recordRepository
                .findByEmployeeIdAndAttendanceDate(employee.getEmployeeCode(), date)
                .orElse(null);
        if (record == null || record.getStatusCode() == null || record.getStatusCode().isBlank()) {
            return null;
        }
        String code = record.getStatusCode().trim().toUpperCase(Locale.ROOT);
        if (WORKED_CODES.contains(code)) {
            return new DayWorkStatus(WorkStatus.WORKED, WorkSource.ROSTER, code);
        }
        if (code.startsWith("ATR") || NOT_WORKED_CODES.contains(code)) {
            return new DayWorkStatus(WorkStatus.NOT_WORKED, WorkSource.ROSTER, code);
        }
        // Unknown / junk code: never treat it as evidence of work, fall through.
        return null;
    }

    /** Daily attendance record for (employee id, date); {@code null} when absent. */
    private DayWorkStatus fromAttendance(Employee employee, LocalDate date) {
        if (employee.getId() == null) {
            return null;
        }
        Attendance attendance = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), date)
                .orElse(null);
        if (attendance == null || attendance.getAttendanceType() == null) {
            return null;
        }
        return switch (attendance.getAttendanceType()) {
            case WORK_FROM_OFFICE, WORK_FROM_HOME ->
                    new DayWorkStatus(WorkStatus.WORKED, WorkSource.ATTENDANCE, attendance.getAttendanceType().getShortLabel());
            default ->
                    new DayWorkStatus(WorkStatus.NOT_WORKED, WorkSource.ATTENDANCE, attendance.getAttendanceType().getShortLabel());
        };
    }
}
