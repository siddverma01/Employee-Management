package com.emplmgt.service;

import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.EmploymentStatus;
import com.emplmgt.entity.HPEEntitlement;
import com.emplmgt.entity.HPEEntitlementStatus;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.HolidayType;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HPEEntitlementRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.service.RosterWorkStatusService.DayWorkStatus;
import com.emplmgt.service.RosterWorkStatusService.WorkStatus;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.HolidayLocationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * HPE Holiday / HPEH entitlement tracking.
 *
 * <p>A master HPE holiday definition ({@code holidays.holiday_type = HPE_HOLIDAY}) is stored once and
 * carries the locations it applies to. Employee-specific rows are only created when an employee
 * actually <b>earns</b> an entitlement:</p>
 *
 * <ul>
 *   <li><b>A) Employee takes the holiday on the HPE holiday date</b> &rarr; roster status {@code HPEH}
 *       (or any non-working status), <b>no</b> entitlement is created.</li>
 *   <li><b>B) Employee works on the HPE holiday date</b> &rarr; exactly one AVAILABLE entitlement is
 *       earned, usable within {@link #EXPIRY_MONTHS} calendar months counted from the <b>original</b>
 *       HPE holiday date. After that it expires if unused.</li>
 * </ul>
 *
 * <p>Earning is evaluated against the existing attendance sources via
 * {@link RosterWorkStatusService} (roster status cell &rarr; daily attendance record &rarr; week off).
 * "No record" is never treated as work.</p>
 */
@Service
@RequiredArgsConstructor
public class HPEEntitlementService {

    /** An earned entitlement must be availed within this many <b>calendar months</b> of the original HPE holiday date. */
    public static final int EXPIRY_MONTHS = 3;

    private final HPEEntitlementRepository entitlementRepository;
    private final HolidayRepository holidayRepository;
    private final EmployeeRepository employeeRepository;
    private final RosterWorkStatusService workStatusService;
    private final AppClock appClock;
    private final AuditService auditService;

    // ------------------------------------------------------------------ MASTER HOLIDAYS

    /**
     * Master HPE holiday definitions that apply to this employee based on their location.
     * One shared definition per holiday - never duplicated per employee.
     */
    @Transactional(readOnly = true)
    public List<HolidayDtos.HPEHolidayResponse> getApplicableHpeHolidays(Long employeeId) {
        Employee employee = getEmployee(employeeId);
        return holidayRepository.findByHolidayTypeAndActiveTrueOrderByHolidayDate(HolidayType.HPE_HOLIDAY).stream()
                .filter(h -> isApplicableToLocation(h, employee.getLocation()))
                .map(this::toHpeHolidayResponse)
                .collect(Collectors.toList());
    }

    /**
     * Location applicability of a master HPE holiday: {@code ALL} applies to everyone,
     * {@code PUNE_MUMBAI} applies to employees whose location is Pune or Mumbai.
     */
    @Transactional(readOnly = true)
    public boolean isHpeHolidayApplicableToEmployee(Long holidayId, Long employeeId) {
        Holiday holiday = holidayRepository.findById(holidayId)
                .orElseThrow(() -> ApiException.notFound("HPE Holiday not found: " + holidayId));
        Employee employee = getEmployee(employeeId);
        return holiday.isActive() && isApplicableToLocation(holiday, employee.getLocation());
    }

    boolean isApplicableToLocation(Holiday holiday, String employeeLocation) {
        return HolidayLocationUtil.applies(holiday, employeeLocation);
    }

    // ------------------------------------------------------------------ ENTITLEMENT QUERIES

    /**
     * Entitlements the employee can still avail as compensatory off: AVAILABLE and not past
     * their expiry date. Expired entitlements are never returned, even if the scheduled
     * expiry sweep has not flipped their status yet.
     */
    @Transactional(readOnly = true)
    public List<HolidayDtos.HPEEntitlementResponse> getAvailableHpeEntitlements(Long employeeId) {
        getEmployee(employeeId);
        LocalDate today = appClock.today();
        return entitlementRepository.findAvailableByEmployeeId(employeeId, today).stream()
                .filter(e -> !isOverdue(e, today))
                .map(this::toEntitlementResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<HolidayDtos.HPEEntitlementResponse> getAllEntitlements(Long employeeId) {
        getEmployee(employeeId);
        return entitlementRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream()
                .map(this::toEntitlementResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public HolidayDtos.EntitlementStatusSummary getEntitlementStatusSummary(Long employeeId) {
        getEmployee(employeeId);
        LocalDate today = appClock.today();
        List<HPEEntitlement> all = entitlementRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
        long available = all.stream().filter(e -> e.getStatus() == HPEEntitlementStatus.AVAILABLE && !isOverdue(e, today)).count();
        long reserved = all.stream().filter(e -> e.getStatus() == HPEEntitlementStatus.RESERVED).count();
        long used = all.stream().filter(e -> e.getStatus() == HPEEntitlementStatus.USED).count();
        long expired = all.stream().filter(e -> e.getStatus() == HPEEntitlementStatus.EXPIRED
                || (e.getStatus() == HPEEntitlementStatus.AVAILABLE && isOverdue(e, today))).count();
        return new HolidayDtos.EntitlementStatusSummary(available, reserved, used, expired);
    }

    /**
     * How many earned HPE Holiday entitlements can still back a compensatory off request:
     * AVAILABLE and not past expiry. Each entitlement is worth one compensatory off day.
     */
    @Transactional(readOnly = true)
    public long countUsableEntitlements(Long employeeId) {
        return entitlementRepository.countUsableByEmployeeId(employeeId, appClock.today());
    }

    /**
     * Batch lookup used to label leave requests with the HPE Holiday they consume
     * without issuing one query per row.
     */
    @Transactional(readOnly = true)
    public List<HolidayDtos.HPEEntitlementResponse> findEntitlementsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return entitlementRepository.findAllById(ids).stream()
                .sorted((a, b) -> a.getHoliday().getHolidayDate().compareTo(b.getHoliday().getHolidayDate()))
                .map(this::toEntitlementResponse)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ EARN (rule B)

    /**
     * Rule B, employee-initiated: the employee must have a recorded <b>working</b> status on the
     * HPE holiday date. The expiry is always derived from the <b>original</b> HPE holiday date,
     * never from the date the request is submitted.
     */
    @Transactional
    public HolidayDtos.HPEEntitlementResponse createHpeEntitlement(Long employeeId, Long holidayId) {
        Employee employee = getEmployee(employeeId);
        Holiday holiday = requireEarnableHoliday(holidayId, employee);

        if (entitlementRepository.findByEmployeeIdAndHolidayId(employeeId, holidayId).isPresent()) {
            throw ApiException.conflict("Entitlement already exists for this HPE holiday");
        }

        assertWorkedOn(employee, holiday);

        HPEEntitlement saved = entitlementRepository.save(newEntitlement(employee, holiday));
        auditService.record("HPE_ENTITLEMENT_EARNED", "HPEEntitlement", String.valueOf(saved.getId()),
                null, earningValues(employee, holiday, saved));
        return toEntitlementResponse(saved);
    }

    /**
     * Rule B, system-driven: scans one master HPE holiday and creates exactly one AVAILABLE
     * entitlement for every eligible employee whose recorded status for that date is "worked".
     * Idempotent - employees that already have an entitlement are skipped, never duplicated.
     */
    @Transactional
    public HolidayDtos.HpeEntitlementSyncResponse syncEntitlements(Long holidayId) {
        Holiday holiday = holidayRepository.findById(holidayId)
                .orElseThrow(() -> ApiException.notFound("HPE Holiday not found: " + holidayId));
        return syncEntitlements(holiday);
    }

    @Transactional
    public HolidayDtos.HpeEntitlementSyncResponse syncEntitlements(Holiday holiday) {
        if (holiday.getHolidayType() != HolidayType.HPE_HOLIDAY) {
            throw ApiException.badRequest("Only HPE holidays can create entitlements");
        }

        int evaluated = 0;
        int created = 0;
        int alreadyExists = 0;
        int notWorking = 0;
        int notApplicable = 0;
        int unknownStatus = 0;

        LocalDate today = appClock.today();
        if (!holiday.isActive() || holiday.getHolidayDate().isAfter(today)) {
            return new HolidayDtos.HpeEntitlementSyncResponse(holiday.getId(), holiday.getName(),
                    holiday.getHolidayDate(), 0, 0, 0, 0, 0, 0);
        }

        for (Employee employee : employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE)) {
            evaluated++;
            if (!isApplicableToLocation(holiday, employee.getLocation())) {
                notApplicable++;
                continue;
            }
            if (entitlementRepository.findByEmployeeIdAndHolidayId(employee.getId(), holiday.getId()).isPresent()) {
                alreadyExists++;
                continue;
            }
            DayWorkStatus day = workStatusService.resolve(employee, holiday.getHolidayDate());
            if (day.worked()) {
                entitlementRepository.save(newEntitlement(employee, holiday));
                created++;
            } else if (day.status() == WorkStatus.NOT_WORKED) {
                notWorking++;
            } else {
                unknownStatus++;
            }
        }

        if (created > 0) {
            auditService.record("HPE_ENTITLEMENTS_EARNED", "HPEEntitlement", "holiday:" + holiday.getId(),
                    null, Map.of("holidayId", holiday.getId(),
                            "holidayDate", holiday.getHolidayDate().toString(),
                            "created", created,
                            "alreadyExists", alreadyExists,
                            "notWorking", notWorking,
                            "notApplicable", notApplicable));
        }

        return new HolidayDtos.HpeEntitlementSyncResponse(holiday.getId(), holiday.getName(),
                holiday.getHolidayDate(), evaluated, created, alreadyExists, notWorking,
                notApplicable, unknownStatus);
    }

    /** Scans every active master HPE holiday that has already taken place. */
    @Transactional
    public List<HolidayDtos.HpeEntitlementSyncResponse> syncAllDueEntitlements() {
        LocalDate today = appClock.today();
        List<HolidayDtos.HpeEntitlementSyncResponse> results = new ArrayList<>();
        for (Holiday holiday : holidayRepository.findByHolidayTypeAndActiveTrueOrderByHolidayDate(HolidayType.HPE_HOLIDAY)) {
            if (!holiday.getHolidayDate().isAfter(today)) {
                results.add(syncEntitlements(holiday));
            }
        }
        return results;
    }

    /** Daily scan: awards entitlements to employees recorded as working on an HPE holiday. */
    @Scheduled(cron = "${application.hpe.earn-cron:0 15 2 * * *}")
    @Transactional
    public void autoEarnHpeEntitlements() {
        syncAllDueEntitlements();
    }

    // ------------------------------------------------------------------ USE / EXPIRE

    /**
     * Avails a previously earned entitlement directly. It can never be used twice and never
     * after its expiry date. Prefer {@link #reserveForRequest} for leave requests: that path
     * only consumes the entitlement once the request is approved.
     */
    @Transactional
    public HolidayDtos.HPEEntitlementResponse useHpeEntitlement(Long employeeId, Long entitlementId, Long requestId) {
        HPEEntitlement entitlement = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> ApiException.notFound("Entitlement not found: " + entitlementId));

        assertOwnedBy(entitlement, employeeId);

        LocalDate today = appClock.today();
        if (entitlement.getStatus() == HPEEntitlementStatus.USED) {
            throw ApiException.badRequest("Entitlement has already been used");
        }
        if (entitlement.getStatus() == HPEEntitlementStatus.RESERVED) {
            throw ApiException.badRequest("Entitlement is reserved by a pending request");
        }
        if (entitlement.getStatus() == HPEEntitlementStatus.EXPIRED) {
            throw ApiException.badRequest("Entitlement has expired");
        }
        if (isOverdue(entitlement, today)) {
            throw ApiException.badRequest("Entitlement expired on " + entitlement.getExpiryDate()
                    + " and cannot be used after its expiry date");
        }

        entitlement.setStatus(HPEEntitlementStatus.USED);
        entitlement.setUsedDate(today);
        entitlement.setUsedRequestId(requestId);
        entitlement.setReservedRequestId(null);
        HPEEntitlement saved = entitlementRepository.save(entitlement);

        auditService.record("HPE_ENTITLEMENT_USED", "HPEEntitlement", String.valueOf(saved.getId()),
                Map.of("status", HPEEntitlementStatus.AVAILABLE.name()),
                Map.of("status", HPEEntitlementStatus.USED.name(),
                        "usedDate", today.toString(),
                        "requestId", String.valueOf(requestId)));
        return toEntitlementResponse(saved);
    }

    // ------------------------------------------------------------------ RESERVATION (leave request lifecycle)

    /**
     * Read-only guard for a new Compensatory Off request: confirms the entitlement exists,
     * belongs to this employee, has not been used, is not already reserved by another pending
     * request, and is still inside its 3-month availing window.
     *
     * <p>Performs no state change. The entitlement is only reserved once the request itself has
     * been persisted, so a failed submission never burns an entitlement.</p>
     */
    @Transactional(readOnly = true)
    public HPEEntitlement requireUsableForRequest(Long employeeId, Long entitlementId) {
        HPEEntitlement entitlement = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> ApiException.notFound("Entitlement not found: " + entitlementId));
        assertUsableForRequest(entitlement, employeeId);
        return entitlement;
    }

    /**
     * The single source of truth for "may this entitlement back a Compensatory Off request?".
     * Shared by the unlocked pre-check ({@link #requireUsableForRequest}) and the locked
     * reservation ({@link #reserveForRequest}) so both enforce identical rules.
     */
    private void assertUsableForRequest(HPEEntitlement entitlement, Long employeeId) {
        assertOwnedBy(entitlement, employeeId);

        LocalDate today = appClock.today();
        if (entitlement.getStatus() == HPEEntitlementStatus.USED) {
            throw ApiException.badRequest("Entitlement has already been used");
        }
        if (entitlement.getStatus() == HPEEntitlementStatus.RESERVED) {
            throw ApiException.conflict("Entitlement is already attached to another pending request");
        }
        if (entitlement.getStatus() == HPEEntitlementStatus.EXPIRED) {
            throw ApiException.badRequest("Entitlement has expired");
        }
        if (isOverdue(entitlement, today)) {
            throw ApiException.badRequest("Entitlement expired on " + entitlement.getExpiryDate()
                    + " and cannot be used after its expiry date");
        }
    }

    /**
     * Earmarks an entitlement for a leave request that has just been submitted. The entitlement
     * stays usable-looking but is <b>not</b> consumed: {@code usedDate}/{@code usedOffDate}/
     * {@code usedRequestId} stay empty and the status moves to
     * {@link HPEEntitlementStatus#RESERVED}. It only becomes USED when the request is approved.
     *
     * <p>The row is re-read {@code FOR UPDATE} before the status check so that two applications
     * racing for the same entitlement are serialised: the loser re-reads the row as RESERVED and
     * is rejected, instead of both passing the check and the second write clobbering the
     * first. The {@code uq_leave_requests_active_hpe_entitlement} unique index is the backstop
     * for any path that bypasses this lock.</p>
     */
    @Transactional
    public HolidayDtos.HPEEntitlementResponse reserveForRequest(Long employeeId, Long entitlementId, Long requestId) {
        HPEEntitlement entitlement = entitlementRepository.findByIdForUpdate(entitlementId)
                .orElseThrow(() -> ApiException.notFound("Entitlement not found: " + entitlementId));
        assertUsableForRequest(entitlement, employeeId);

        HPEEntitlementStatus previous = entitlement.getStatus();
        entitlement.setStatus(HPEEntitlementStatus.RESERVED);
        entitlement.setReservedRequestId(requestId);
        HPEEntitlement saved = entitlementRepository.save(entitlement);

        auditService.record("HPE_ENTITLEMENT_RESERVED", "HPEEntitlement", String.valueOf(saved.getId()),
                auditMap("status", previous.name()),
                auditMap("status", HPEEntitlementStatus.RESERVED.name(),
                        "entitlementId", saved.getId(),
                        "requestId", requestId,
                        "requestStatus", "PENDING",
                        "employee", employeeNameOf(saved),
                        "employeeId", employeeIdOf(saved),
                        "hpeHoliday", holidayNameOf(saved),
                        "hpeHolidayId", holidayIdOf(saved),
                        "hpeHolidayDate", holidayDateOf(saved),
                        "earnedDate", saved.getEarnedDate(),
                        "expiryDate", saved.getExpiryDate()));
        return toEntitlementResponse(saved);
    }

    /**
     * Consumes a reserved entitlement when its leave request is approved. This is the only
     * point at which a Compensatory Off request actually spends the earned entitlement.
     *
     * <p>Runs inside the caller's {@code approve()} transaction, so a failure here aborts the
     * whole approval (leave, roster, attendance) and rolls the entitlement back to RESERVED -
     * the entitlement is never left incorrectly marked USED for a request that was not approved.
     * The {@code approve()} transaction already writes the {@code CO} roster status and the
     * attendance rows before calling this, so all four effects commit or roll back together.</p>
     *
     * <p>Guards, in order:</p>
     * <ul>
     *   <li>already USED <b>by this same request</li> &rarr; no-op, keeping approval idempotent;</li>
     *   <li>already USED by a <b>different</b> request &rarr; refused (would double-spend);</li>
     *   <li>EXPIRED, or not RESERVED for this request &rarr; refused, because consuming an
     *       entitlement this request never legitimately held is exactly the "incorrectly
     *       marked USED" state the approval flow must not produce.</li>
     * </ul>
     *
     * @param offDate       the compensatory-off day being granted, stored on the entitlement so
     *                      the spend can be audited without joining back to the leave request
     * @param approverUserId the deciding admin, recorded in the audit trail
     */
    @Transactional
    public void consumeReservation(Long entitlementId, Long requestId, LocalDate offDate, Long approverUserId) {
        if (entitlementId == null) {
            return;
        }
        // Same pessimistic lock as reservation: serialises a concurrent second approval.
        HPEEntitlement entitlement = entitlementRepository.findByIdForUpdate(entitlementId)
                .orElseThrow(() -> ApiException.conflict(
                        "The HPE Holiday entitlement linked to this request no longer exists, so it cannot be approved"));

        if (entitlement.getStatus() == HPEEntitlementStatus.USED) {
            if (Objects.equals(entitlement.getUsedRequestId(), requestId)) {
                return; // already consumed by this very request - approval stays idempotent
            }
            throw ApiException.conflict("This HPE Holiday entitlement has already been consumed by another request");
        }
        if (entitlement.getStatus() == HPEEntitlementStatus.EXPIRED) {
            throw ApiException.conflict("This HPE Holiday entitlement expired on " + entitlement.getExpiryDate()
                    + " and can no longer be approved");
        }
        if (entitlement.getStatus() != HPEEntitlementStatus.RESERVED
                || !Objects.equals(entitlement.getReservedRequestId(), requestId)) {
            throw ApiException.conflict("This HPE Holiday entitlement is not reserved for this request, "
                    + "so it cannot be consumed. Reject the request and let the employee apply again.");
        }

        LocalDate today = appClock.today();
        entitlement.setStatus(HPEEntitlementStatus.USED);
        entitlement.setUsedDate(today);
        entitlement.setUsedOffDate(offDate);
        entitlement.setUsedRequestId(requestId);
        entitlement.setReservedRequestId(null);
        entitlementRepository.save(entitlement);

        auditService.record("HPE_ENTITLEMENT_USED", "HPEEntitlement", String.valueOf(entitlementId),
                auditMap("status", HPEEntitlementStatus.RESERVED.name(),
                        "reservedRequestId", requestId),
                auditMap("status", HPEEntitlementStatus.USED.name(),
                        "entitlementId", entitlementId,
                        "requestId", requestId,
                        "requestStatus", "APPROVED",
                        "approvedByUserId", approverUserId,
                        "usedDate", today,
                        "usedOffDate", offDate,
                        "employee", employeeNameOf(entitlement),
                        "employeeId", employeeIdOf(entitlement),
                        "hpeHoliday", holidayNameOf(entitlement),
                        "hpeHolidayId", holidayIdOf(entitlement),
                        "hpeHolidayDate", holidayDateOf(entitlement),
                        "earnedDate", entitlement.getEarnedDate(),
                        "expiryDate", entitlement.getExpiryDate()));
    }

    /**
     * Returns a reserved entitlement to the pool when its leave request is rejected or
     * cancelled. Entitlements that are no longer reserved are left untouched, and an
     * entitlement whose window closed in the meantime is expired rather than revived.
     *
     * <p>Deliberately a no-op for an entitlement that is already {@code USED}: a request that
     * was approved has actually granted the day off (roster {@code CO} + attendance row), and
     * the existing cancellation rules only allow PENDING requests to be cancelled, so there is
     * no path that should ever hand a spent entitlement back. This guard means that even if
     * such a path were added later, a consumed entitlement could not be silently resurrected
     * into AVAILABLE and spent twice.</p>
     */
    @Transactional
    public void releaseReservation(Long entitlementId, Long requestId) {
        if (entitlementId == null) {
            return;
        }
        HPEEntitlement entitlement = entitlementRepository.findByIdForUpdate(entitlementId).orElse(null);
        if (entitlement == null || entitlement.getStatus() != HPEEntitlementStatus.RESERVED) {
            return;
        }
        if (entitlement.getReservedRequestId() != null
                && !Objects.equals(entitlement.getReservedRequestId(), requestId)) {
            // Held by a different request - do not steal it.
            return;
        }

        LocalDate today = appClock.today();
        entitlement.setReservedRequestId(null);
        if (isOverdue(entitlement, today)) {
            entitlement.setStatus(HPEEntitlementStatus.EXPIRED);
        } else {
            entitlement.setStatus(HPEEntitlementStatus.AVAILABLE);
        }
        entitlementRepository.save(entitlement);

        auditService.record("HPE_ENTITLEMENT_RELEASED", "HPEEntitlement", String.valueOf(entitlementId),
                auditMap("status", HPEEntitlementStatus.RESERVED.name(), "reservedRequestId", requestId),
                auditMap("status", entitlement.getStatus().name(),
                        "entitlementId", entitlementId,
                        "requestId", requestId,
                        "employee", employeeNameOf(entitlement),
                        "employeeId", employeeIdOf(entitlement),
                        "hpeHoliday", holidayNameOf(entitlement),
                        "hpeHolidayId", holidayIdOf(entitlement),
                        "earnedDate", entitlement.getEarnedDate(),
                        "expiryDate", entitlement.getExpiryDate()));
    }

    /**
     * Marks every AVAILABLE entitlement whose 3-month window (counted from the original HPE holiday
     * date) has passed as EXPIRED. Runs daily; also safe to call manually.
     */
    @Scheduled(cron = "${application.hpe.expire-cron:0 30 1 * * *}")
    @Transactional
    public int expireHpeEntitlements() {
        return expireOverdue(appClock.today());
    }

    int expireOverdue(LocalDate today) {
        List<HPEEntitlement> overdue = entitlementRepository.findExpired(today);
        if (overdue.isEmpty()) {
            return 0;
        }
        for (HPEEntitlement entitlement : overdue) {
            entitlement.setStatus(HPEEntitlementStatus.EXPIRED);
            entitlementRepository.save(entitlement);
        }
        auditService.record("HPE_ENTITLEMENTS_EXPIRED", "HPEEntitlement", null,
                null, Map.of("count", overdue.size(), "asOf", today.toString()));
        return overdue.size();
    }

    // ------------------------------------------------------------------ HELPERS

    private boolean isOverdue(HPEEntitlement entitlement, LocalDate today) {
        return entitlement.getExpiryDate() != null && entitlement.getExpiryDate().isBefore(today);
    }

    private void assertOwnedBy(HPEEntitlement entitlement, Long employeeId) {
        if (entitlement.getEmployee() == null || !entitlement.getEmployee().getId().equals(employeeId)) {
            throw ApiException.forbidden("Cannot use another employee's entitlement");
        }
    }

    /**
     * Builds an audit-trail detail map from alternating key/value pairs.
     *
     * <p>{@code Map.of} cannot be used for these: it rejects null keys and values, and these
     * trails deliberately record "not applicable yet" as an empty string rather than dropping
     * the key, so the audit row always has the same shape. Insertion order is preserved so the
     * stored JSON reads in the order the fields are documented.</p>
     */
    private static Map<String, Object> auditMap(Object... keyValuePairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]),
                    keyValuePairs[i + 1] == null ? "" : keyValuePairs[i + 1]);
        }
        return map;
    }

    private static String employeeNameOf(HPEEntitlement e) {
        return e.getEmployee() == null ? null : e.getEmployee().getFullName();
    }

    private static Long employeeIdOf(HPEEntitlement e) {
        return e.getEmployee() == null ? null : e.getEmployee().getId();
    }

    private static String holidayNameOf(HPEEntitlement e) {
        return e.getHoliday() == null ? null : e.getHoliday().getName();
    }

    private static Long holidayIdOf(HPEEntitlement e) {
        return e.getHoliday() == null ? null : e.getHoliday().getId();
    }

    private static LocalDate holidayDateOf(HPEEntitlement e) {
        return e.getHoliday() == null ? null : e.getHoliday().getHolidayDate();
    }

    private Holiday requireEarnableHoliday(Long holidayId, Employee employee) {
        Holiday holiday = holidayRepository.findById(holidayId)
                .orElseThrow(() -> ApiException.notFound("HPE Holiday not found: " + holidayId));

        if (holiday.getHolidayType() != HolidayType.HPE_HOLIDAY) {
            throw ApiException.badRequest("Only HPE holidays can create an entitlement");
        }
        if (!holiday.isActive()) {
            throw ApiException.badRequest("This HPE holiday is no longer active");
        }
        if (!isApplicableToLocation(holiday, employee.getLocation())) {
            throw ApiException.badRequest("HPE holiday '" + holiday.getName() + "' does not apply to your location ("
                    + employee.getLocation() + ")");
        }
        if (holiday.getHolidayDate().isAfter(appClock.today())) {
            throw ApiException.badRequest("An entitlement can only be earned on or after the HPE holiday date");
        }
        return holiday;
    }

    /**
     * Rule A vs B: an entitlement is only earned by <b>working</b> on the HPE holiday date.
     * Roster status HPEH (took the holiday), week off, leave, ... never earn an entitlement,
     * and a date with no recorded status never implicitly counts as work.
     */
    private void assertWorkedOn(Employee employee, Holiday holiday) {
        DayWorkStatus day = workStatusService.resolve(employee, holiday.getHolidayDate());
        if (day.worked()) {
            return;
        }
        String on = "HPE holiday date " + holiday.getHolidayDate();
        if (day.status() == WorkStatus.NOT_WORKED && "HPEH".equals(day.code())) {
            throw ApiException.badRequest("Employee took the HPE holiday on " + holiday.getHolidayDate()
                    + " (roster status HPEH) - no compensatory entitlement is created for rule A");
        }
        if (day.status() == WorkStatus.NOT_WORKED) {
            throw ApiException.badRequest("Employee is not recorded as working on " + on
                    + " (status " + day.code() + " via " + day.source() + ")"
                    + " - an entitlement is only earned by working on the HPE holiday");
        }
        throw ApiException.badRequest("No working status is recorded for " + on
                + " - an entitlement is only earned by actually working on the HPE holiday");
    }

    /** One entitlement per (employee, holiday), expiry = original holiday date + {@link #EXPIRY_MONTHS} calendar months. */
    private HPEEntitlement newEntitlement(Employee employee, Holiday holiday) {
        return HPEEntitlement.builder()
                .employee(employee)
                .holiday(holiday)
                .earnedDate(holiday.getHolidayDate())
                .expiryDate(holiday.getHolidayDate().plusMonths(EXPIRY_MONTHS))
                .status(HPEEntitlementStatus.AVAILABLE)
                .build();
    }

    private Map<String, Object> earningValues(Employee employee, Holiday holiday, HPEEntitlement saved) {
        return Map.of("employeeId", employee.getId(),
                "holidayId", holiday.getId(),
                "holidayDate", holiday.getHolidayDate().toString(),
                "expiryDate", saved.getExpiryDate().toString());
    }

    private Employee getEmployee(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("Employee not found: " + employeeId));
    }

    private HolidayDtos.HPEHolidayResponse toHpeHolidayResponse(Holiday holiday) {
        return new HolidayDtos.HPEHolidayResponse(
                holiday.getId(),
                holiday.getName(),
                holiday.getHolidayDate(),
                holiday.getCountry(),
                holiday.getDescription(),
                holiday.getApplicableLocations(),
                true
        );
    }

    private HolidayDtos.HPEEntitlementResponse toEntitlementResponse(HPEEntitlement entitlement) {
        return new HolidayDtos.HPEEntitlementResponse(
                entitlement.getId(),
                entitlement.getEmployee().getId(),
                entitlement.getEmployee().getFullName(),
                entitlement.getHoliday().getId(),
                entitlement.getHoliday().getName(),
                entitlement.getHoliday().getHolidayDate(),
                entitlement.getEarnedDate(),
                entitlement.getExpiryDate(),
                entitlement.getStatus().name(),
                entitlement.getUsedDate(),
                entitlement.getUsedRequestId(),
                entitlement.getReservedRequestId(),
                entitlement.getUsedOffDate(),
                entitlement.getNotes()
        );
    }
}
