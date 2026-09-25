package com.emplmgt.service;

import com.emplmgt.dto.SwapOffDtos;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SwapOffService {

    private final SwapOffRequestRepository swapOffRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final HolidayRepository holidayRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveDaysCalculator daysCalculator;
    private final AppClock appClock;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Transactional
    public SwapOffDtos.Response apply(Long userId, SwapOffDtos.ApplyRequest request) {
        Employee employee = employeeFor(userId);
        if (employee.getEmploymentStatus() != EmploymentStatus.ACTIVE) {
            throw ApiException.badRequest("Your account is inactive. Contact an administrator.");
        }
        if (request.workedDate().equals(request.requestedOffDate())) {
            throw ApiException.badRequest("Worked date and requested off date cannot be the same");
        }
        if (request.workedDate().isAfter(appClock.today())) {
            throw ApiException.badRequest("Worked date cannot be in the future");
        }
        if (request.requestedOffDate().isBefore(appClock.today())) {
            throw ApiException.badRequest("Requested off date must be in the future");
        }
        if (!daysCalculator.isWeeklyOff(request.workedDate())
                && holidayRepository.findByHolidayDate(request.workedDate()).isEmpty()) {
            throw ApiException.badRequest("Worked date must be a non-working day (weekly off or holiday)");
        }
        if (request.requestedOffDate().isBefore(appClock.today())) {
            throw ApiException.badRequest("Requested off date cannot be in the past");
        }
        boolean duplicate = swapOffRequestRepository
                .findByEmployeeIdAndStatus(employee.getId(), LeaveStatus.PENDING).stream()
                .anyMatch(s -> s.getWorkedDate().equals(request.workedDate())
                        || s.getRequestedOffDate().equals(request.requestedOffDate()));
        if (duplicate) {
            throw ApiException.conflict("A pending swap-off already exists for these dates");
        }
        if (leaveRequestRepository.existsOverlapping(employee.getId(),
                request.requestedOffDate(), request.requestedOffDate())) {
            throw ApiException.conflict("You already have a pending or approved leave on the requested off date");
        }

        SwapOffRequest swap = SwapOffRequest.builder()
                .employee(employee)
                .workedDate(request.workedDate())
                .requestedOffDate(request.requestedOffDate())
                .reason(request.reason())
                .attachment(request.attachment())
                .status(LeaveStatus.PENDING)
                .build();
        SwapOffRequest saved = swapOffRequestRepository.save(swap);

        notificationService.notifyAdmins("New Swap Off Request",
                employee.getFullName() + " (" + employee.getEmployeeCode() + ") worked on "
                        + request.workedDate() + " and requests " + request.requestedOffDate() + " off.",
                NotificationType.SWAP_OFF, "/admin/swap-offs");
        auditService.record("SWAP_OFF_APPLIED", "SwapOffRequest", String.valueOf(saved.getId()),
                null, Map.of("employee", employee.getFullName(), "workedDate", request.workedDate().toString(),
                        "offDate", request.requestedOffDate().toString()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<SwapOffDtos.Response> myRequests(Long userId) {
        Employee employee = employeeFor(userId);
        return swapOffRequestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public SwapOffDtos.Response cancel(Long userId, Long swapId) {
        Employee employee = employeeFor(userId);
        SwapOffRequest swap = swapOffRequestRepository.findById(swapId)
                .orElseThrow(() -> ApiException.notFound("Swap off request not found"));
        if (!swap.getEmployee().getId().equals(employee.getId())) {
            throw ApiException.forbidden("Cannot cancel another employee's request");
        }
        if (swap.getStatus() != LeaveStatus.PENDING) {
            throw ApiException.badRequest("Only pending swap off requests can be cancelled");
        }
        swap.setStatus(LeaveStatus.CANCELLED);
        SwapOffRequest saved = swapOffRequestRepository.save(swap);
        auditService.record("SWAP_OFF_CANCELLED", "SwapOffRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "CANCELLED"));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<SwapOffDtos.Response> search(LeaveStatus status, Long employeeId, Long departmentId,
                                             java.time.Instant from, java.time.Instant to,
                                             String search, Pageable pageable) {
        return swapOffRequestRepository.search(status, employeeId, departmentId, from, to, search, pageable)
                .map(this::toResponse);
    }

    @Transactional
    public SwapOffDtos.Response approve(Long adminUserId, Long swapId) {
        SwapOffRequest swap = loadPending(swapId);
        ensureNotSelfDecision(swap, adminUserId);
        swap.setStatus(LeaveStatus.APPROVED);
        swap.setCompOffCredited(true);
        swap.setDecidedBy(userRepository.findById(adminUserId).orElseThrow());
        swap.setDecidedAt(appClock.now());
        creditAttendance(swap);
        SwapOffRequest saved = swapOffRequestRepository.save(swap);

        if (saved.getEmployee().getUser() != null) {
            notificationService.notifyUser(saved.getEmployee().getUser().getId(), "Swap Off Approved",
                    "Your swap off request (worked " + saved.getWorkedDate() + ", off "
                            + saved.getRequestedOffDate() + ") has been approved. A Compensatory Off has been credited.",
                    NotificationType.SWAP_OFF, "/swap-off");
        }
        auditService.record("SWAP_OFF_APPROVED", "SwapOffRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "APPROVED", "compOffCredited", true));
        return toResponse(saved);
    }

    @Transactional
    public SwapOffDtos.Response reject(Long adminUserId, Long swapId, String rejectionReason) {
        SwapOffRequest swap = loadPending(swapId);
        ensureNotSelfDecision(swap, adminUserId);
        swap.setStatus(LeaveStatus.REJECTED);
        swap.setRejectionReason(rejectionReason);
        swap.setDecidedBy(userRepository.findById(adminUserId).orElseThrow());
        swap.setDecidedAt(appClock.now());
        removeCreditedAttendance(swap);
        SwapOffRequest saved = swapOffRequestRepository.save(swap);

        if (saved.getEmployee().getUser() != null) {
            notificationService.notifyUser(saved.getEmployee().getUser().getId(), "Swap Off Rejected",
                    "Your swap off request (worked " + saved.getWorkedDate() + ", off "
                            + saved.getRequestedOffDate() + ") was rejected."
                            + (rejectionReason == null || rejectionReason.isBlank() ? "" : " Reason: " + rejectionReason),
                    NotificationType.SWAP_OFF, "/swap-off");
        }
        auditService.record("SWAP_OFF_REJECTED", "SwapOffRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "REJECTED", "reason", rejectionReason));
        return toResponse(saved);
    }

    private void creditAttendance(SwapOffRequest swap) {
        // Worked day -> mark as work from office attendance (source: IMPORT derived from approval).
        attendanceRepository.findByEmployeeIdAndAttendanceDate(swap.getEmployee().getId(), swap.getWorkedDate())
                .ifPresentOrElse(existing -> {
                    if (existing.getAttendanceType() == AttendanceType.WEEK_OFF
                            || existing.getAttendanceType() == AttendanceType.HOLIDAY) {
                        existing.setAttendanceType(AttendanceType.WORK_FROM_OFFICE);
                        existing.setRemarks("Swap off worked day");
                        attendanceRepository.save(existing);
                    }
                }, () -> attendanceRepository.save(Attendance.builder()
                        .employee(swap.getEmployee())
                        .attendanceDate(swap.getWorkedDate())
                        .attendanceType(AttendanceType.WORK_FROM_OFFICE)
                        .source(AttendanceSource.SYSTEM)
                        .remarks("Swap off worked day")
                        .build()));
        // Requested off date -> mark as COMP_OFF if no leave/attendance already there.
        attendanceRepository.findByEmployeeIdAndAttendanceDate(swap.getEmployee().getId(), swap.getRequestedOffDate())
                .ifPresentOrElse(existing -> {
                    if (existing.getAttendanceType() == AttendanceType.WORK_FROM_OFFICE
                            || existing.getAttendanceType() == AttendanceType.WORK_FROM_HOME) {
                        existing.setAttendanceType(AttendanceType.COMP_OFF);
                        existing.setRemarks("Compensatory off");
                        attendanceRepository.save(existing);
                    }
                }, () -> attendanceRepository.save(Attendance.builder()
                        .employee(swap.getEmployee())
                        .attendanceDate(swap.getRequestedOffDate())
                        .attendanceType(AttendanceType.COMP_OFF)
                        .source(AttendanceSource.SYSTEM)
                        .remarks("Compensatory off")
                        .build()));
    }

    private void removeCreditedAttendance(SwapOffRequest swap) {
        attendanceRepository.findByEmployeeIdAndAttendanceDate(swap.getEmployee().getId(), swap.getWorkedDate())
                .ifPresent(existing -> {
                    if (existing.getRemarks() != null && existing.getRemarks().contains("Swap off worked day")) {
                        attendanceRepository.delete(existing);
                    }
                });
        attendanceRepository.findByEmployeeIdAndAttendanceDate(swap.getEmployee().getId(), swap.getRequestedOffDate())
                .ifPresent(existing -> {
                    if (existing.getRemarks() != null && existing.getRemarks().contains("Compensatory off")) {
                        attendanceRepository.delete(existing);
                    }
                });
    }

    private void ensureNotSelfDecision(SwapOffRequest swap, Long adminUserId) {
        if (swap.getEmployee().getUser() != null
                && Objects.equals(swap.getEmployee().getUser().getId(), adminUserId)) {
            throw ApiException.badRequest("An administrator cannot approve or reject their own swap off request");
        }
    }

    private SwapOffRequest loadPending(Long swapId) {
        SwapOffRequest swap = swapOffRequestRepository.findById(swapId)
                .orElseThrow(() -> ApiException.notFound("Swap off request not found"));
        if (swap.getStatus() != LeaveStatus.PENDING) {
            throw ApiException.conflict("Only pending swap off requests can be acted on");
        }
        return swap;
    }

    private Employee employeeFor(Long userId) {
        return employeeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Employee profile not found"));
    }

    public SwapOffDtos.Response toResponse(SwapOffRequest s) {
        return new SwapOffDtos.Response(
                s.getId(),
                s.getEmployee() != null ? s.getEmployee().getId() : null,
                s.getEmployee() != null ? s.getEmployee().getEmployeeCode() : null,
                s.getEmployee() != null ? s.getEmployee().getFullName() : null,
                s.getEmployee() != null && s.getEmployee().getDepartment() != null ? s.getEmployee().getDepartment().getName() : null,
                s.getWorkedDate(),
                s.getRequestedOffDate(),
                s.getReason(),
                s.getAttachment(),
                s.getStatus(),
                s.getRejectionReason(),
                Boolean.TRUE.equals(s.getCompOffCredited()),
                s.getCreatedAt(),
                s.getDecidedAt());
    }
}