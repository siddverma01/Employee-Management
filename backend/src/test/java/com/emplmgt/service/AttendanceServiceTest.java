package com.emplmgt.service;

import com.emplmgt.dto.DashboardDtos;
import com.emplmgt.entity.*;
import com.emplmgt.repository.AttendanceRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.LeaveRequestRepository;
import com.emplmgt.service.TeamAccessService.AccessMode;
import com.emplmgt.service.TeamAccessService.ResolvedTeam;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.WeekOffUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceServiceTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock LeaveRequestRepository leaveRequestRepository;
    @Mock HolidayRepository holidayRepository;
    @Mock DepartmentRepository departmentRepository;
    @Mock AppClock appClock;
    @Mock AuditService auditService;
    @Mock TeamAccessService teamAccessService;
    @Mock WeekOffUtil weekOffUtil;

    private AttendanceService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceService(attendanceRepository, employeeRepository, leaveRequestRepository,
                holidayRepository, departmentRepository, appClock, auditService, teamAccessService, weekOffUtil);
        when(teamAccessService.resolve(nullable(Long.class)))
                .thenReturn(new ResolvedTeam(null, AccessMode.FULL));
        when(weekOffUtil.isWeekOff(any(), any())).thenReturn(false);
    }

    @Test
    void todayStatusSeparatesWorkingFromOnLeave() {
        LocalDate today = LocalDate.of(2026, 9, 18);
        when(appClock.today()).thenReturn(today);
        when(holidayRepository.findVisibleInRange(today, today, null, null)).thenReturn(List.of());

        Employee wfhEmp = Employee.builder()
                .id(1L).employeeCode("EMP-001").fullName("John Doe")
                .location("Remote").employmentStatus(EmploymentStatus.ACTIVE).build();
        Employee leaveEmp = Employee.builder()
                .id(2L).employeeCode("EMP-002").fullName("Jane Doe")
                .location("New York").employmentStatus(EmploymentStatus.ACTIVE).build();

        Attendance wfhAttendance = Attendance.builder()
                .employee(wfhEmp).attendanceType(AttendanceType.WORK_FROM_HOME).attendanceDate(today).build();
        when(attendanceRepository.findByDateAndTypes(eq(today), any()))
                .thenReturn(List.of(wfhAttendance));

        LeaveRequest approvedLeave = LeaveRequest.builder()
                .employee(leaveEmp).leaveType(LeaveType.SICK_LEAVE).build();
        when(leaveRequestRepository.findApprovedInRange(today, today))
                .thenReturn(List.of(approvedLeave));
        when(employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE))
                .thenReturn(List.of(wfhEmp, leaveEmp));

        DashboardDtos.TodayStatus result = service.todayStatus(null, null);

        assertThat(result.mode()).isEqualTo("FULL");
        assertThat(result.working()).hasSize(1);
        assertThat(result.working().get(0).fullName()).isEqualTo("John Doe");
        assertThat(result.working().get(0).attendanceType()).isEqualTo(AttendanceType.WORK_FROM_HOME);

        assertThat(result.onLeave()).hasSize(1);
        assertThat(result.onLeave().get(0).fullName()).isEqualTo("Jane Doe");
        assertThat(result.onLeave().get(0).leaveType()).isEqualTo(LeaveType.SICK_LEAVE);
    }

    @Test
    void todayStatusSkipsHolidayAndFallsBackToWFO() {
        LocalDate today = LocalDate.of(2026, 9, 18);
        when(appClock.today()).thenReturn(today);
        Holiday holiday = Holiday.builder().id(1L).holidayDate(today).build();
        when(holidayRepository.findVisibleInRange(today, today, null, null)).thenReturn(List.of(holiday));

        DashboardDtos.TodayStatus result = service.todayStatus(null, null);

        assertThat(result.working()).isEmpty();
        assertThat(result.onLeave()).isEmpty();
    }

    @Test
    void todayStatusBasicModeOnlyExposesAvailability() {
        LocalDate today = LocalDate.of(2026, 9, 18);
        when(appClock.today()).thenReturn(today);
        when(teamAccessService.resolve(99L)).thenReturn(new ResolvedTeam(99L, AccessMode.BASIC));

        Department otherTeam = Department.builder().id(99L).name("SCM").build();
        Employee other = Employee.builder()
                .id(3L).employeeCode("EMP-099").fullName("Other Agent").designation("Secret")
                .shift("Night").location("Remote").weekOff("Sat-Sun")
                .department(otherTeam)
                .employmentStatus(EmploymentStatus.ACTIVE).build();
        when(employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE)).thenReturn(List.of(other));
        when(holidayRepository.findVisibleInRange(today, today, null, 99L)).thenReturn(List.of());
        when(weekOffUtil.isWeekOff(other, today)).thenReturn(true);

        DashboardDtos.TodayStatus result = service.todayStatus(99L, null);

        assertThat(result.mode()).isEqualTo("BASIC");
        assertThat(result.working()).isEmpty();
        assertThat(result.onLeave()).isEmpty();
        assertThat(result.members()).hasSize(1);
        com.emplmgt.dto.TeamDtos.TeamMemberAvailability member = result.members().get(0);
        assertThat(member.fullName()).isEqualTo("Other Agent");
        assertThat(member.status()).isEqualTo("WEEK_OFF");
        assertThat(member.shift()).isEqualTo("Night");
        assertThat(member.weekOff()).isEqualTo("Sat-Sun");
        assertThat(member.designation()).isNull();
        assertThat(member.avatar()).isNull();
    }
}