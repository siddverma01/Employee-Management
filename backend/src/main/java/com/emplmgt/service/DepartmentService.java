package com.emplmgt.service;

import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.entity.Department;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<EmployeeDtos.DepartmentDto> list() {
        return departmentRepository.findAllByOrderByNameAsc().stream()
                .map(d -> new EmployeeDtos.DepartmentDto(d.getId(), d.getName(), d.getDescription()))
                .toList();
    }

    @Transactional
    public EmployeeDtos.DepartmentDto create(EmployeeDtos.DepartmentCreateRequest request) {
        if (departmentRepository.findByNameIgnoreCase(request.name()).isPresent()) {
            throw ApiException.conflict("Department '" + request.name() + "' already exists");
        }
        Department department = Department.builder()
                .name(request.name().trim())
                .description(request.description())
                .build();
        Department saved = departmentRepository.save(department);
        return new EmployeeDtos.DepartmentDto(saved.getId(), saved.getName(), saved.getDescription());
    }

    @Transactional
    public EmployeeDtos.DepartmentDto update(Long id, EmployeeDtos.DepartmentCreateRequest request) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Department not found"));
        department.setName(request.name().trim());
        department.setDescription(request.description());
        Department saved = departmentRepository.save(department);
        return new EmployeeDtos.DepartmentDto(saved.getId(), saved.getName(), saved.getDescription());
    }

    @Transactional
    public void delete(Long id) {
        long employees = employeeRepository.search(null, null, id, org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
        if (employees > 0) {
            throw ApiException.badRequest("Cannot delete department with assigned employees");
        }
        departmentRepository.deleteById(id);
    }

    public Department getOrCreate(String name) {
        return departmentRepository.findByNameIgnoreCase(name)
                .orElseGet(() -> departmentRepository.save(Department.builder().name(name).build()));
    }
}