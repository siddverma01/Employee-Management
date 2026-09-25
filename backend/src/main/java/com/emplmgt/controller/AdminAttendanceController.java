package com.emplmgt.controller;

import com.emplmgt.dto.AttendanceDtos;
import com.emplmgt.dto.PageResponse;
import com.emplmgt.entity.AttendanceType;
import com.emplmgt.service.AttendanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/attendance")
@RequiredArgsConstructor
public class AdminAttendanceController {

    private final AttendanceService attendanceService;

    @GetMapping
    public ResponseEntity<PageResponse<AttendanceDtos.Response>> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) AttendanceType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("attendanceDate").descending());
        return ResponseEntity.ok(PageResponse.of(attendanceService.search(from, to, employeeId, departmentId, type, pageable)));
    }

    @PostMapping
    public ResponseEntity<AttendanceDtos.Response> upsert(@Valid @RequestBody AttendanceDtos.ManualCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(attendanceService.upsert(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        attendanceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}