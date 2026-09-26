package com.emplmgt.service;

import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.dto.HolidayDtos;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final HPEEntitlementService hpeEntitlementService;

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

        // Resolve and validate the earned HPE Holiday entitlement before the balance check so an
        // unavailable/unowned entitlement fails with a precise reason rather than a generic
        // "insufficient balance". Validation only - nothing is reserved yet.
        if (request.leaveType() != LeaveType.COMP_OFF && request.hpeEntitlementId() != null) {
            throw ApiException.badRequest("An HPE Holiday can only be applied to Compensatory Off");
        }
        HPEEntitlement entitlement = request.leaveType() == LeaveType.COMP_OFF
                ? resolveCompOffEntitlement(employee, request.hpeEntitlementId())
                : null;

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
                .hpeEntitlement(entitlement)
                .build();
        LeaveRequest saved = leaveRequestRepository.save(leave);

        // Submission only reserves the entitlement. It stays RESERVED (not USED) until an
        // admin approves, so a rejected or cancelled request releases it untouched.
        if (entitlement != null) {
            hpeEntitlementService.reserveForRequest(employee.getId(), entitlement.getId(), saved.getId());
        }

        notificationService.notifyAdmins(
                "New Leave Request",
                employee.getFullName() + " (" + employee.getEmployeeCode() + ") applied for "
                        + request.leaveType().getLabel() + " from " + request.startDate() + " to " + request.endDate()
                        + " (" + days + " day(s))."
                        + (entitlement == null ? "" : " Using HPE Holiday: " + entitlement.getHoliday().getName()
                        + " (" + entitlement.getHoliday().getHolidayDate() + ")."),
                NotificationType.LEAVE, "/admin/leaves");

        auditService.record("LEAVE_APPLIED", "LeaveRequest", String.valueOf(saved.getId()),
                null, Map.of("employee", employee.getFullName(), "type", request.leaveType().name(),
                        "from", request.startDate().toString(), "to", request.endDate().toString(), "days", days,
                        "hpeEntitlementId", String.valueOf(entitlement == null ? null : entitlement.getId())));

        return toResponse(saved, entitlementIndex(List.of(saved)));
    }

    @Transactional(readOnly = true)
    public List<LeaveDtos.Response> myLeaves(Long userId) {
        Employee employee = employeeFor(userId);
        List<LeaveRequest> leaves = leaveRequestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId());
        Map<Long, HolidayDtos.HPEEntitlementResponse> entitlements = entitlementIndex(leaves);
        return leaves.stream().map(l -> toResponse(l, entitlements)).toList();
    }

    @Transactional(readOnly = true)
    public List<EmployeeDtos.LeaveBalanceDto> myBalances(Long userId) {
        Employee employee = employeeFor(userId);
        int year = appClock.today().getYear();
        return employeeService.balancesFor(employee, year).stream().map(b -> {
            if ("COMP_OFF".equals(b.leaveType())) {
                BigDecimal allocated = b.allocated().add(coCredits(employee, year));
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
            // Existing cancellation rules only allow PENDING requests to be cancelled. An
            // APPROVED request has already granted the day (roster CO + attendance row) and,
            // for a Compensatory Off backed by an HPE entitlement, already spent that
            // entitlement - so it is correctly not cancellable here and the entitlement
            // correctly stays USED. releaseReservation is additionally a no-op unless the
            // entitlement is still RESERVED, so a consumed one can never be handed back.
            throw ApiException.badRequest("Only pending leaves can be cancelled");
        }
        leave.setStatus(LeaveStatus.CANCELLED);
        LeaveRequest saved = leaveRequestRepository.save(leave);
        // A cancelled request never consumed the entitlement - hand it back to the pool.
        hpeEntitlementService.releaseReservation(entitlementIdOf(saved), saved.getId());
        auditService.record("LEAVE_CANCELLED", "LeaveRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "CANCELLED"));
        return toResponse(saved, entitlementIndex(List.of(saved)));
    }

    // ------------------------------------------------------------------ ADMIN

    @Transactional(readOnly = true)
    public Page<LeaveDtos.Response> search(LeaveStatus status, LeaveType leaveType, Long employeeId, Long departmentId,
                                           LocalDate from, LocalDate to, String search, Pageable pageable) {
        Page<LeaveRequest> page = leaveRequestRepository.search(status, leaveType, employeeId, departmentId,
                from, to, search, pageable);
        Map<Long, HolidayDtos.HPEEntitlementResponse> entitlements =
                entitlementIndex(page.getContent());
        return page.map(l -> toResponse(l, entitlements));
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

        // Approval is the only point at which the earned HPE Holiday is actually spent.
        // This runs inside the same transaction as the roster (attendanceRequestIntegration,
        // which writes the "CO" roster status) and the attendance rows above, so either the
        // leave is approved AND the entitlement is consumed AND the roster shows CO, or
        // nothing happens at all - an entitlement is never left USED for a rolled-back
        // approval. The off date is recorded on the entitlement so the spend is auditable
        // without joining back to this request.
        Long entitlementId = entitlementIdOf(saved);
        if (entitlementId != null) {
            hpeEntitlementService.consumeReservation(entitlementId, saved.getId(),
                    saved.getStartDate(), adminUserId);
        }

        notifyEmployee(saved, "Leave Approved",
                "Your " + saved.getLeaveType().getLabel() + " request (" + saved.getStartDate() + " to "
                        + saved.getEndDate() + ") has been approved."
                        + (saved.getHpeEntitlement() == null ? "" : " Your HPE Holiday entitlement "
                        + saved.getHpeEntitlement().getHoliday().getName() + " has been used."),
                NotificationType.LEAVE, "/leaves");
        auditService.record("LEAVE_APPROVED", "LeaveRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "APPROVED", "days", saved.getDays(),
                        "approvedByUserId", String.valueOf(adminUserId),
                        "hpeEntitlementId", String.valueOf(entitlementId)));
        return toResponse(saved, entitlementIndex(List.of(saved)));
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

        // Rejected request never spent the entitlement - return it to AVAILABLE.
        hpeEntitlementService.releaseReservation(entitlementIdOf(saved), saved.getId());

        notifyEmployee(saved, "Leave Rejected",
                "Your " + saved.getLeaveType().getLabel() + " request (" + saved.getStartDate() + " to "
                        + saved.getEndDate() + ") was rejected."
                        + (rejectionReason == null || rejectionReason.isBlank() ? "" : " Reason: " + rejectionReason),
                NotificationType.LEAVE, "/leaves");
        auditService.record("LEAVE_REJECTED", "LeaveRequest", String.valueOf(saved.getId()),
                Map.of("status", "PENDING"), Map.of("status", "REJECTED", "reason", rejectionReason));
        return toResponse(saved, entitlementIndex(List.of(saved)));
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

    /**
     * Validates the HPE Holiday entitlement chosen for a Compensatory Off request.
     *
     * <p>Rejects: a missing selection, an entitlement belonging to someone else, one that is
     * already USED, one already reserved by another pending request, and one whose 3-month
     * availing window has closed. Purely a read - the entitlement is only reserved once the
     * request has been persisted.</p>
     */
    private HPEEntitlement resolveCompOffEntitlement(Employee employee, Long hpeEntitlementId) {
        if (hpeEntitlementId == null) {
            throw ApiException.badRequest("Select the HPE Holiday you want to use for this Compensatory Off");
        }
        HPEEntitlement entitlement = hpeEntitlementService.requireUsableForRequest(employee.getId(), hpeEntitlementId);
        if (leaveRequestRepository.existsActiveRequestForEntitlement(hpeEntitlementId)) {
            throw ApiException.conflict("This HPE Holiday is already attached to another pending or approved request");
        }
        return entitlement;
    }

    private Long entitlementIdOf(LeaveRequest leave) {
        return leave.getHpeEntitlement() == null ? null : leave.getHpeEntitlement().getId();
    }

    private void validateBalance(Employee employee, LeaveType type, BigDecimal requestedDays) {
        int year = appClock.today().getYear();
        BigDecimal available;
        if (type == LeaveType.COMP_OFF) {
            BigDecimal used = leaveRequestRepository.sumApprovedDays(employee.getId(), LeaveType.COMP_OFF);
            available = coAllocated(employee, year).add(coCredits(employee, year)).subtract(used);
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

    /**
     * Compensatory off credits: approved swap-offs plus one day per still-usable earned
     * HPE Holiday entitlement. Without the entitlement term an employee holding a valid
     * earned entitlement would always be told they have zero Compensatory Off balance.
     */
    private BigDecimal coCredits(Employee employee, int year) {
        long swapOffCredits = swapOffRequestRepository.countApprovedCredits(employee.getId(), year);
        long hpeEntitlements = hpeEntitlementService.countUsableEntitlements(employee.getId());
        return BigDecimal.valueOf(swapOffCredits + hpeEntitlements);
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

    /**
     * Resolves the HPE Holiday details for a page of leaves in a single query, so listing
     * leaves does not trigger one lookup per row.
     */
    private Map<Long, HolidayDtos.HPEEntitlementResponse> entitlementIndex(List<LeaveRequest> leaves) {
        Set<Long> ids = leaves.stream()
                .map(this::entitlementIdOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return hpeEntitlementService.findEntitlementsByIds(ids).stream()
                .collect(Collectors.toMap(HolidayDtos.HPEEntitlementResponse::id, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    public LeaveDtos.Response toResponse(LeaveRequest l) {
        return toResponse(l, entitlementIndex(List.of(l)));
    }

    private LeaveDtos.Response toResponse(LeaveRequest l, Map<Long, HolidayDtos.HPEEntitlementResponse> entitlements) {
        HolidayDtos.HPEEntitlementResponse hpe = l.getHpeEntitlement() == null
                ? null
                : entitlements.get(l.getHpeEntitlement().getId());
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
                l.getDecidedAt(),
                l.getHpeEntitlement() != null ? l.getHpeEntitlement().getId() : null,
                hpe != null ? hpe.holidayName() : null,
                hpe != null ? hpe.holidayDate() : null,
                hpe != null ? hpe.expiryDate() : null);
    }
}