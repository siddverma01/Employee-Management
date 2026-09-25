package com.emplmgt.controller;

import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.service.AttendanceRosterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the month-wise Attendance Roster, available to any
 * authenticated user. Normal users see the grid read-only (they may open the
 * status description popup and read reasons); only the {@code /api/admin/**}
 * write endpoints may change statuses or descriptions.
 */
@RestController
@RequestMapping("/api/roster/monthly")
@RequiredArgsConstructor
public class RosterReadController {

    private final AttendanceRosterService attendanceRosterService;

    @GetMapping
    public ResponseEntity<AttendanceRosterDtos.MonthlyResponse> monthly(
            @RequestParam(required = false) Long teamId,
            @RequestParam String month,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String shift,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(attendanceRosterService
                .monthly(teamId, month, q, status, location, shift, page, size));
    }

    @GetMapping("/meta")
    public ResponseEntity<AttendanceRosterDtos.PageMeta> meta(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(attendanceRosterService.meta(teamId, month));
    }
}