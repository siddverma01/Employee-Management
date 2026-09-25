package com.emplmgt.controller;

import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.service.AttendanceStatusDetailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Status description (reason) for a roster attendance cell.
 *
 * <p>GET is available to any authenticated user (the roster is view-only for
 * normal users), while CREATE / UPDATE / DELETE are strictly ADMIN — enforced
 * server-side via the URL rule ({@code /api/admin/**} -> ROLE_ADMIN) and the
 * explicit {@code @PreAuthorize} below. The frontend never decides privilege.</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AttendanceStatusDetailController {

    private final AttendanceStatusDetailService detailService;

    @GetMapping("/roster/status-detail")
    public ResponseEntity<AttendanceRosterDtos.StatusDetail> get(
            @RequestParam String employeeId,
            @RequestParam LocalDate date) {
        return ResponseEntity.ok(detailService.get(employeeId, date));
    }

    @PostMapping("/admin/roster/status-detail")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AttendanceRosterDtos.StatusDetail> upsert(
            @Valid @RequestBody AttendanceRosterDtos.DescriptionUpsertRequest request) {
        return ResponseEntity.ok(detailService.upsert(request.employeeId(), request.date(), request.description()));
    }

    @DeleteMapping("/admin/roster/status-detail")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AttendanceRosterDtos.StatusDetail> clear(
            @RequestParam String employeeId,
            @RequestParam LocalDate date) {
        return ResponseEntity.ok(detailService.clear(employeeId, date));
    }
}