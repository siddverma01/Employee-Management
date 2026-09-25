package com.emplmgt.controller;

import com.emplmgt.dto.DashboardDtos;
import com.emplmgt.dto.TeamDtos;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.AttendanceService;
import com.emplmgt.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final AttendanceService attendanceService;
    private final SecurityUtils securityUtils;

    @GetMapping("/me")
    public ResponseEntity<DashboardDtos.EmployeeDashboard> mine() {
        return ResponseEntity.ok(dashboardService.employeeDashboard(securityUtils.currentUserId()));
    }

    @GetMapping("/team")
    public ResponseEntity<TeamDtos.TeamDashboard> team(@RequestParam(required = false) Long teamId) {
        return ResponseEntity.ok(attendanceService.teamStatus(teamId));
    }
}