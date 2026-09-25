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
import java.util.List;
import java.util.Map;
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
        long used = all.stream().filter(e -> e.getStatus() == HPEEntitlementStatus.USED).count();
        long expired = all.stream().filter(e -> e.getStatus() == HPEEntitlementStatus.EXPIRED
                || (e.getStatus() == HPEEntitlementStatus.AVAILABLE && isOverdue(e, today))).count();
        return new HolidayDtos.EntitlementStatusSummary(available, used, expired);
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
     * Avails a previously earned entitlement. It can never be used twice and never after its expiry date.
     */
    @Transactional
    public HolidayDtos.HPEEntitlementResponse useHpeEntitlement(Long employeeId, Long entitlementId, Long requestId) {
        HPEEntitlement entitlement = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> ApiException.notFound("Entitlement not found: " + entitlementId));

        if (entitlement.getEmployee() == null || !entitlement.getEmployee().getId().equals(employeeId)) {
            throw ApiException.forbidden("Cannot use another employee's entitlement");
        }

        LocalDate today = appClock.today();
        if (entitlement.getStatus() == HPEEntitlementStatus.USED) {
            throw ApiException.badRequest("Entitlement has already been used");
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
        HPEEntitlement saved = entitlementRepository.save(entitlement);

        auditService.record("HPE_ENTITLEMENT_USED", "HPEEntitlement", String.valueOf(saved.getId()),
                Map.of("status", HPEEntitlementStatus.AVAILABLE.name()),
                Map.of("status", HPEEntitlementStatus.USED.name(),
                        "usedDate", today.toString(),
                        "requestId", String.valueOf(requestId)));
        return toEntitlementResponse(saved);
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
                entitlement.getNotes()
        );
    }
}
