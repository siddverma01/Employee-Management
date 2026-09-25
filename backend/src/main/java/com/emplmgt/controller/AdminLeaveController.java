package com.emplmgt.controller;

import com.emplmgt.dto.LeaveDtos;
import com.emplmgt.dto.PageResponse;
import com.emplmgt.entity.LeaveStatus;
import com.emplmgt.entity.LeaveType;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/leaves")
@RequiredArgsConstructor
public class AdminLeaveController {

    private final LeaveService leaveService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<PageResponse<LeaveDtos.Response>> list(
            @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) LeaveType leaveType,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sort) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(sort).descending());
        return ResponseEntity.ok(PageResponse.of(
                leaveService.search(status, leaveType, employeeId, departmentId, from, to, search, pageable)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<LeaveDtos.Response> approve(@PathVariable Long id) {
        return ResponseEntity.ok(leaveService.approve(securityUtils.currentUserId(), id));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<LeaveDtos.Response> reject(@PathVariable Long id,
                                                     @Valid @RequestBody LeaveDtos.DecideRequest request) {
        return ResponseEntity.ok(leaveService.reject(securityUtils.currentUserId(), id, request.rejectionReason()));
    }
}