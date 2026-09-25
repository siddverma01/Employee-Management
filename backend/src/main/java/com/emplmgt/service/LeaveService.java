package com.emplmgt.service;

import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.dto.LeaveDtos;
import com.emplmgt.entity.*;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.*;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.LeaveDaysCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LeaveService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final HolidayRepository holidayRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final SwapOffRequestRepository swapOffRequestRepository;
    private final LeaveDaysCalculator daysCalculator;
    private final AppClock appClock;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final EmployeeService employeeService;
    private final AttendanceRequestIntegrationService attendanceRequestIntegration;

    // ------------------------------------------------------------------ EMPLOYEE

    @Transactional
    public LeaveDtos.Response apply(Long userId, LeaveDtos.ApplyRequest request) {
        Employee employee = employeeFor(userId);
        if (employee.getEmploymentStatus() != EmploymentStatus.ACTIVE) {
            throw ApiException.badRequest("Your account is inactive. Contact an administrator.");
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("Start date cannot be after end date");
        }
        if (request.startDate().isBefore(appClock.today())) {
            throw ApiException.badRequest("Cannot apply for leave in the past");
        }

        long days = daysCalculator.countLeaveDays(request.startDate(), request.endDate(),
                holidayRepository.findByHolidayDateBetween(request.startDate(), request.endDate()));
        if (days <= 0) {
            throw ApiException.badRequest("Selected dates contain no working days (all are weekends/holidays)");
        }
        if (leaveRequestRepository.existsOverlapping(employee.getId(), request.startDate(), request.endDate())) {
            throw ApiException.conflict("You already have a pending or approved leave overlapping these dates");
        }
        if (hasConflictingAttendance(employee, request.startDate(), request.endDate())) {
            throw ApiException.conflict("You have a scheduled work-from-* or comp-off record overlapping these dates");
        }
        validateBalance(employee, request.leaveType(), BigDecimal.valueOf(days));

        LeaveRequest leave = LeaveRequest.builder()
                .employee(employee)
                .leaveType(request.leaveType())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .days(BigDecimal.valueOf(days))
                .reason(request.reason())
                .attachment(request.attachment())
                .status(LeaveStatus.PENDING)
                .build();
        LeaveRequest saved = leaveRequestRepository.save(leave);

        notificationService.notifyAdmins(
                "New Leave Request",
                employee.getFullName() + " (" + employee.getEmployeeCode() + ") applied for "
                        + request.leaveType().getLabel() + " from " + request.startDate() + " to " + request.endDate()
                        + " (" + days + " day(s)).",
                NotificationType.LEAVE, "/admin/leaves");

        auditService.record("LEAVE_APPLIED", "LeaveRequest", String.valueOf(saved.getId()),
                null, Map.of("employee", employee.getFullName(), "type", request.leaveType().name(),
                        "from", request.startDate().toString(), "to", request.endDate().toString(), "days", days));

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<LeaveDtos.Response> myLeaves(Long userId) {
        Employee employee = employeeFor(userId);
        return leaveRequestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<EmployeeDtos.LeaveBalanceDto> myBalances(Long userId) {
        Employee employee = employeeFor(userId);
        int year = appClock.today().getYear();
        return employeeService.balancesFor(employee, year).stream().map(b -> {
            if ("COMP_OFF".equals(b.leaveType())) {
                long credits = swapOffRequestRepository.countApprovedCredits(employee.getId(), year);
                BigDecimal allocated = b.allocated().add(BigDecimal.valueOf(credits));
                return new EmployeeDtos.LeaveBalanceDto(b.leaveType(), b.leaveTypeCode(), b.leaveTypeLabel(),
                        allocated, b.used(), allocated.subtract(b.used()));
            }
            return b;
        }).toList();
    }

    @Transactional
    public LeaveDtos.Response cancel(Long userId, Long leaveId) {
        Employee employee = employeeFor(userId);
        LeaveRequest leave = leaveRequestRepository.findById(leaveId)
                .orElseThrow(() -> ApiException.notFound("Leave request not found"));
        if (!leave.getEmployee().getId().equals(employee.getId())) {
            throw ApiException.forbidden("Cannot cancel another employee's leave");
        }
        if (leave.getStatus() == LeaveStatus.APPROVED || leave.getStatus() == LeaveStatus.REJECTED) {
            throw ApiException.badRequest("Only pending leaves can be cancelled");
        }
        leave.setStatus(LeaveStatus.CANCELLED);
        LeaveRequest saved = leaveRequestRepository.save(leave);
        auditService.record("LEAVE_CANCELLED", "LeaveRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "CANCELLED"));
        return toResponse(saved);
    }

    // ------------------------------------------------------------------ ADMIN

    @Transactional(readOnly = true)
    public Page<LeaveDtos.Response> search(LeaveStatus status, LeaveType leaveType, Long employeeId, Long departmentId,
                                           LocalDate from, LocalDate to, String search, Pageable pageable) {
        return leaveRequestRepository.search(status, leaveType, employeeId, departmentId, from, to, search, pageable)
                .map(this::toResponse);
    }

    @Transactional
    public LeaveDtos.Response approve(Long adminUserId, Long leaveId) {
        LeaveRequest leave = loadPending(leaveId);
        ensureNotSelfDecision(leave, adminUserId);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setDecidedBy(userRepository.findById(adminUserId).orElseThrow());
        leave.setDecidedAt(appClock.now());
        rewriteAttendanceFor(leave);
        attendanceRequestIntegration.applyLeaveApproval(leave);
        LeaveRequest saved = leaveRequestRepository.save(leave);

        notifyEmployee(saved, "Leave Approved",
                "Your " + saved.getLeaveType().getLabel() + " request (" + saved.getStartDate() + " to "
                        + saved.getEndDate() + ") has been approved.", NotificationType.LEAVE, "/leaves");
        auditService.record("LEAVE_APPROVED", "LeaveRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "APPROVED", "days", saved.getDays()));
        return toResponse(saved);
    }

    @Transactional
    public LeaveDtos.Response reject(Long adminUserId, Long leaveId, String rejectionReason) {
        LeaveRequest leave = loadPending(leaveId);
        ensureNotSelfDecision(leave, adminUserId);
        leave.setStatus(LeaveStatus.REJECTED);
        leave.setRejectionReason(rejectionReason);
        leave.setDecidedBy(userRepository.findById(adminUserId).orElseThrow());
        leave.setDecidedAt(appClock.now());
        removeLeaveAttendanceFor(leave);
        LeaveRequest saved = leaveRequestRepository.save(leave);

        notifyEmployee(saved, "Leave Rejected",
                "Your " + saved.getLeaveType().getLabel() + " request (" + saved.getStartDate() + " to "
                        + saved.getEndDate() + ") was rejected."
                        + (rejectionReason == null || rejectionReason.isBlank() ? "" : " Reason: " + rejectionReason),
                NotificationType.LEAVE, "/leaves");
        auditService.record("LEAVE_REJECTED", "LeaveRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "REJECTED", "reason", rejectionReason));
        return toResponse(saved);
    }

    // ------------------------------------------------------------------ HELPERS

    private void notifyEmployee(LeaveRequest leave, String title, String body, NotificationType type, String link) {
        if (leave.getEmployee().getUser() != null) {
            notificationService.notifyUser(leave.getEmployee().getUser().getId(), title, body, type, link);
        }
    }

    private void ensureNotSelfDecision(LeaveRequest leave, Long adminUserId) {
        if (leave.getEmployee().getUser() != null
                && Objects.equals(leave.getEmployee().getUser().getId(), adminUserId)) {
            throw ApiException.badRequest("An administrator cannot approve or reject their own leave request");
        }
    }

    private LeaveRequest loadPending(Long leaveId) {
        LeaveRequest leave = leaveRequestRepository.findById(leaveId)
                .orElseThrow(() -> ApiException.notFound("Leave request not found"));
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw ApiException.conflict("Only pending leave requests can be acted on");
        }
        return leave;
    }

    private void validateBalance(Employee employee, LeaveType type, BigDecimal requestedDays) {
        int year = appClock.today().getYear();
        BigDecimal available;
        if (type == LeaveType.COMP_OFF) {
            long credits = swapOffRequestRepository.countApprovedCredits(employee.getId(), year);
            BigDecimal allocated = coAllocated(employee, year).add(BigDecimal.valueOf(credits));
            BigDecimal used = leaveRequestRepository.sumApprovedDays(employee.getId(), LeaveType.COMP_OFF);
            available = allocated.subtract(used);
        } else {
            available = employeeService.balancesFor(employee, year).stream()
                    .filter(b -> type.name().equals(b.leaveType()))
                    .map(EmployeeDtos.LeaveBalanceDto::available)
                    .findFirst().orElse(BigDecimal.ZERO);
        }
        if (available.compareTo(requestedDays) < 0) {
            throw ApiException.badRequest("Insufficient " + type.getLabel() + " balance");
        }
    }

    private BigDecimal coAllocated(Employee employee, int year) {
        return leaveBalanceRepository.findByEmployeeIdAndLeaveTypeAndYear(employee.getId(), LeaveType.COMP_OFF, year)
                .map(LeaveBalance::getAllocated).orElse(BigDecimal.ZERO);
    }

    private boolean hasConflictingAttendance(Employee employee, LocalDate start, LocalDate end) {
        List<Attendance> rows = attendanceRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
                employee.getId(), start, end);
        return rows.stream().anyMatch(a -> a.getAttendanceType() == AttendanceType.WORK_FROM_HOME
                || a.getAttendanceType() == AttendanceType.WORK_FROM_OFFICE
                || a.getAttendanceType() == AttendanceType.COMP_OFF);
    }

    private void rewriteAttendanceFor(LeaveRequest leave) {
        List<Holiday> holidays = holidayRepository.findByHolidayDateBetween(leave.getStartDate(), leave.getEndDate());
        AttendanceType attendanceType = leave.getLeaveType() == LeaveType.COMP_OFF
                ? AttendanceType.COMP_OFF : AttendanceType.LEAVE;
        for (LocalDate date = leave.getStartDate(); !date.isAfter(leave.getEndDate()); date = date.plusDays(1)) {
            LocalDate day = date;
            if (daysCalculator.isWeeklyOff(day)) {
                continue;
            }
            if (holidays.stream().anyMatch(h -> h.getHolidayDate().equals(day))) {
                continue;
            }
            attendanceRepository.findByEmployeeIdAndAttendanceDate(leave.getEmployee().getId(), day)
                    .ifPresentOrElse(existing -> {
                        if (existing.getAttendanceType() != attendanceType) {
                            existing.setAttendanceType(attendanceType);
                            existing.setRemarks("Leave: " + leave.getLeaveType().getLabel());
                            attendanceRepository.save(existing);
                        }
                    }, () -> attendanceRepository.save(Attendance.builder()
                            .employee(leave.getEmployee())
                            .attendanceDate(day)
                            .attendanceType(attendanceType)
                            .source(AttendanceSource.SYSTEM)
                            .remarks("Leave: " + leave.getLeaveType().getLabel())
                            .build()));
        }
    }

    private void removeLeaveAttendanceFor(LeaveRequest leave) {
        attendanceRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
                        leave.getEmployee().getId(), leave.getStartDate(), leave.getEndDate())
                .stream()
                .filter(a -> a.getAttendanceType() == AttendanceType.LEAVE
                        || a.getAttendanceType() == AttendanceType.COMP_OFF)
                .forEach(attendanceRepository::delete);
    }

    private Employee employeeFor(Long userId) {
        return employeeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Employee profile not found"));
    }

    public LeaveDtos.Response toResponse(LeaveRequest l) {
        return new LeaveDtos.Response(
                l.getId(),
                l.getEmployee() != null ? l.getEmployee().getId() : null,
                l.getEmployee() != null ? l.getEmployee().getEmployeeCode() : null,
                l.getEmployee() != null ? l.getEmployee().getFullName() : null,
                l.getEmployee() != null && l.getEmployee().getDepartment() != null ? l.getEmployee().getDepartment().getName() : null,
                l.getLeaveType(),
                l.getLeaveType().getCode(),
                l.getLeaveType().getLabel(),
                l.getStartDate(),
                l.getEndDate(),
                l.getDays(),
                l.getReason(),
                l.getAttachment(),
                l.getStatus(),
                l.getRejectionReason(),
                l.getCreatedAt(),
                l.getDecidedAt());
    }
}