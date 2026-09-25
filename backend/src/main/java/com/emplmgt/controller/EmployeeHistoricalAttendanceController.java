package com.emplmgt.controller;

import com.emplmgt.dto.EmployeeHistoricalAttendanceDtos;
import com.emplmgt.service.EmployeeHistoricalAttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/employees/{employeeId}/historical-attendance")
@RequiredArgsConstructor
public class EmployeeHistoricalAttendanceController {

    private final EmployeeHistoricalAttendanceService employeeHistoricalAttendanceService;

    @GetMapping
    public ResponseEntity<EmployeeHistoricalAttendanceDtos.ProfileResponse> profile(
            @PathVariable Long employeeId,
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(employeeHistoricalAttendanceService.profile(employeeId, month));
    }
}