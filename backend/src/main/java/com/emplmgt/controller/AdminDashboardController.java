package com.emplmgt.controller;

import com.emplmgt.dto.DashboardDtos;
import com.emplmgt.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ResponseEntity<DashboardDtos.AdminSummary> summary(@RequestParam(required = false) Long teamId) {
        return ResponseEntity.ok(dashboardService.adminSummary(teamId));
    }

    @GetMapping("/charts")
    public ResponseEntity<Map<String, Object>> charts(
            @RequestParam String name,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long teamId) {
        Map<String, Object> data = switch (name) {
            case "leaveUsage" -> dashboardService.leaveUsageByType(from, to, teamId);
            case "wfhWfo" -> dashboardService.wfhVsWfo(from, to, teamId);
            case "monthlyTrend" -> dashboardService.monthlyTrend(from, to, teamId);
            case "departmentLeave" -> dashboardService.departmentLeaveUsage(from, to);
            default -> throw com.emplmgt.exception.ApiException.badRequest("Unknown chart: " + name);
        };
        return ResponseEntity.ok(data);
    }
}