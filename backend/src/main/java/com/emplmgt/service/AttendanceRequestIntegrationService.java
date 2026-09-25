package com.emplmgt.service;

import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.entity.LeaveRequest;
import com.emplmgt.entity.LeaveStatus;
import com.emplmgt.entity.LeaveType;
import com.emplmgt.entity.SwapOffRequest;
import com.emplmgt.entity.User;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.repository.LeaveRequestRepository;
import com.emplmgt.repository.SwapOffRequestRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.HistoricalImportCodes;
import com.emplmgt.util.LeaveDaysCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Automatic integration between the Leave / Swap Off approval workflow and the
 * Attendance Roster ({@code attendance_records}).
 *
 * <p>Approving a request writes the resulting roster status cells (PL/SL/CO for
 * leaves, WFO for the swap worked day and SW OFF for the requested off day)
 * and links each record back to the originating request via
 * {@code sourceRequestId} + {@code sourceRequestType}. Free-text manual admin
 * descriptions are never touched by these writes.</p>
 *
 * <p>At read time {@link #resolveSource} expands a roster cell's source request
 * into the original reason, the submitter and the actual approver (the
 * {@code decidedBy} user who approved it — never hardcoded, never the current
 * viewer). Only APPROVED requests are ever surfaced; PENDING / REJECTED /
 * CANCELLED never produce or resolve to a source. Cells written by a historical
 * workbook import (no stored link) fall back to an approved-request lookup by
 * employee code + date when the stored status matches the request's canonical
 * status.</p>
 */
@Service
@RequiredArgsConstructor
public class AttendanceRequestIntegrationService {

    public static final String SOURCE_TYPE_LEAVE = "LEAVE";
    public static final String SOURCE_TYPE_SWAP_OFF = "SWAP_OFF";

    private final AttendanceRecordRepository recordRepository;
    private final ImportEmployeeRepository importEmployeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final SwapOffRequestRepository swapOffRequestRepository;
    private final HolidayRepository holidayRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final LeaveDaysCalculator daysCalculator;
    private final AppClock appClock;

    // ------------------------------------------------------------------ WRITE

    /** Called when a leave is APPROVED. Writes the roster status (PL/SL/CO) for
     *  every working day in the leave range and links each cell to the leave. */
    @Transactional
    public void applyLeaveApproval(LeaveRequest leave) {
        String code = leave.getLeaveType().getCode();
        Set<LocalDate> holidayDates = holidayRepository.findByHolidayDateBetween(
                        leave.getStartDate(), leave.getEndDate()).stream()
                .map(Holiday::getHolidayDate)
                .collect(Collectors.toSet());
        for (LocalDate date = leave.getStartDate(); !date.isAfter(leave.getEndDate()); date = date.plusDays(1)) {
            if (daysCalculator.isWeeklyOff(date) || holidayDates.contains(date)) {
                continue;
            }
            upsertSourceRecord(leave.getEmployee(), date, code,
                    leave.getId(), SOURCE_TYPE_LEAVE);
        }
    }

    /** Called when a swap off is APPROVED.
     *  - Worked date: requester (employee) gets WFO (Work From Office)
     *  - Requested off date: worked-for employee gets SW OFF
     *  Both link back to the swap request. */
    @Transactional
    public void applySwapOffApproval(SwapOffRequest swap) {
        // Requester's worked date -> WFO
        upsertSourceRecord(swap.getEmployee(), swap.getWorkedDate(), "WFO",
                swap.getId(), SOURCE_TYPE_SWAP_OFF);
        // Worked-for employee's requested off date -> SW OFF
        upsertSourceRecord(swap.getWorkedForEmployee(), swap.getRequestedOffDate(), "SW OFF",
                swap.getId(), SOURCE_TYPE_SWAP_OFF);
    }

    private void upsertSourceRecord(Employee employee, LocalDate date, String code,
                                    Long requestId, String requestType) {
        if (employee == null || employee.getEmployeeCode() == null || date == null) {
            return;
        }
        ensureEmployeeExists(employee);
        AttendanceRecord rec = recordRepository
                .findByEmployeeIdAndAttendanceDate(employee.getEmployeeCode(), date)
                .orElse(null);
        if (rec == null) {
            rec = new AttendanceRecord();
            rec.setEmployeeId(employee.getEmployeeCode());
            rec.setAttendanceDate(date);
            rec.setImportedAt(appClock.now());
        }
        rec.setStatusCode(code);
        boolean known = HistoricalImportCodes.isKnown(code);
        rec.setStatusName(known ? HistoricalImportCodes.nameOf(code) : null);
        rec.setIsUnknown(!known);
        rec.setSourceRequestId(requestId);
        rec.setSourceRequestType(requestType);
        rec.setUpdatedAt(appClock.now());
        recordRepository.save(rec);
    }

    /** The roster grid reads employees from import_employees; a request's
     *  employee may not have been imported yet, so a placeholder row is created
     *  (FK on attendance_records.employee_id requires it). */
    private void ensureEmployeeExists(Employee employee) {
        if (importEmployeeRepository.existsById(employee.getEmployeeCode())) {
            return;
        }
        ImportEmployee emp = ImportEmployee.builder()
                .employeeId(employee.getEmployeeCode())
                .employeeName(employee.getFullName())
                .active(Boolean.TRUE)
                .teamId(employee.getDepartment() != null ? employee.getDepartment().getId() : null)
                .build();
        importEmployeeRepository.save(emp);
    }

    // ------------------------------------------------------------------ READ

    /** Expanded source-request details for a roster cell, or null when the cell
     *  is not backed by an approved leave / swap-off request. */
    public record SourceDetail(Long requestId, String requestType, String reason,
                               String submittedByName, String approvedByName, Instant approvedAt,
                               String workedForName, LocalDate workedDate) {
    }

    /** Resolve the approved source request behind a roster status cell. Prefers
     *  the stored link (cells written by this approval integration), then falls
     *  back to an approved-request lookup by employee code + date when the stored
     *  status matches the request's canonical status. */
    @Transactional(readOnly = true)
    public SourceDetail resolveSource(String employeeId, LocalDate date, String statusCode,
                                      Long storedRequestId, String storedRequestType) {
        if (employeeId == null || date == null) {
            return null;
        }
        SourceDetail fromStored = resolveStored(storedRequestId, storedRequestType, date, statusCode);
        if (fromStored != null) {
            return fromStored;
        }
        return resolveFallback(employeeId, date, statusCode);
    }

    private SourceDetail resolveStored(Long requestId, String requestType, LocalDate date, String statusCode) {
        if (requestId == null || requestType == null) {
            return null;
        }
        if (SOURCE_TYPE_LEAVE.equals(requestType)) {
            return leaveRequestRepository.findById(requestId)
                    .filter(l -> l.getStatus() == LeaveStatus.APPROVED)
                    .filter(l -> l.getLeaveType() != null
                            && l.getLeaveType().getCode().equalsIgnoreCase(statusCode == null ? "" : statusCode))
                    .map(this::toSource).orElse(null);
        }
        if (SOURCE_TYPE_SWAP_OFF.equals(requestType)) {
            return swapOffRequestRepository.findById(requestId)
                    .filter(s -> s.getStatus() == LeaveStatus.APPROVED)
                    .filter(s -> expectedSwapCode(s, date) != null
                            && expectedSwapCode(s, date).equalsIgnoreCase(statusCode == null ? "" : statusCode))
                    .map(this::toSource).orElse(null);
        }
        return null;
    }

    private SourceDetail resolveFallback(String employeeId, LocalDate date, String statusCode) {
        if (statusCode == null) {
            return null;
        }
        String upper = statusCode.toUpperCase(Locale.ROOT);
        if (upper.equals("PL") || upper.equals("SL") || upper.equals("CO")) {
            LeaveType type = switch (upper) {
                case "PL" -> LeaveType.PRIVILEGE_LEAVE;
                case "SL" -> LeaveType.SICK_LEAVE;
                default -> LeaveType.COMP_OFF;
            };
            return leaveRequestRepository.findApprovedByCodeAndDate(type, employeeId, date).stream()
                    .findFirst().map(this::toSource).orElse(null);
        }
        if (upper.equals("SW OFF") || upper.equals("WFO") || upper.equals("WK WRK")) {
            return swapOffRequestRepository.findApprovedByCodeAndDate(employeeId, date).stream()
                    .filter(s -> upper.equals(expectedSwapCode(s, date)))
                    .findFirst().map(this::toSource).orElse(null);
        }
        return null;
    }

    /** The roster status a swap-off request produces for a given date:
     *  SW OFF on the requested off date (for worked-for employee),
     *  WFO on the worked date (for requester), else null. */
    private String expectedSwapCode(SwapOffRequest swap, LocalDate date) {
        if (swap.getRequestedOffDate() != null && swap.getRequestedOffDate().equals(date)) {
            return "SW OFF";
        }
        if (swap.getWorkedDate() != null && swap.getWorkedDate().equals(date)) {
            return "WFO";
        }
        return null;
    }

    private SourceDetail toSource(LeaveRequest leave) {
        return new SourceDetail(leave.getId(), SOURCE_TYPE_LEAVE,
                nonBlank(leave.getReason()),
                employeeName(leave.getEmployee()),
                displayNameOf(leave.getDecidedBy()),
                leave.getDecidedAt(),
                null, null);
    }

    private SourceDetail toSource(SwapOffRequest swap) {
        return new SourceDetail(swap.getId(), SOURCE_TYPE_SWAP_OFF,
                nonBlank(swap.getReason()),
                employeeName(swap.getEmployee()),
                displayNameOf(swap.getDecidedBy()),
                swap.getDecidedAt(),
                employeeName(swap.getWorkedForEmployee()),
                swap.getWorkedDate());
    }

    private String employeeName(Employee employee) {
        if (employee == null) {
            return null;
        }
        return nonBlank(employee.getFullName());
    }

    /** Actor display name: employee full name when the account is linked to an
     *  employee profile, otherwise the account email. Never hardcoded. */
    private String displayNameOf(User user) {
        if (user == null) {
            return null;
        }
        return employeeRepository.findByUserId(user.getId())
                .map(Employee::getFullName)
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> user.getEmail() != null && !user.getEmail().isBlank()
                        ? user.getEmail() : null);
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}