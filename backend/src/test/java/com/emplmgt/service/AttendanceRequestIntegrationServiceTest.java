package com.emplmgt.service;

import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.HPEEntitlement;
import com.emplmgt.entity.HPEEntitlementStatus;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.LeaveRequest;
import com.emplmgt.entity.LeaveStatus;
import com.emplmgt.entity.LeaveType;
import com.emplmgt.entity.User;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.repository.LeaveRequestRepository;
import com.emplmgt.repository.SwapOffRequestRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.LeaveDaysCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceRequestIntegrationServiceTest {

    private static final LocalDate HPE_DATE = LocalDate.of(2025, 9, 14);
    private static final LocalDate OFF_DATE = LocalDate.of(2025, 9, 22);

    @Mock AttendanceRecordRepository recordRepository;
    @Mock ImportEmployeeRepository importEmployeeRepository;
    @Mock LeaveRequestRepository leaveRequestRepository;
    @Mock SwapOffRequestRepository swapOffRequestRepository;
    @Mock HolidayRepository holidayRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock UserRepository userRepository;
    @Mock LeaveDaysCalculator daysCalculator;
    @Mock AppClock appClock;

    AttendanceRequestIntegrationService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceRequestIntegrationService(recordRepository, importEmployeeRepository,
                leaveRequestRepository, swapOffRequestRepository, holidayRepository,
                employeeRepository, userRepository, daysCalculator, appClock);
        when(holidayRepository.findByHolidayDateBetween(any(), any())).thenReturn(List.of());
        when(daysCalculator.isWeeklyOff(any())).thenReturn(false);
        when(importEmployeeRepository.existsById(anyString())).thenReturn(true);
        when(appClock.now()).thenReturn(Instant.parse("2025-09-01T00:00:00Z"));
        when(recordRepository.findByEmployeeIdAndAttendanceDate(anyString(), any()))
                .thenReturn(Optional.empty());
        when(recordRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private Employee employee(String code) {
        Employee e = new Employee();
        e.setEmployeeCode(code);
        e.setFullName("Employee " + code);
        return e;
    }

    private Holiday holiday(String name, LocalDate date) {
        Holiday h = new Holiday();
        h.setName(name);
        h.setHolidayDate(date);
        return h;
    }

    /** Approved HPE-backed compensatory off request for {@code emp}. */
    private LeaveRequest compOffRequest(Employee emp, Holiday hpe, Long requestId) {
        HPEEntitlement ent = new HPEEntitlement();
        ent.setId(10L + requestId);
        ent.setEmployee(emp);
        ent.setHoliday(hpe);
        ent.setStatus(HPEEntitlementStatus.USED);
        ent.setUsedDate(LocalDate.of(2025, 9, 1));
        ent.setUsedOffDate(OFF_DATE);
        ent.setUsedRequestId(requestId);

        LeaveRequest leave = new LeaveRequest();
        leave.setId(requestId);
        leave.setEmployee(emp);
        leave.setLeaveType(LeaveType.COMP_OFF);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setStartDate(OFF_DATE);
        leave.setEndDate(OFF_DATE);
        leave.setReason("Compensatory off earned from " + (hpe == null ? "HPE" : hpe.getName()));
        leave.setHpeEntitlement(ent);
        return leave;
    }

    // ------------------------------------------------------------------ WRITE

    @Test
    void applyLeaveApproval_writesCoOnRequestedOffDate_only() {
        Employee emp = employee("E1");
        LeaveRequest leave = compOffRequest(emp, holiday("Ganesh Chaturthi", HPE_DATE), 1L);

        service.applyLeaveApproval(leave);

        ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
        verify(recordRepository).save(captor.capture());
        AttendanceRecord rec = captor.getValue();
        assertThat(rec.getAttendanceDate()).isEqualTo(OFF_DATE);
        assertThat(rec.getStatusCode()).isEqualTo("CO");
        assertThat(rec.getSourceRequestType())
                .isEqualTo(AttendanceRequestIntegrationService.SOURCE_TYPE_LEAVE);
        // The manual description field is never touched by approval writes.
        assertThat(rec.getDescription()).isNull();
    }

    @Test
    void applyLeaveApproval_doesNotWriteCoOnTheOriginalHpeHolidayDate() {
        Employee emp = employee("E1");
        // Requested off date IS the HPE holiday date -> existing rules skip holidays.
        when(holidayRepository.findByHolidayDateBetween(any(), any()))
                .thenReturn(List.of(holiday("Ganesh Chaturthi", HPE_DATE)));
        LeaveRequest leave = compOffRequest(emp, holiday("Ganesh Chaturthi", HPE_DATE), 1L);
        leave.setStartDate(HPE_DATE);
        leave.setEndDate(HPE_DATE);

        service.applyLeaveApproval(leave);

        verify(recordRepository, never()).save(any(AttendanceRecord.class));
    }

    @Test
    void applyLeaveApproval_keepsEmployeesIsolatedAcrossMultipleEntitlements() {
        Employee alice = employee("E1");
        Employee bob = employee("E2");
        LeaveRequest a = compOffRequest(alice, holiday("Ganesh Chaturthi", HPE_DATE), 1L);
        LeaveRequest b = compOffRequest(bob, holiday("Independence Day", LocalDate.of(2025, 7, 4)), 2L);

        service.applyLeaveApproval(a);
        service.applyLeaveApproval(b);

        ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
        verify(recordRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<AttendanceRecord> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(AttendanceRecord::getEmployeeId)
                .containsExactlyInAnyOrder("E1", "E2");
        // Both are CO on their own requested off date, never cross-assigned.
        assertThat(saved).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo("CO"));
        assertThat(saved).extracting(AttendanceRecord::getAttendanceDate)
                .containsExactlyInAnyOrder(OFF_DATE, OFF_DATE);
    }

    // ------------------------------------------------------------------- READ

    @Test
    void resolveSource_exposesHpeHolidayNameAndDateForHpeCompOff() {
        Employee emp = employee("E1");
        User approver = new User();
        approver.setId(99L);
        approver.setEmail("manager@example.com");
        LeaveRequest leave = compOffRequest(emp, holiday("Ganesh Chaturthi", HPE_DATE), 1L);
        leave.setDecidedBy(approver);
        leave.setDecidedAt(Instant.parse("2025-09-01T10:00:00Z"));
        when(employeeRepository.findByUserId(99L)).thenReturn(Optional.empty());
        when(userRepository.findById(99L)).thenReturn(Optional.of(approver));
        when(leaveRequestRepository.findById(1L)).thenReturn(Optional.of(leave));

        var src = service.resolveSource("E1", OFF_DATE, "CO", 1L,
                AttendanceRequestIntegrationService.SOURCE_TYPE_LEAVE);

        assertThat(src).isNotNull();
        assertThat(src.hpeHolidayName()).isEqualTo("Ganesh Chaturthi");
        assertThat(src.hpeHolidayDate()).isEqualTo(HPE_DATE);
        assertThat(src.approvedByName()).isEqualTo("manager@example.com");
        assertThat(src.reason()).contains("Ganesh Chaturthi");
    }

    @Test
    void resolveSource_leavesHpeHolidayNullForNonHpeLeave() {
        Employee emp = employee("E1");
        LeaveRequest leave = compOffRequest(emp, null, 5L);
        leave.setHpeEntitlement(null);
        when(leaveRequestRepository.findById(5L)).thenReturn(Optional.of(leave));

        var src = service.resolveSource("E1", OFF_DATE, "CO", 5L,
                AttendanceRequestIntegrationService.SOURCE_TYPE_LEAVE);

        assertThat(src).isNotNull();
        assertThat(src.hpeHolidayName()).isNull();
        assertThat(src.hpeHolidayDate()).isNull();
    }

    @Test
    void resolveSource_ignoresNonApprovedLeave() {
        Employee emp = employee("E1");
        LeaveRequest leave = compOffRequest(emp, holiday("Ganesh Chaturthi", HPE_DATE), 1L);
        leave.setStatus(LeaveStatus.PENDING);
        when(leaveRequestRepository.findById(1L)).thenReturn(Optional.of(leave));
        when(leaveRequestRepository.findApprovedByCodeAndDate(any(), anyString(), any()))
                .thenReturn(List.of());

        assertThat(service.resolveSource("E1", OFF_DATE, "CO", 1L,
                AttendanceRequestIntegrationService.SOURCE_TYPE_LEAVE)).isNull();
    }
}
