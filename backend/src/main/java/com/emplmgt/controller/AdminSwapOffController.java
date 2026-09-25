package com.emplmgt.controller;

import com.emplmgt.dto.PageResponse;
import com.emplmgt.dto.SwapOffDtos;
import com.emplmgt.entity.LeaveStatus;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.SwapOffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin/swap-offs")
@RequiredArgsConstructor
public class AdminSwapOffController {

    private final SwapOffService swapOffService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<PageResponse<SwapOffDtos.Response>> list(
            @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sort) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(sort).descending());
        return ResponseEntity.ok(PageResponse.of(
                swapOffService.search(status, employeeId, departmentId, from, to, search, pageable)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<SwapOffDtos.Response> approve(@PathVariable Long id) {
        return ResponseEntity.ok(swapOffService.approve(securityUtils.currentUserId(), id));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<SwapOffDtos.Response> reject(@PathVariable Long id,
                                                       @Valid @RequestBody SwapOffDtos.DecideRequest request) {
        return ResponseEntity.ok(swapOffService.reject(securityUtils.currentUserId(), id, request.rejectionReason()));
    }
}