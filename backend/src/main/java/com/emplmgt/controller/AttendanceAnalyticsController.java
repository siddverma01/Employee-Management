package com.emplmgt.controller;

import com.emplmgt.dto.AttendanceAnalyticsDtos;
import com.emplmgt.service.AttendanceAnalyticsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;

@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AttendanceAnalyticsController {

    private final AttendanceAnalyticsService analyticsService;

    @GetMapping("/meta")
    public ResponseEntity<AttendanceAnalyticsDtos.MetaResponse> meta(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long teamId) {
        return ResponseEntity.ok(analyticsService.meta(
                new AttendanceAnalyticsDtos.MetaQuery(month, year, teamId)));
    }

    @PostMapping("/employees")
    public ResponseEntity<AttendanceAnalyticsDtos.EmployeeStatsResponse> getEmployeeStats(
            @Valid @RequestBody AttendanceAnalyticsDtos.AnalyticsQuery query) {
        return ResponseEntity.ok(analyticsService.getEmployeeStats(query));
    }

    @GetMapping("/employees/{employeeCode}")
    public ResponseEntity<AttendanceAnalyticsDtos.EmployeeDetailResponse> getEmployeeDetail(
            @PathVariable String employeeCode,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) AttendanceAnalyticsDtos.ReportMode mode,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String status) {

        AttendanceAnalyticsDtos.AnalyticsQuery query = new AttendanceAnalyticsDtos.AnalyticsQuery(
                month, year, AttendanceAnalyticsDtos.ReportMode.OVERALL, null, null, null, null, null, 0, 30, null, null
        );
        return ResponseEntity.ok(analyticsService.getEmployeeDetail(employeeCode, query));
    }

    @PostMapping("/export")
    public ResponseEntity<Resource> export(@Valid @RequestBody AttendanceAnalyticsDtos.ExportRequest request) {
        return analyticsService.export(request);
    }
}