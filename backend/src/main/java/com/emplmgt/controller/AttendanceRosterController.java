package com.emplmgt.controller;

import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.service.AttendanceRosterService;
import com.emplmgt.service.RosterExportService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/admin/roster/monthly")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AttendanceRosterController {

    private final AttendanceRosterService attendanceRosterService;
    private final RosterExportService rosterExportService;

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

    @GetMapping("/today")
    public ResponseEntity<AttendanceRosterDtos.TodayResponse> today(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String shift) {
        return ResponseEntity.ok(attendanceRosterService.today(teamId, q, status, location, shift));
    }

    @PostMapping("/save")
    public ResponseEntity<AttendanceRosterDtos.BatchSaveResponse> save(
            @Valid @RequestBody AttendanceRosterDtos.BatchSaveRequest request) {
        return ResponseEntity.ok(attendanceRosterService.save(request));
    }

    /**
     * Downloads the live roster as a formatted Excel workbook, shaped exactly like
     * the historical import template so it can be uploaded straight back.
     *
     * <p>Filters mirror the grid so what is downloaded is what is on screen
     * (team, employee search, status, location, shift). {@code scope} picks the
     * width of the export: the selected {@code month}, the current calendar
     * month, or every month of {@code year} that holds attendance — one sheet each.
     * {@code includeEmpty} also lists filtered employees with no attendance that
     * month; off by default so the workbook matches the grid and omits the junk
     * rows that the historical leave-tracker sheets leave in the import tables.
     * Admin-only via the class-level {@code @PreAuthorize}.</p>
     */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false, defaultValue = "MONTH") String scope,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String shift,
            @RequestParam(required = false, defaultValue = "false") boolean includeEmpty,
            HttpServletResponse response) throws IOException {
        RosterExportService.ExportRequest request = new RosterExportService.ExportRequest(
                RosterExportService.Scope.of(scope), month, year, teamId, q, location, shift, status,
                includeEmpty);

        // The filename is resolved before streaming so a validation failure can
        // still be reported as a normal JSON error rather than a truncated file.
        RosterExportService.Plan plan = rosterExportService.plan(request);
        String filename = rosterExportService.filenameFor(plan);

        response.setContentType(RosterExportService.contentType());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encode(filename));

        rosterExportService.export(response.getOutputStream(), request);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}