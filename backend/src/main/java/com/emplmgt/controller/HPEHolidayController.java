package com.emplmgt.controller;

import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.Employee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.HPEEntitlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Employee self-service endpoints for HPE holidays and earned HPEH entitlements.
 */
@RestController
@RequestMapping("/api/hpe-holidays")
@RequiredArgsConstructor
public class HPEHolidayController {

    private final HPEEntitlementService entitlementService;
    private final SecurityUtils securityUtils;
    private final EmployeeRepository employeeRepository;

    /** Master HPE holidays applicable to the current employee's location. */
    @GetMapping
    public ResponseEntity<List<HolidayDtos.HPEHolidayResponse>> getApplicableHpeHolidays() {
        return ResponseEntity.ok(entitlementService.getApplicableHpeHolidays(getEmployeeId()));
    }

    /** Entitlements that can still be availed (AVAILABLE and not past expiry). */
    @GetMapping("/entitlements")
    public ResponseEntity<List<HolidayDtos.HPEEntitlementResponse>> getMyEntitlements() {
        return ResponseEntity.ok(entitlementService.getAvailableHpeEntitlements(getEmployeeId()));
    }

    @GetMapping("/entitlements/all")
    public ResponseEntity<List<HolidayDtos.HPEEntitlementResponse>> getAllMyEntitlements() {
        return ResponseEntity.ok(entitlementService.getAllEntitlements(getEmployeeId()));
    }

    @GetMapping("/entitlements/summary")
    public ResponseEntity<HolidayDtos.EntitlementStatusSummary> getEntitlementSummary() {
        return ResponseEntity.ok(entitlementService.getEntitlementStatusSummary(getEmployeeId()));
    }

    /** Rule B: earns an entitlement after working on the HPE holiday date. */
    @PostMapping("/entitlements")
    public ResponseEntity<HolidayDtos.HPEEntitlementResponse> createEntitlement(
            @Valid @RequestBody HolidayDtos.HPEEntitlementCreateRequest request) {
        return ResponseEntity.ok(entitlementService.createHpeEntitlement(getEmployeeId(), request.holidayId()));
    }

    /** Avails an earned entitlement (single use, never after its expiry date). */
    @PostMapping("/entitlements/use")
    public ResponseEntity<HolidayDtos.HPEEntitlementResponse> useEntitlement(
            @Valid @RequestBody HolidayDtos.HPEEntitlementUseRequest request) {
        return ResponseEntity.ok(entitlementService.useHpeEntitlement(
                getEmployeeId(), request.entitlementId(), request.requestId()));
    }

    private Long getEmployeeId() {
        Long userId = securityUtils.currentUserId();
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Employee profile not found for the current user"));
        return employee.getId();
    }
}
