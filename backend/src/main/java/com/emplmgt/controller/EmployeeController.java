package com.emplmgt.controller;

import com.emplmgt.dto.DashboardDtos;
import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.exception.ApiException;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.EmployeeService;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.util.AppClock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
    private final AppClock appClock;

    @GetMapping("/me")
    public ResponseEntity<EmployeeDtos.Profile> myProfile() {
        return ResponseEntity.ok(employeeService.getProfileForUser(securityUtils.currentUserId()));
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