package com.emplmgt.controller;

import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.service.DepartmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/departments")
@RequiredArgsConstructor
public class AdminDepartmentController {

    private final DepartmentService departmentService;

    @PostMapping
    public ResponseEntity<EmployeeDtos.DepartmentDto> create(@Valid @RequestBody EmployeeDtos.DepartmentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(departmentService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmployeeDtos.DepartmentDto> update(@PathVariable Long id,
                                                             @Valid @RequestBody EmployeeDtos.DepartmentCreateRequest request) {
        return ResponseEntity.ok(departmentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        departmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}