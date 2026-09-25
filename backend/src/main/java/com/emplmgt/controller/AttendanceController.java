package com.emplmgt.controller;

import com.emplmgt.dto.AttendanceDtos;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final SecurityUtils securityUtils;

    @GetMapping("/me")
    public ResponseEntity<List<AttendanceDtos.Response>> myAttendance(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(attendanceService.myAttendance(securityUtils.currentUserId(), YearMonth.of(year, month)));
    }
}