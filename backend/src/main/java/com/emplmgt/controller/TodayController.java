package com.emplmgt.controller;

import com.emplmgt.dto.DashboardDtos;
import com.emplmgt.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/today")
@RequiredArgsConstructor
public class TodayController {

    private final AttendanceService attendanceService;

    @GetMapping("/status")
    public ResponseEntity<DashboardDtos.TodayStatus> status(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String location) {
        return ResponseEntity.ok(attendanceService.todayStatus(teamId, location));
    }
}