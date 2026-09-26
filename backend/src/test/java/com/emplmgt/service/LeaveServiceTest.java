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
    @Mock HPEEntitlementService hpeEntitlementService;

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
                attendanceRequestIntegration, hpeEntitlementService);

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
                            LocalDate.of(2026, 9, 14), "family event", null, null)))
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
                            LocalDate.of(2026, 9, 12), "not feeling well", null, null)))
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
                            LocalDate.of(2026, 9, 13), "holiday", null, null)))
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
                            LocalDate.of(2026, 9, 16), "overlap check", null, null)))
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
                            LocalDate.of(2026, 9, 15), "wfh conflict", null, null)))
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
                    LocalDate.of(2026, 9, 15), "vacation", null, null));

            verify(notificationService).notifyAdmins(eq("New Leave Request"), anyString(), eq(NotificationType.LEAVE), eq("/admin/leaves"));
            verify(auditService).record(eq("LEAVE_APPLIED"), eq("LeaveRequest"), eq("55"), isNull(), anyMap());
            verify(leaveRequestRepository).save(any());
        }
    }

    /**
     * Compensatory Off backed by an earned HPE Holiday entitlement: the employee picks which
     * earned holiday they are spending, and the entitlement is only spent on approval.
     */
    @Nested
    class CompOffHpeEntitlement {

        private final LocalDate today = LocalDate.of(2026, 9, 20);
        private final LocalDate offDate = LocalDate.of(2026, 10, 5);
        private final Long entitlementId = 300L;

        private HPEEntitlement entitlement;

        @BeforeEach
        void setup() {
            entitlement = HPEEntitlement.builder()
                    .id(entitlementId)
                    .employee(employee)
                    .holiday(Holiday.builder().id(7L).name("Ganesh Chaturthi")
                            .holidayType(HolidayType.HPE_HOLIDAY)
                            .holidayDate(LocalDate.of(2026, 9, 14)).build())
                    .earnedDate(LocalDate.of(2026, 9, 14))
                    .expiryDate(LocalDate.of(2026, 12, 14))
                    .status(HPEEntitlementStatus.AVAILABLE)
                    .build();
        }

        /** Stubs only the checks that run *before* entitlement resolution. */
        private void givenReachesEntitlementValidation() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(appClock.today()).thenReturn(today);
            when(daysCalculator.countLeaveDays(any(), any(), anyList())).thenReturn(1L);
            when(leaveRequestRepository.existsOverlapping(eq(10L), any(), any())).thenReturn(false);
            when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(eq(10L), any(), any()))
                    .thenReturn(List.of());
        }

        /** Stubs everything a well-formed Compensatory Off application needs. */
        private void givenValidCompOffApplication() {
            givenReachesEntitlementValidation();
            when(hpeEntitlementService.requireUsableForRequest(10L, entitlementId)).thenReturn(entitlement);
            when(leaveRequestRepository.existsActiveRequestForEntitlement(entitlementId)).thenReturn(false);
            when(leaveRequestRepository.sumApprovedDays(10L, LeaveType.COMP_OFF)).thenReturn(BigDecimal.ZERO);
            when(leaveBalanceRepository.findByEmployeeIdAndLeaveTypeAndYear(10L, LeaveType.COMP_OFF, 2026))
                    .thenReturn(Optional.empty());
            when(swapOffRequestRepository.countApprovedCredits(10L, 2026)).thenReturn(0L);
            when(hpeEntitlementService.countUsableEntitlements(10L)).thenReturn(1L);
            when(leaveRequestRepository.save(any())).thenAnswer(invocation -> {
                LeaveRequest r = invocation.getArgument(0);
                r.setId(60L);
                return r;
            });
        }

        private LeaveDtos.ApplyRequest compOffRequest(Long selectedEntitlementId) {
            return new LeaveDtos.ApplyRequest(LeaveType.COMP_OFF, offDate, offDate,
                    "worked on Ganesh Chaturthi", null, selectedEntitlementId);
        }

        @Test
        void rejectsCompOffWithNoEntitlementSelected() {
            givenReachesEntitlementValidation();

            assertThatThrownBy(() -> service.apply(1L, compOffRequest(null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Select the HPE Holiday");

            verify(leaveRequestRepository, never()).save(any());
        }

        @Test
        void rejectsEntitlementOfAnotherEmployee() {
            givenReachesEntitlementValidation();
            when(hpeEntitlementService.requireUsableForRequest(10L, entitlementId))
                    .thenThrow(ApiException.forbidden("Cannot use another employee's entitlement"));

            assertThatThrownBy(() -> service.apply(1L, compOffRequest(entitlementId)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("another employee");

            verify(leaveRequestRepository, never()).save(any());
        }

        @Test
        void rejectsAlreadyUsedEntitlement() {
            givenReachesEntitlementValidation();
            when(hpeEntitlementService.requireUsableForRequest(10L, entitlementId))
                    .thenThrow(ApiException.badRequest("Entitlement has already been used"));

            assertThatThrownBy(() -> service.apply(1L, compOffRequest(entitlementId)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already been used");

            verify(leaveRequestRepository, never()).save(any());
        }

        @Test
        void rejectsExpiredEntitlement() {
            givenReachesEntitlementValidation();
            when(hpeEntitlementService.requireUsableForRequest(10L, entitlementId))
                    .thenThrow(ApiException.badRequest("Entitlement expired on 2026-09-14 "
                            + "and cannot be used after its expiry date"));

            assertThatThrownBy(() -> service.apply(1L, compOffRequest(entitlementId)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("expired");

            verify(leaveRequestRepository, never()).save(any());
        }

        @Test
        void rejectsEntitlementAlreadyAttachedToAnotherLiveRequest() {
            givenReachesEntitlementValidation();
            when(hpeEntitlementService.requireUsableForRequest(10L, entitlementId)).thenReturn(entitlement);
            when(leaveRequestRepository.existsActiveRequestForEntitlement(entitlementId)).thenReturn(true);

            assertThatThrownBy(() -> service.apply(1L, compOffRequest(entitlementId)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already attached to another pending or approved request");

            verify(leaveRequestRepository, never()).save(any());
        }

        @Test
        void rejectsHpeEntitlementOnANonCompOffLeaveType() {
            givenReachesEntitlementValidation();

            assertThatThrownBy(() -> service.apply(1L, new LeaveDtos.ApplyRequest(
                    LeaveType.PRIVILEGE_LEAVE, offDate, offDate, "vacation", null, entitlementId)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("only be applied to Compensatory Off");
        }

        @Test
        void offDateIsIndependentOfTheOriginalHpeHolidayDate() {
            givenValidCompOffApplication();

            LeaveDtos.Response saved = service.apply(1L, compOffRequest(entitlementId));

            // Off date is 05 Oct, the holiday was 14 Sep - both are preserved as given.
            assertThat(saved.startDate()).isEqualTo(offDate);
            assertThat(saved.hpeEntitlementId()).isEqualTo(entitlementId);
        }

        @Test
        void submissionReservesButDoesNotConsumeTheEntitlement() {
            givenValidCompOffApplication();

            service.apply(1L, compOffRequest(entitlementId));

            verify(hpeEntitlementService).reserveForRequest(10L, entitlementId, 60L);
            verify(hpeEntitlementService, never()).consumeReservation(any(), any(), any(), any());
            verify(hpeEntitlementService, never()).useHpeEntitlement(any(), any(), any());
        }

        @Test
        void approvalConsumesTheReservedEntitlement() {
            LeaveRequest leave = LeaveRequest.builder()
                    .id(61L).employee(employee).status(LeaveStatus.PENDING).leaveType(LeaveType.COMP_OFF)
                    .startDate(offDate).endDate(offDate).days(BigDecimal.ONE)
                    .hpeEntitlement(entitlement).build();
            when(leaveRequestRepository.findById(61L)).thenReturn(Optional.of(leave));
            when(userRepository.findById(adminUser.getId())).thenReturn(Optional.of(adminUser));
            when(holidayRepository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
            when(daysCalculator.isWeeklyOff(any())).thenReturn(false);
            when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(10L), any())).thenReturn(Optional.empty());
            when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(appClock.now()).thenReturn(Instant.now());

            service.approve(adminUser.getId(), 61L);

            // off date + deciding admin are both handed to the entitlement so the spend is auditable
            verify(hpeEntitlementService).consumeReservation(entitlementId, 61L, offDate, adminUser.getId());
        }

        @Test
        void rejectionReleasesTheReservedEntitlement() {
            LeaveRequest leave = LeaveRequest.builder()
                    .id(62L).employee(employee).status(LeaveStatus.PENDING).leaveType(LeaveType.COMP_OFF)
                    .startDate(offDate).endDate(offDate).days(BigDecimal.ONE)
                    .hpeEntitlement(entitlement).build();
            when(leaveRequestRepository.findById(62L)).thenReturn(Optional.of(leave));
            when(userRepository.findById(adminUser.getId())).thenReturn(Optional.of(adminUser));
            when(appClock.now()).thenReturn(Instant.now());
            when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(eq(10L), any(), any()))
                    .thenReturn(List.of());
            when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.reject(adminUser.getId(), 62L, "Not needed");

            verify(hpeEntitlementService).releaseReservation(entitlementId, 62L);
            verify(hpeEntitlementService, never()).consumeReservation(any(), any(), any(), any());
        }

        @Test
        void cancellationReleasesTheReservedEntitlement() {
            LeaveRequest leave = LeaveRequest.builder()
                    .id(63L).employee(employee).status(LeaveStatus.PENDING).leaveType(LeaveType.COMP_OFF)
                    .startDate(offDate).endDate(offDate).days(BigDecimal.ONE)
                    .hpeEntitlement(entitlement).build();
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(leaveRequestRepository.findById(63L)).thenReturn(Optional.of(leave));
            when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.cancel(1L, 63L);

            verify(hpeEntitlementService).releaseReservation(entitlementId, 63L);
        }

        @Test
        void leaveTypesWithoutAnEntitlementNeverTouchEntitlements() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(appClock.today()).thenReturn(today);
            when(daysCalculator.countLeaveDays(any(), any(), anyList())).thenReturn(1L);
            when(leaveRequestRepository.existsOverlapping(eq(10L), any(), any())).thenReturn(false);
            when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(eq(10L), any(), any()))
                    .thenReturn(List.of());
            when(employeeService.balancesFor(eq(employee), anyInt()))
                    .thenReturn(List.of(new EmployeeDtos.LeaveBalanceDto("SICK_LEAVE", "SL", "Sick Leave",
                            BigDecimal.valueOf(12), BigDecimal.ZERO, BigDecimal.valueOf(12))));
            when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.apply(1L, new LeaveDtos.ApplyRequest(LeaveType.SICK_LEAVE, offDate, offDate,
                    "not feeling well", null, null));

            verify(hpeEntitlementService, never()).reserveForRequest(any(), any(), any());
            verify(hpeEntitlementService, never()).countUsableEntitlements(any());
        }

        @Test
        void compOffBalanceCountsEarnedHpeEntitlementsSoTheyAreActuallyUsable() {
            // No swap-off credits and no seeded CO allocation: the single earned entitlement is
            // the only thing backing this day, and it must be enough.
            givenValidCompOffApplication();

            service.apply(1L, compOffRequest(entitlementId));

            verify(hpeEntitlementService).countUsableEntitlements(10L);
            verify(leaveRequestRepository).save(any());
        }

        @Test
        void compOffStillNeedsACreditWhenNoEntitlementIsEarned() {            givenReachesEntitlementValidation();
            when(hpeEntitlementService.requireUsableForRequest(10L, entitlementId)).thenReturn(entitlement);
            when(leaveRequestRepository.existsActiveRequestForEntitlement(entitlementId)).thenReturn(false);
            when(leaveRequestRepository.sumApprovedDays(10L, LeaveType.COMP_OFF)).thenReturn(BigDecimal.ZERO);
            when(leaveBalanceRepository.findByEmployeeIdAndLeaveTypeAndYear(10L, LeaveType.COMP_OFF, 2026))
                    .thenReturn(Optional.empty());
            when(swapOffRequestRepository.countApprovedCredits(10L, 2026)).thenReturn(0L);
            when(hpeEntitlementService.countUsableEntitlements(10L)).thenReturn(0L);

            assertThatThrownBy(() -> service.apply(1L, compOffRequest(entitlementId)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Insufficient Compensatory Off balance");

            verify(leaveRequestRepository, never()).save(any());
        }

        @Test
        void compOffBalanceOnTheDashboardIncludesEarnedEntitlements() {
            when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
            when(appClock.today()).thenReturn(today);
            when(employeeService.balancesFor(eq(employee), anyInt())).thenReturn(List.of(
                    new EmployeeDtos.LeaveBalanceDto("COMP_OFF", "CO", "Compensatory Off",
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
            when(swapOffRequestRepository.countApprovedCredits(10L, 2026)).thenReturn(1L);
            when(hpeEntitlementService.countUsableEntitlements(10L)).thenReturn(2L);

            EmployeeDtos.LeaveBalanceDto co = service.myBalances(1L).stream()
                    .filter(b -> "COMP_OFF".equals(b.leaveType()))
                    .findFirst().orElseThrow();

            // 1 approved swap-off credit + 2 earned HPE Holiday entitlements.
            assertThat(co.allocated()).isEqualByComparingTo(BigDecimal.valueOf(3));
            assertThat(co.available()).isEqualByComparingTo(BigDecimal.valueOf(3));
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

        /**
         * The Compensatory Off approval half of the lifecycle: the roster is stamped with the
         * CO status, the entitlement is consumed in the same call, and the off date plus the
         * deciding admin are recorded for the audit trail.
         */
        @Nested
        class CompOffApproval {

            private final LocalDate offDate = LocalDate.of(2026, 10, 5);
            private HPEEntitlement entitlement;

            @BeforeEach
            void setUp() {
                entitlement = HPEEntitlement.builder()
                        .id(300L)
                        .holiday(Holiday.builder().id(70L).name("Ganesh Chaturthi")
                                .holidayDate(LocalDate.of(2026, 9, 14)).build())
                        .earnedDate(LocalDate.of(2026, 9, 14))
                        .expiryDate(LocalDate.of(2026, 12, 14))
                        .status(HPEEntitlementStatus.RESERVED)
                        .reservedRequestId(70L)
                        .build();
            }

            private LeaveRequest pendingCompOff() {
                return LeaveRequest.builder()
                        .id(70L).employee(employee).status(LeaveStatus.PENDING)
                        .leaveType(LeaveType.COMP_OFF)
                        .startDate(offDate).endDate(offDate).days(BigDecimal.ONE)
                        .hpeEntitlement(entitlement).build();
            }

            private void givenApprovableCompOff() {
                when(leaveRequestRepository.findById(70L)).thenReturn(Optional.of(pendingCompOff()));
                when(userRepository.findById(adminUser.getId())).thenReturn(Optional.of(adminUser));
                when(holidayRepository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
                when(daysCalculator.isWeeklyOff(any())).thenReturn(false);
                when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(10L), any())).thenReturn(Optional.empty());
                when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
                when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
                when(appClock.now()).thenReturn(Instant.now());
            }

            @Test
            void approvalWritesTheCompOffStatusToTheRoster() {
                givenApprovableCompOff();

                service.approve(adminUser.getId(), 70L);

                // applyLeaveApproval is the bridge into attendance_records, which is the live
                // roster table - it derives the "CO" code from the leave type itself.
                verify(attendanceRequestIntegration).applyLeaveApproval(argThat(l ->
                        l.getLeaveType() == LeaveType.COMP_OFF
                                && l.getStartDate().equals(offDate)
                                && l.getStatus() == LeaveStatus.APPROVED));
            }

            @Test
            void approvalConsumesTheEntitlementWithTheOffDateAndApprover() {
                givenApprovableCompOff();

                service.approve(adminUser.getId(), 70L);

                verify(hpeEntitlementService).consumeReservation(300L, 70L, offDate, adminUser.getId());
            }

            @Test
            void approvalRecordsTheApproverInTheAuditTrail() {
                givenApprovableCompOff();

                service.approve(adminUser.getId(), 70L);

                verify(auditService).record(eq("LEAVE_APPROVED"), eq("LeaveRequest"), eq("70"),
                        any(), argThat(after -> "APPROVED".equals(after.get("status"))
                                && String.valueOf(adminUser.getId()).equals(after.get("approvedByUserId"))
                                && "300".equals(after.get("hpeEntitlementId"))));
            }

            /**
             * Requirement: a partially failed approval must not leave the entitlement marked
             * USED. consumeReservation runs inside approve's transaction, so when it refuses,
             * the exception propagates out of approve and the whole approval (leave status,
             * roster CO, attendance rows) is rolled back by the transaction manager. The
             * entitlement is never written, so it stays RESERVED and the employee can retry.
             */
            @Test
            void aFailedEntitlementConsumptionAbortsTheWholeApproval() {
                givenApprovableCompOff();
                doThrow(ApiException.conflict("This HPE Holiday entitlement is not reserved for this request"))
                        .when(hpeEntitlementService).consumeReservation(300L, 70L, offDate, adminUser.getId());

                assertThatThrownBy(() -> service.approve(adminUser.getId(), 70L))
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("not reserved for this request");

                // The failure escapes approve(), so Spring rolls the transaction back: the
                // entitlement is not released (it stays RESERVED, still held by this request)
                // and the leave is never treated as a rejection.
                verify(hpeEntitlementService, never()).releaseReservation(any(), any());
            }

            @Test
            void approvingANonCompOffRequestNeverConsumesAnEntitlement() {
                LeaveRequest leave = LeaveRequest.builder()
                        .id(71L).employee(employee).status(LeaveStatus.PENDING)
                        .leaveType(LeaveType.PRIVILEGE_LEAVE)
                        .startDate(offDate).endDate(offDate).days(BigDecimal.ONE).build();
                when(leaveRequestRepository.findById(71L)).thenReturn(Optional.of(leave));
                when(userRepository.findById(adminUser.getId())).thenReturn(Optional.of(adminUser));
                when(holidayRepository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
                when(daysCalculator.isWeeklyOff(any())).thenReturn(false);
                when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(10L), any())).thenReturn(Optional.empty());
                when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
                when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
                when(appClock.now()).thenReturn(Instant.now());

                service.approve(adminUser.getId(), 71L);

                verify(hpeEntitlementService, never()).consumeReservation(any(), any(), any(), any());
            }
        }

        /**
         * Requirement: cancelling after approval. The existing cancellation rules only allow
         * PENDING requests to be cancelled, so an approved Compensatory Off cannot be pulled
         * back - the day was granted (roster CO + attendance) and the entitlement was spent.
         */
        @Nested
        class CancelAfterApproval {

            @Test
            void anApprovedLeaveCannotBeCancelled() {
                LeaveRequest leave = LeaveRequest.builder()
                        .id(80L).employee(employee).status(LeaveStatus.APPROVED)
                        .leaveType(LeaveType.COMP_OFF)
                        .startDate(LocalDate.of(2026, 10, 5)).endDate(LocalDate.of(2026, 10, 5))
                        .days(BigDecimal.ONE)
                        .hpeEntitlement(HPEEntitlement.builder().id(300L).build())
                        .build();
                when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
                when(leaveRequestRepository.findById(80L)).thenReturn(Optional.of(leave));

                assertThatThrownBy(() -> service.cancel(1L, 80L))
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("Only pending leaves can be cancelled");

                // Nothing is written and, critically, the spent entitlement is not released.
                verify(leaveRequestRepository, never()).save(any());
                verify(hpeEntitlementService, never()).releaseReservation(any(), any());
            }

            @Test
            void aRejectedLeaveCannotBeCancelledEither() {
                LeaveRequest leave = LeaveRequest.builder()
                        .id(81L).employee(employee).status(LeaveStatus.REJECTED)
                        .leaveType(LeaveType.COMP_OFF)
                        .startDate(LocalDate.of(2026, 10, 5)).endDate(LocalDate.of(2026, 10, 5))
                        .days(BigDecimal.ONE).build();
                when(employeeRepository.findByUserId(1L)).thenReturn(Optional.of(employee));
                when(leaveRequestRepository.findById(81L)).thenReturn(Optional.of(leave));

                assertThatThrownBy(() -> service.cancel(1L, 81L))
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("Only pending leaves can be cancelled");

                verify(hpeEntitlementService, never()).releaseReservation(any(), any());
            }
        }
    }
}