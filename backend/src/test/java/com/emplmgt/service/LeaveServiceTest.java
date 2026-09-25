package com.emplmgt.service;

import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.dto.LeaveDtos;
import com.emplmgt.entity.*;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.*;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.LeaveDaysCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    @Mock LeaveRequestRepository leaveRequestRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock UserRepository userRepository;
    @Mock HolidayRepository holidayRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock LeaveBalanceRepository leaveBalanceRepository;
    @Mock SwapOffRequestRepository swapOffRequestRepository;
    @Mock LeaveDaysCalculator daysCalculator;
    @Mock AppClock appClock;
    @Mock NotificationService notificationService;
    @Mock AuditService auditService;
    @Mock EmployeeService employeeService;
    @Mock AttendanceRequestIntegrationService attendanceRequestIntegration;

    private LeaveService service;

    private User adminUser;
    private User employeeUser;
    private Employee employee;

    @BeforeEach
    void setUp() {
        service = new LeaveService(
                leaveRequestRepository, employeeRepository, userRepository, holidayRepository,
                attendanceRepository, leaveBalanceRepository, swapOffRequestRepository,
                daysCalculator, appClock, notificationService, auditService, employeeService,
                attendanceRequestIntegration);

        adminUser = User.builder().id(99L).email("admin@x.com").role(Role.ADMIN).build();
        employeeUser = User.builder().id(1L).email("john@x.com").role(Role.EMPLOYEE).build();
        employee = Employee.builder()
                .id(10L).employeeCode("EMP-001").fullName("John Doe")
                .email("john@x.com").user(employeeUser).employmentStatus(EmploymentStatus.ACTIVE)
                .build();
    }

    @Nested
    class Apply {

        @Test
        void rejectsEndDateBeforeStartDate() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));

            assertThatThrownBy(() ->
                    service.apply(1L, new LeaveDtos.ApplyRequest(
                            LeaveType.PRIVILEGE_LEAVE, LocalDate.of(2026, 9, 18),
                            LocalDate.of(2026, 9, 14), "family event", null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Start date cannot be after end date");
        }

        @Test
        void rejectsLeaveInThePast() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 15));

            assertThatThrownBy(() ->
                    service.apply(1L, new LeaveDtos.ApplyRequest(
                            LeaveType.SICK_LEAVE, LocalDate.of(2026, 9, 10),
                            LocalDate.of(2026, 9, 12), "not feeling well", null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("past");
        }

        @Test
        void rejectsAllWeekendRange() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 10));
            when(holidayRepository.findByHolidayDateBetween(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13)))
                    .thenReturn(List.of());
            when(daysCalculator.countLeaveDays(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13), List.of()))
                    .thenReturn(0L);

            assertThatThrownBy(() ->
                    service.apply(1L, new LeaveDtos.ApplyRequest(
                            LeaveType.PRIVILEGE_LEAVE, LocalDate.of(2026, 9, 12),
                            LocalDate.of(2026, 9, 13), "holiday", null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("no working days");
        }

        @Test
        void rejectsOverlap() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 10));
            when(daysCalculator.countLeaveDays(any(), any(), anyList())).thenReturn(3L);
            when(leaveRequestRepository.existsOverlapping(eq(10L), any(), any())).thenReturn(true);

            assertThatThrownBy(() ->
                    service.apply(1L, new LeaveDtos.ApplyRequest(
                            LeaveType.PRIVILEGE_LEAVE, LocalDate.of(2026, 9, 14),
                            LocalDate.of(2026, 9, 16), "overlap check", null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("overlapping");
        }

        @Test
        void rejectsConflictingAttendance() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 10));
            when(daysCalculator.countLeaveDays(any(), any(), anyList())).thenReturn(2L);
            when(leaveRequestRepository.existsOverlapping(eq(10L), any(), any())).thenReturn(false);
            when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(eq(10L), any(), any()))
                    .thenReturn(List.of(Attendance.builder().attendanceType(AttendanceType.WORK_FROM_HOME).build()));

            assertThatThrownBy(() ->
                    service.apply(1L, new LeaveDtos.ApplyRequest(
                            LeaveType.PRIVILEGE_LEAVE, LocalDate.of(2026, 9, 14),
                            LocalDate.of(2026, 9, 15), "wfh conflict", null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("overlapping these dates");
        }

        @Test
        void savesAndNotifiesOnSuccess() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 10));
            when(holidayRepository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
            when(daysCalculator.countLeaveDays(any(), any(), anyList())).thenReturn(2L);
            when(leaveRequestRepository.existsOverlapping(eq(10L), any(), any())).thenReturn(false);
            when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(eq(10L), any(), any()))
                    .thenReturn(List.of());
            // Enough PL balance
            when(employeeService.balancesFor(eq(employee), anyInt()))
                    .thenReturn(List.of(new EmployeeDtos.LeaveBalanceDto(
                            "PRIVILEGE_LEAVE", "PL", "Privilege Leave", BigDecimal.valueOf(20), BigDecimal.ZERO, BigDecimal.valueOf(20))));
            when(leaveRequestRepository.save(any())).thenAnswer(invocation -> {
                LeaveRequest r = invocation.getArgument(0);
                r.setId(55L);
                return r;
            });

            service.apply(1L, new LeaveDtos.ApplyRequest(
                    LeaveType.PRIVILEGE_LEAVE, LocalDate.of(2026, 9, 14),
                    LocalDate.of(2026, 9, 15), "vacation", null));

            verify(notificationService).notifyAdmins(eq("New Leave Request"), anyString(), eq(NotificationType.LEAVE), eq("/admin/leaves"));
            verify(auditService).record(eq("LEAVE_APPLIED"), eq("LeaveRequest"), eq("55"), isNull(), anyMap());
            verify(leaveRequestRepository).save(any());
        }
    }

    @Nested
    class Cancel {

        @Test
        void canCancelOwnPendingLeave() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            LeaveRequest leave = LeaveRequest.builder()
                    .id(30L).employee(employee).status(LeaveStatus.PENDING)
                    .leaveType(LeaveType.SICK_LEAVE).build();
            when(leaveRequestRepository.findById(30L)).thenReturn(Optional.of(leave));
            when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = service.cancel(1L, 30L);
            assertThat(result.status()).isEqualTo(LeaveStatus.CANCELLED);
            verify(auditService).record(eq("LEAVE_CANCELLED"), eq("LeaveRequest"), eq("30"), anyMap(), anyMap());
        }

        @Test
        void rejectsCancelOfApprovedLeave() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(leaveRequestRepository.findById(30L)).thenReturn(Optional.of(
                    LeaveRequest.builder().id(30L).employee(employee).status(LeaveStatus.APPROVED).build()));

            assertThatThrownBy(() -> service.cancel(1L, 30L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Only pending leaves can be cancelled");
        }

        @Test
        void cannotCancelAnotherEmployeeLeave() {
            Employee other = Employee.builder().id(999L).employmentStatus(EmploymentStatus.ACTIVE).build();
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(leaveRequestRepository.findById(30L)).thenReturn(Optional.of(
                    LeaveRequest.builder().id(30L).employee(other).status(LeaveStatus.PENDING).build()));

            assertThatThrownBy(() -> service.cancel(1L, 30L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Cannot cancel another employee");
        }
    }

    @Nested
    class Approve {

        @Test
        void rejectsApprovingOwnLeave() {
            LeaveRequest leave = LeaveRequest.builder()
                    .id(40L).employee(employee).status(LeaveStatus.PENDING).build();
            when(leaveRequestRepository.findById(40L)).thenReturn(Optional.of(leave));

            assertThatThrownBy(() -> service.approve(employeeUser.getId(), 40L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("cannot approve or reject their own");
        }

        @Test
        void approveRewritesAttendance() {
            LeaveRequest leave = LeaveRequest.builder()
                    .id(41L).employee(employee).status(LeaveStatus.PENDING)
                    .startDate(LocalDate.of(2026, 9, 16)).endDate(LocalDate.of(2026, 9, 16))
                    .leaveType(LeaveType.PRIVILEGE_LEAVE).days(BigDecimal.ONE).build();
            when(leaveRequestRepository.findById(41L)).thenReturn(Optional.of(leave));
            when(userRepository.findById(adminUser.getId())).thenReturn(Optional.of(adminUser));
            when(holidayRepository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
            when(daysCalculator.isWeeklyOff(any())).thenReturn(false);
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(10L), any())).thenReturn(Optional.empty());
            when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(appClock.now()).thenReturn(Instant.now());

            service.approve(adminUser.getId(), 41L);

            verify(attendanceRepository).save(argThat(a ->
                    a.getAttendanceType() == AttendanceType.LEAVE && a.getRemarks() != null));
        }

        @Test
        void rejectRemovesLeaveAttendance() {
            Attendance leaveAtt = Attendance.builder().id(200L).attendanceType(AttendanceType.LEAVE).build();
            when(leaveRequestRepository.findById(42L)).thenReturn(Optional.of(
                    LeaveRequest.builder().id(42L).employee(employee).status(LeaveStatus.PENDING)
                            .startDate(LocalDate.of(2026, 9, 16)).endDate(LocalDate.of(2026, 9, 16))
                            .leaveType(LeaveType.SICK_LEAVE).build()));
            when(userRepository.findById(adminUser.getId())).thenReturn(Optional.of(adminUser));
            when(appClock.now()).thenReturn(Instant.now());
            when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(eq(10L), any(), any()))
                    .thenReturn(List.of(leaveAtt));
            when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.reject(adminUser.getId(), 42L, "Not approved");

            verify(attendanceRepository).delete(leaveAtt);
            assertThat(leaveAtt.getAttendanceType()).isEqualTo(AttendanceType.LEAVE);
        }
    }
}