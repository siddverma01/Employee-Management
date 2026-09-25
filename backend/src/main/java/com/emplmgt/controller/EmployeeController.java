package com.emplmgt.controller;

import com.emplmgt.dto.DashboardDtos;
import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.EmployeeService;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;
    private final SecurityUtils securityUtils;
    private final EmployeeRepository employeeRepository;
    private final ImportEmployeeRepository importEmployeeRepository;
    private final AppClock appClock;

    @GetMapping("/me")
    public ResponseEntity<EmployeeDtos.Profile> myProfile() {
        return ResponseEntity.ok(employeeService.getProfileForUser(securityUtils.currentUserId()));
    }

    @GetMapping("/search")
    public ResponseEntity<List<EmployeeDtos.Simple>> searchEmployees(@RequestParam String q) {
        Long currentUserId = securityUtils.currentUserId();
        Employee currentEmployee = employeeRepository.findByUserId(currentUserId)
                .orElseThrow(() -> ApiException.notFound("Employee profile not found"));
        List<EmployeeDtos.Simple> results = employeeRepository.searchByNameOrCode(q)
                .stream()
                .filter(e -> e.getEmploymentStatus() == com.emplmgt.entity.EmploymentStatus.ACTIVE)
                .filter(e -> !e.getId().equals(currentEmployee.getId())) // Exclude self
                .map(e -> new EmployeeDtos.Simple(e.getId(), e.getEmployeeCode(), e.getFullName(),
                        e.getDepartment() != null ? e.getDepartment().getName() : null))
                .limit(20)
                .toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/roster-search")
    public ResponseEntity<List<EmployeeDtos.Simple>> searchRosterEmployees(@RequestParam String q) {
        Long currentUserId = securityUtils.currentUserId();
        Employee currentEmployee = employeeRepository.findByUserId(currentUserId)
                .orElseThrow(() -> ApiException.notFound("Employee profile not found"));
        String currentEmployeeCode = currentEmployee.getEmployeeCode();
        
        List<ImportEmployee> importEmployees = importEmployeeRepository.findAllActiveForDropdown(q);
        List<EmployeeDtos.Simple> results = importEmployees.stream()
                .filter(e -> !e.getEmployeeId().equals(currentEmployeeCode)) // Exclude self
                .map(e -> new EmployeeDtos.Simple(null, e.getEmployeeId(), e.getEmployeeName(),
                        e.getTeamId() != null ? e.getTeamId().toString() : null))
                .toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/roster-month-search")
    public ResponseEntity<List<EmployeeDtos.Simple>> searchRosterMonthEmployees(@RequestParam String month,
                                                                                 @RequestParam(required = false) String q) {
        Long currentUserId = securityUtils.currentUserId();
        Employee currentEmployee = employeeRepository.findByUserId(currentUserId)
                .orElseThrow(() -> ApiException.notFound("Employee profile not found"));
        String currentEmployeeCode = currentEmployee.getEmployeeCode();
        
        YearMonth ym = YearMonth.parse(month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        
        List<ImportEmployee> importEmployees = importEmployeeRepository.findForMonthDropdown(from, to, q != null ? q : "");
        List<EmployeeDtos.Simple> results = importEmployees.stream()
                .filter(e -> !e.getEmployeeId().equals(currentEmployeeCode)) // Exclude self
                .map(e -> new EmployeeDtos.Simple(null, e.getEmployeeId(), e.getEmployeeName(),
                        e.getTeamId() != null ? e.getTeamId().toString() : null))
                .toList();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/birthdays/upcoming")
    public ResponseEntity<List<DashboardDtos.UpcomingItem>> upcomingBirthdays(
            @RequestParam(name = "days", defaultValue = "30") int days) {
        int requested = Math.min(days, 90);
        LocalDate today = appClock.today();
        List<DashboardDtos.UpcomingItem> items = new ArrayList<>();
        for (var emp : employeeRepository.findByEmploymentStatus(com.emplmgt.entity.EmploymentStatus.ACTIVE)) {
            if (emp.getDateOfBirth() == null) {
                continue;
            }
            LocalDate dob = emp.getDateOfBirth();
            LocalDate next;
            try {
                next = LocalDate.of(today.getYear(), dob.getMonthValue(), dob.getDayOfMonth());
            } catch (Exception ex) {
                continue;
            }
            if (next.isBefore(today)) {
                try {
                    next = LocalDate.of(today.getYear() + 1, dob.getMonthValue(), dob.getDayOfMonth());
                } catch (Exception ex) {
                    continue;
                }
            }
            if (!next.isAfter(today.plusDays(requested - 1))) {
                items.add(new DashboardDtos.UpcomingItem(next, emp.getFullName(), "BIRTHDAY"));
            }
        }
        items.sort(Comparator.comparing(DashboardDtos.UpcomingItem::date));
        return ResponseEntity.ok(items);
    }
}