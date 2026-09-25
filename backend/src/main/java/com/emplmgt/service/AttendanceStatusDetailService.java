package com.emplmgt.service;

import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.User;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.HistoricalImportCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Read/write accessor for the attendance status description (reason) shown in
 * the roster popup. The description belongs to the individual attendance
 * record (employee + date) and carries who created / last updated it.
 *
 * <p>Reads are available to any authenticated user. Writes are performed by the
 * controller which enforces the ADMIN role via {@code @PreAuthorize}; this
 * service never trusts a client-supplied role/value.</p>
 */
@Service
@RequiredArgsConstructor
public class AttendanceStatusDetailService {

    private final AttendanceRecordRepository recordRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AttendanceRequestIntegrationService requestIntegration;
    private final SecurityUtils securityUtils;
    private final AppClock appClock;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public AttendanceRosterDtos.StatusDetail get(String employeeId, LocalDate date) {
        AttendanceRecord rec = recordRepository.findByEmployeeIdAndAttendanceDate(employeeId, date).orElse(null);
        return detailOf(rec);
    }

    /** Resolution of the source-request block, shared by read + write paths so
     *  every status-detail response (GET/POST/DELETE) carries the same shape. */
    private AttendanceRosterDtos.StatusDetail detailOf(AttendanceRecord rec) {
        if (rec == null) {
            return null;
        }
        AttendanceRequestIntegrationService.SourceDetail source = requestIntegration.resolveSource(
                rec.getEmployeeId(), rec.getAttendanceDate(), rec.getStatusCode(),
                rec.getSourceRequestId(), rec.getSourceRequestType());
        return toDetail(rec, source);
    }

    /** Admin-only by contract (controller enforces ROLE_ADMIN). Upserts the
     *  free-text reason; an empty description clears it back to "none". */
    @Transactional
    public AttendanceRosterDtos.StatusDetail upsert(String employeeId, LocalDate date, String description) {
        boolean clearing = description == null || description.isBlank();
        if (clearing) {
            return clear(employeeId, date);
        }

        String reason = description.trim();
        AttendanceRecord rec = requiredRecord(employeeId, date);
        AttendanceRosterDtos.StatusDetail before = detailOf(rec);

        Long actorId = securityUtils.currentUserId();
        if (actorId == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        String actorName = displayNameOf(actorId);
        Instant now = appClock.now();

        boolean isNew = rec.getDescription() == null;
        rec.setDescription(reason);
        if (isNew) {
            rec.setDescriptionCreatedBy(actorId);
            rec.setDescriptionCreatedName(actorName);
            rec.setDescriptionCreatedAt(now);
            rec.setDescriptionUpdatedBy(null);
            rec.setDescriptionUpdatedName(null);
            rec.setDescriptionUpdatedAt(null);
        } else {
            rec.setDescriptionUpdatedBy(actorId);
            rec.setDescriptionUpdatedName(actorName);
            rec.setDescriptionUpdatedAt(now);
        }
        rec.setUpdatedAt(now);
        recordRepository.save(rec);

        auditService.record("STATUS_DESCRIPTION_UPSERTED", "AttendanceRecord",
                employeeId + "|" + date,
                Map.of("description", before.description() == null ? "" : before.description()),
                Map.of("description", reason, "updatedByName", actorName));

        return detailOf(rec);
    }

    /** Admin-only by contract. Removes the description from a record. */
    @Transactional
    public AttendanceRosterDtos.StatusDetail clear(String employeeId, LocalDate date) {
        AttendanceRecord rec = recordRepository.findByEmployeeIdAndAttendanceDate(employeeId, date).orElse(null);
        if (rec == null || rec.getDescription() == null) {
            return detailOf(rec);
        }
        String old = rec.getDescription();
        rec.setDescription(null);
        rec.setDescriptionCreatedBy(null);
        rec.setDescriptionCreatedName(null);
        rec.setDescriptionCreatedAt(null);
        rec.setDescriptionUpdatedBy(null);
        rec.setDescriptionUpdatedName(null);
        rec.setDescriptionUpdatedAt(null);
        rec.setUpdatedAt(appClock.now());
        recordRepository.save(rec);

        auditService.record("STATUS_DESCRIPTION_CLEARED", "AttendanceRecord",
                employeeId + "|" + date,
                Map.of("description", old),
                Map.of("description", ""));

        return detailOf(rec);
    }

    private AttendanceRecord requiredRecord(String employeeId, LocalDate date) {
        AttendanceRecord rec = recordRepository.findByEmployeeIdAndAttendanceDate(employeeId, date).orElse(null);
        if (rec == null) {
            throw ApiException.notFound("No attendance record for the given employee and date");
        }
        return rec;
    }

    /** Actor display name: employee full name when the account is linked to an
     *  employee profile, otherwise the account email. Never hardcoded. */
    private String displayNameOf(Long userId) {
        return employeeRepository.findByUserId(userId)
                .map(Employee::getFullName)
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> userRepository.findById(userId).map(User::getEmail).orElse("Unknown"));
    }

private AttendanceRosterDtos.StatusDetail toDetail(AttendanceRecord rec,
                                                        AttendanceRequestIntegrationService.SourceDetail source) {
        if (rec == null) {
            return null;
        }
        String statusName = null;
        if (rec.getStatusCode() != null && HistoricalImportCodes.isKnown(rec.getStatusCode())) {
            statusName = HistoricalImportCodes.nameOf(rec.getStatusCode());
        } else {
            statusName = rec.getStatusName();
        }
        return new AttendanceRosterDtos.StatusDetail(
                rec.getEmployeeId(), rec.getAttendanceDate(), rec.getStatusCode(), statusName,
                rec.getDescription(), rec.getDescriptionCreatedName(), rec.getDescriptionCreatedAt(),
                rec.getDescriptionUpdatedName(), rec.getDescriptionUpdatedAt(),
                source == null ? null : source.requestId(),
                source == null ? null : source.requestType(),
                source == null ? null : source.reason(),
                source == null ? null : source.submittedByName(),
                source == null ? null : source.approvedByName(),
                source == null ? null : source.approvedAt(),
                source == null ? null : source.workedForName(),
                source == null ? null : source.workedDate());
    }
}