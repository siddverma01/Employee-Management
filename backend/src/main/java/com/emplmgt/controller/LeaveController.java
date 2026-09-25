package com.emplmgt.controller;

import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.dto.LeaveDtos;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leaves")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<LeaveDtos.Response> apply(@Valid @RequestBody LeaveDtos.ApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaveService.apply(securityUtils.currentUserId(), request));
    }

    @GetMapping
    public ResponseEntity<List<LeaveDtos.Response>> myLeaves() {
        return ResponseEntity.ok(leaveService.myLeaves(securityUtils.currentUserId()));
    }

    @GetMapping("/balances")
    public ResponseEntity<List<EmployeeDtos.LeaveBalanceDto>> balances() {
        return ResponseEntity.ok(leaveService.myBalances(securityUtils.currentUserId()));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<LeaveDtos.Response> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(leaveService.cancel(securityUtils.currentUserId(), id));
    }
}