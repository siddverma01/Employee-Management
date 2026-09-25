package com.emplmgt.controller;

import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.HolidayType;
import com.emplmgt.service.HolidayService;
import com.emplmgt.service.HPEEntitlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin CRUD over the master HPE holiday definitions (holidayType is always forced to HPE_HOLIDAY).
 * Employee-specific rows are never managed here - they live in hpe_entitlements.
 */
@RestController
@RequestMapping("/api/admin/hpe-holidays")
@RequiredArgsConstructor
public class AdminHPEHolidayController {

    private final HolidayService holidayService;
    private final HPEEntitlementService entitlementService;

    @GetMapping
    public ResponseEntity<List<HolidayDtos.Response>> listHpeHolidays() {
        return ResponseEntity.ok(holidayService.listHpeHolidays());
    }

    @PostMapping
    public ResponseEntity<HolidayDtos.Response> createHpeHoliday(
            @Valid @RequestBody HolidayDtos.HolidayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holidayService.create(asHpeHoliday(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HolidayDtos.Response> updateHpeHoliday(
            @PathVariable Long id,
            @Valid @RequestBody HolidayDtos.HolidayRequest request) {
        return ResponseEntity.ok(holidayService.update(id, asHpeHoliday(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHpeHoliday(@PathVariable Long id) {
        holidayService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * System-driven earning (rule B): scans this master HPE holiday and awards one entitlement to
     * every eligible employee recorded as having worked on it. Idempotent.
     */
    @PostMapping("/{id}/entitlements/sync")
    public ResponseEntity<HolidayDtos.HpeEntitlementSyncResponse> syncEntitlements(@PathVariable Long id) {
        return ResponseEntity.ok(entitlementService.syncEntitlements(id));
    }

    /**
     * Scans every active master HPE holiday that has already taken place.
     */
    @PostMapping("/entitlements/sync")
    public ResponseEntity<List<HolidayDtos.HpeEntitlementSyncResponse>> syncAllEntitlements() {
        return ResponseEntity.ok(entitlementService.syncAllDueEntitlements());
    }

    private HolidayDtos.HolidayRequest asHpeHoliday(HolidayDtos.HolidayRequest request) {
        if (request.holidayType() == HolidayType.HPE_HOLIDAY) {
            return request;
        }
        return new HolidayDtos.HolidayRequest(
                request.name(),
                request.date(),
                request.country(),
                HolidayType.HPE_HOLIDAY,
                request.description(),
                request.applicableLocations(),
                request.active(),
                request.scope(),
                request.teamId()
        );
    }
}
