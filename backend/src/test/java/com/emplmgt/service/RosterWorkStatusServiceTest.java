package com.emplmgt.service;

import com.emplmgt.entity.Attendance;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.AttendanceType;
import com.emplmgt.entity.Employee;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceRepository;
import com.emplmgt.service.RosterWorkStatusService.DayWorkStatus;
import com.emplmgt.service.RosterWorkStatusService.WorkSource;
import com.emplmgt.service.RosterWorkStatusService.WorkStatus;
import com.emplmgt.util.WeekOffUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RosterWorkStatusServiceTest {

    @Mock AttendanceRecordRepository recordRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock WeekOffUtil weekOffUtil;

    private RosterWorkStatusService service;
    private Employee employee;
    private final LocalDate date = LocalDate.of(2026, 9, 14);

    @BeforeEach
    void setUp() {
        service = new RosterWorkStatusService(recordRepository, attendanceRepository, weekOffUtil);
        employee = Employee.builder()
                .id(10L)
                .employeeCode("EMP-001")
                .fullName("Test Employee")
                .location("Pune")
                .build();
    }

    private AttendanceRecord record(String code) {
        return AttendanceRecord.builder()
                .id(1L)
                .employeeId("EMP-001")
                .attendanceDate(date)
                .statusCode(code)
                .build();
    }

    @Nested
    class RosterCellDecides {

        @Test
        void workedRosterCodeWins() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.of(record("WFO")));

            DayWorkStatus status = service.resolve(employee, date);

            assertThat(status.status()).isEqualTo(WorkStatus.WORKED);
            assertThat(status.source()).isEqualTo(WorkSource.ROSTER);
            assertThat(status.code()).isEqualTo("WFO");
        }

        @Test
        void wfhCountsAsWorked() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.of(record("WFH")));

            assertThat(service.resolve(employee, date).worked()).isTrue();
        }

        @Test
        void hpehCodeIsNotWorked() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.of(record("HPEH")));

            DayWorkStatus status = service.resolve(employee, date);

            assertThat(status.status()).isEqualTo(WorkStatus.NOT_WORKED);
            assertThat(status.code()).isEqualTo("HPEH");
        }

        @Test
        void weekOffAndLeaveCodesAreNotWorked() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.of(record("WO")));

            assertThat(service.resolve(employee, date).status()).isEqualTo(WorkStatus.NOT_WORKED);
        }

        @Test
        void attritionCodeIsNotWorked() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.of(record("ATR2")));

            assertThat(service.resolve(employee, date).status()).isEqualTo(WorkStatus.NOT_WORKED);
        }

        @Test
        void blankStatusCodeFallsThroughToTheNextSource() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.of(record("  ")));
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(10L, date))
                    .thenReturn(Optional.empty());
            when(weekOffUtil.isWeekOff(employee, date)).thenReturn(false);

            assertThat(service.resolve(employee, date).status()).isEqualTo(WorkStatus.UNKNOWN);
        }

        @Test
        void unrecognisedJunkCodeNeverCountsAsWork() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.of(record("XYZZY")));
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(10L, date))
                    .thenReturn(Optional.empty());
            when(weekOffUtil.isWeekOff(employee, date)).thenReturn(false);

            DayWorkStatus status = service.resolve(employee, date);

            assertThat(status.status()).isEqualTo(WorkStatus.UNKNOWN);
        }

        @Test
        void rosterCellShortCircuitsTheOtherSources() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.of(record("WFO")));

            service.resolve(employee, date);

            org.mockito.Mockito.verify(attendanceRepository, never()).findByEmployeeIdAndAttendanceDate(any(), any());
            org.mockito.Mockito.verify(weekOffUtil, never()).isWeekOff(any(), any());
        }
    }

    @Nested
    class AttendanceFallback {

        @Test
        void workFromHomeAttendanceIsWorked() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.empty());
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(10L, date))
                    .thenReturn(Optional.of(Attendance.builder()
                            .id(1L).employee(employee).attendanceDate(date)
                            .attendanceType(AttendanceType.WORK_FROM_HOME)
                            .build()));

            DayWorkStatus status = service.resolve(employee, date);

            assertThat(status.status()).isEqualTo(WorkStatus.WORKED);
            assertThat(status.source()).isEqualTo(WorkSource.ATTENDANCE);
        }

        @Test
        void holidayAttendanceIsNotWorked() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.empty());
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(10L, date))
                    .thenReturn(Optional.of(Attendance.builder()
                            .id(1L).employee(employee).attendanceDate(date)
                            .attendanceType(AttendanceType.HOLIDAY)
                            .build()));

            DayWorkStatus status = service.resolve(employee, date);

            assertThat(status.status()).isEqualTo(WorkStatus.NOT_WORKED);
            assertThat(status.code()).isEqualTo("Holiday");
        }

        @Test
        void leaveAttendanceIsNotWorked() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.empty());
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(10L, date))
                    .thenReturn(Optional.of(Attendance.builder()
                            .id(1L).employee(employee).attendanceDate(date)
                            .attendanceType(AttendanceType.LEAVE)
                            .build()));

            assertThat(service.resolve(employee, date).status()).isEqualTo(WorkStatus.NOT_WORKED);
        }
    }

    @Nested
    class NoRecordIsNeverWorked {

        @Test
        void weekOffIsUsedWhenNothingElseIsRecorded() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.empty());
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(10L, date))
                    .thenReturn(Optional.empty());
            when(weekOffUtil.isWeekOff(employee, date)).thenReturn(true);

            DayWorkStatus status = service.resolve(employee, date);

            assertThat(status.status()).isEqualTo(WorkStatus.NOT_WORKED);
            assertThat(status.source()).isEqualTo(WorkSource.WEEK_OFF);
            assertThat(status.code()).isEqualTo("WO");
        }

        @Test
        void missingRecordMeansUnknownRatherThanWorked() {
            when(recordRepository.findByEmployeeIdAndAttendanceDate("EMP-001", date))
                    .thenReturn(Optional.empty());
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(10L, date))
                    .thenReturn(Optional.empty());
            when(weekOffUtil.isWeekOff(employee, date)).thenReturn(false);

            DayWorkStatus status = service.resolve(employee, date);

            assertThat(status.status()).isEqualTo(WorkStatus.UNKNOWN);
            assertThat(status.source()).isEqualTo(WorkSource.NONE);
            assertThat(status.worked()).isFalse();
        }

        @Test
        void nullInputsAreUnknown() {
            assertThat(service.resolve(null, date).status()).isEqualTo(WorkStatus.UNKNOWN);
            assertThat(service.resolve(employee, null).status()).isEqualTo(WorkStatus.UNKNOWN);
        }

        @Test
        void employeeWithoutCodeFallsBackToAttendance() {
            employee.setEmployeeCode(null);
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(10L, date))
                    .thenReturn(Optional.empty());
            when(weekOffUtil.isWeekOff(employee, date)).thenReturn(false);

            assertThat(service.resolve(employee, date).status()).isEqualTo(WorkStatus.UNKNOWN);
            org.mockito.Mockito.verify(recordRepository, never()).findByEmployeeIdAndAttendanceDate(any(), any());
        }
    }
}
