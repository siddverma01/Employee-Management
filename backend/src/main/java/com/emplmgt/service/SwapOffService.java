package com.emplmgt.service;

import com.emplmgt.dto.SwapOffDtos;
import com.emplmgt.entity.*;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.*;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.LeaveDaysCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
    private final AttendanceRequestIntegrationService attendanceRequestIntegration;

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
        // Validate workedForEmployee by employee code
        Employee workedForEmployee = employeeRepository.findByEmployeeCodeIgnoreCase(request.workedForEmployeeCode())
                .orElseThrow(() -> ApiException.badRequest("Selected employee not found"));
        if (workedForEmployee.getId().equals(employee.getId())) {
            throw ApiException.badRequest("You cannot work on behalf of yourself");
        }
        if (workedForEmployee.getEmploymentStatus() != EmploymentStatus.ACTIVE) {
            throw ApiException.badRequest("Selected employee is not active");
        }
        // Check for duplicate pending requests for the same dates involving these employees
        boolean duplicate = swapOffRequestRepository
                .findByEmployeeIdAndStatus(employee.getId(), LeaveStatus.PENDING).stream()
                .anyMatch(s -> s.getWorkedDate().equals(request.workedDate())
                        || s.getRequestedOffDate().equals(request.requestedOffDate()));
        if (duplicate) {
            throw ApiException.conflict("A pending swap-off already exists for these dates");
        }
        // Check if the worked-for employee already has a pending/approved leave on requested off date
        if (leaveRequestRepository.existsOverlapping(workedForEmployee.getId(),
                request.requestedOffDate(), request.requestedOffDate())) {
            throw ApiException.conflict("The employee you're working for already has a pending or approved leave on the requested off date");
        }
        // Check if the requester already has a pending/approved leave on requested off date
        if (leaveRequestRepository.existsOverlapping(employee.getId(),
                request.requestedOffDate(), request.requestedOffDate())) {
            throw ApiException.conflict("You already have a pending or approved leave on the requested off date");
        }
        // Check if the requester already has a swap off as worked-for on the requested off date
        if (swapOffRequestRepository.existsApprovedForEmployeeAndDate(workedForEmployee.getId(), request.requestedOffDate())) {
            throw ApiException.conflict("The employee you're working for already has an approved swap off for the requested off date");
        }

        SwapOffRequest swap = SwapOffRequest.builder()
                .employee(employee)
                .workedForEmployee(workedForEmployee)
                .workedDate(request.workedDate())
                .requestedOffDate(request.requestedOffDate())
                .reason(request.reason())
                .attachment(request.attachment())
                .status(LeaveStatus.PENDING)
                .build();
        SwapOffRequest saved = swapOffRequestRepository.save(swap);

        notificationService.notifyAdmins("New Swap Off Request",
                employee.getFullName() + " (" + employee.getEmployeeCode() + ") worked on behalf of "
                        + workedForEmployee.getFullName() + " (" + workedForEmployee.getEmployeeCode() + ") on "
                        + request.workedDate() + ", requesting " + request.requestedOffDate() + " off for " + workedForEmployee.getFullName() + ".",
                NotificationType.SWAP_OFF, "/admin/swap-offs");
        auditService.record("SWAP_OFF_APPLIED", "SwapOffRequest", String.valueOf(saved.getId()),
                null, Map.of("requester", employee.getFullName(), "workedFor", workedForEmployee.getFullName(),
                        "workedDate", request.workedDate().toString(),
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
        // Fetch all matching records (without search filter) then filter in Java
        // This is a workaround for the search parameter binding issue with multiple parameters
        String searchParam = search != null ? search.toLowerCase() : "";
        // Convert LeaveStatus to string for native query
        String statusParam = status != null ? status.name() : null;
        int page = pageable.getPageNumber();
        int size = pageable.getPageSize();
        int offset = page * size;
        // Pass from/to directly (can be null - COALESCE in query handles it)
        List<SwapOffRequest> content = swapOffRequestRepository.searchWithoutSearchParamNative(statusParam, employeeId, departmentId, from, to, size, offset);
        long total = swapOffRequestRepository.countWithoutSearchParam(statusParam, employeeId, departmentId, from, to);
        
        List<SwapOffDtos.Response> filtered = content.stream()
                .filter(s -> searchParam.isEmpty() 
                        || (s.getEmployee() != null && s.getEmployee().getFullName() != null && s.getEmployee().getFullName().toLowerCase().contains(searchParam))
                        || (s.getWorkedForEmployee() != null && s.getWorkedForEmployee().getFullName() != null && s.getWorkedForEmployee().getFullName().toLowerCase().contains(searchParam))
                        || (s.getEmployee() != null && s.getEmployee().getEmployeeCode() != null && s.getEmployee().getEmployeeCode().toLowerCase().contains(searchParam))
                        || (s.getWorkedForEmployee() != null && s.getWorkedForEmployee().getEmployeeCode() != null && s.getWorkedForEmployee().getEmployeeCode().toLowerCase().contains(searchParam))
                        || (s.getReason() != null && s.getReason().toLowerCase().contains(searchParam)))
                .map(this::toResponse)
                .toList();
        
        // Apply pagination manually
        int start = (int) Math.min(pageable.getOffset(), filtered.size());
        int end = (int) Math.min(start + pageable.getPageSize(), filtered.size());
        List<SwapOffDtos.Response> pagedContent = filtered.subList(start, end);
        
        return new PageImpl<>(pagedContent, pageable, filtered.size());
    }

    @Transactional
    public SwapOffDtos.Response approve(Long adminUserId, Long swapId) {
        SwapOffRequest swap = loadPending(swapId);
        ensureNotSelfDecision(swap, adminUserId);
        swap.setStatus(LeaveStatus.APPROVED);
        swap.setDecidedBy(userRepository.findById(adminUserId).orElseThrow());
        swap.setDecidedAt(appClock.now());
        // Update attendance records for both employees
        updateAttendanceForApproval(swap);
        attendanceRequestIntegration.applySwapOffApproval(swap);
        SwapOffRequest saved = swapOffRequestRepository.save(swap);

        if (saved.getEmployee().getUser() != null) {
            notificationService.notifyUser(saved.getEmployee().getUser().getId(), "Swap Off Approved",
                    "Your swap off request (worked " + saved.getWorkedDate() + " on behalf of "
                            + saved.getWorkedForEmployee().getFullName() + ", off "
                            + saved.getRequestedOffDate() + " for " + saved.getWorkedForEmployee().getFullName() + ") has been approved.",
                    NotificationType.SWAP_OFF, "/swap-off");
        }
        // Also notify the worked-for employee
        if (saved.getWorkedForEmployee().getUser() != null) {
            notificationService.notifyUser(saved.getWorkedForEmployee().getUser().getId(), "Swap Off Approved",
                    saved.getEmployee().getFullName() + " worked on your behalf on " + saved.getWorkedDate()
                            + ". You have been granted a swap off on " + saved.getRequestedOffDate() + ".",
                    NotificationType.SWAP_OFF, "/swap-off");
        }
        auditService.record("SWAP_OFF_APPROVED", "SwapOffRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "APPROVED"));
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
        SwapOffRequest saved = swapOffRequestRepository.save(swap);

        if (saved.getEmployee().getUser() != null) {
            notificationService.notifyUser(saved.getEmployee().getUser().getId(), "Swap Off Rejected",
                    "Your swap off request (worked " + saved.getWorkedDate() + " on behalf of "
                            + saved.getWorkedForEmployee().getFullName() + ", off "
                            + saved.getRequestedOffDate() + " for " + saved.getWorkedForEmployee().getFullName() + ") was rejected."
                            + (rejectionReason == null || rejectionReason.isBlank() ? "" : " Reason: " + rejectionReason),
                    NotificationType.SWAP_OFF, "/swap-off");
        }
        auditService.record("SWAP_OFF_REJECTED", "SwapOffRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "REJECTED", "reason", rejectionReason));
        return toResponse(saved);
    }

    private void updateAttendanceForApproval(SwapOffRequest swap) {
        // Worked date: requester gets WORK_FROM_OFFICE
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
        // Requested off date: worked-for employee gets SWAP_OFF (not COMP_OFF)
        attendanceRepository.findByEmployeeIdAndAttendanceDate(swap.getWorkedForEmployee().getId(), swap.getRequestedOffDate())
                .ifPresentOrElse(existing -> {
                    if (existing.getAttendanceType() == AttendanceType.WORK_FROM_OFFICE
                            || existing.getAttendanceType() == AttendanceType.WORK_FROM_HOME) {
                        existing.setAttendanceType(AttendanceType.COMP_OFF); // Use COMP_OFF as the attendance type for SW OFF
                        existing.setRemarks("Swap off day off");
                        attendanceRepository.save(existing);
                    }
                }, () -> attendanceRepository.save(Attendance.builder()
                        .employee(swap.getWorkedForEmployee())
                        .attendanceDate(swap.getRequestedOffDate())
                        .attendanceType(AttendanceType.COMP_OFF)
                        .source(AttendanceSource.SYSTEM)
                        .remarks("Swap off day off")
                        .build()));
    }

    private void removeCreditedAttendance(SwapOffRequest swap) {
        attendanceRepository.findByEmployeeIdAndAttendanceDate(swap.getEmployee().getId(), swap.getWorkedDate())
                .ifPresent(existing -> {
                    if (existing.getRemarks() != null && existing.getRemarks().contains("Swap off worked day")) {
                        attendanceRepository.delete(existing);
                    }
                });
        attendanceRepository.findByEmployeeIdAndAttendanceDate(swap.getWorkedForEmployee().getId(), swap.getRequestedOffDate())
                .ifPresent(existing -> {
                    if (existing.getRemarks() != null && existing.getRemarks().contains("Swap off day off")) {
                        attendanceRepository.delete(existing);
                    }
                });
    }

    private void ensureNotSelfDecision(SwapOffRequest swap, Long adminUserId) {
        if (swap.getEmployee().getUser() != null
                && Objects.equals(swap.getEmployee().getUser().getId(), adminUserId)) {
            throw ApiException.badRequest("An administrator cannot approve or reject their own swap off request");
        }
        if (swap.getWorkedForEmployee().getUser() != null
                && Objects.equals(swap.getWorkedForEmployee().getUser().getId(), adminUserId)) {
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
                s.getWorkedForEmployee() != null ? s.getWorkedForEmployee().getId() : null,
                s.getWorkedForEmployee() != null ? s.getWorkedForEmployee().getEmployeeCode() : null,
                s.getWorkedForEmployee() != null ? s.getWorkedForEmployee().getFullName() : null,
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