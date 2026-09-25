package com.emplmgt.controller;

import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.dto.PageResponse;
import com.emplmgt.entity.EmploymentStatus;
import com.emplmgt.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/employees")
@RequiredArgsConstructor
public class AdminEmployeeController {

    private final EmployeeService employeeService;

    @GetMapping
    public ResponseEntity<PageResponse<EmployeeDtos.Summary>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) EmploymentStatus status,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "fullName") String sort) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(sort).ascending());
        return ResponseEntity.ok(PageResponse.of(employeeService.search(search, status, departmentId, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeDtos.Profile> get(@PathVariable Long id) {
        return ResponseEntity.ok(employeeService.getProfile(id));
    }

    @PostMapping
    public ResponseEntity<EmployeeDtos.Profile> create(@Valid @RequestBody EmployeeDtos.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmployeeDtos.Profile> update(@PathVariable Long id,
                                                       @Valid @RequestBody EmployeeDtos.UpdateRequest request) {
        return ResponseEntity.ok(employeeService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<EmployeeDtos.Summary> setStatus(@PathVariable Long id,
                                                          @RequestParam EmploymentStatus status) {
        return ResponseEntity.ok(employeeService.setStatus(id, status));
    }
}