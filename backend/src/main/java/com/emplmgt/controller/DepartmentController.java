package com.emplmgt.controller;

import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping
    public ResponseEntity<List<EmployeeDtos.DepartmentDto>> list() {
        return ResponseEntity.ok(departmentService.list());
    }
}