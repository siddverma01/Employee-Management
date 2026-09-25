package com.emplmgt.service;

import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.entity.*;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.*;
import com.emplmgt.util.AppClock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppClock appClock;
    private final AuditService auditService;

    @Value("${application.seed.default-password:Welcome@123}")
    private String defaultPassword;

    @Value("${application.employee.default-allocations:PRIVILEGE_LEAVE=20,SICK_LEAVE=12,COMP_OFF=0}")
    private String defaultAllocations;

    @Transactional(readOnly = true)
    public EmployeeDtos.Profile getProfileForUser(Long userId) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Employee profile not found for the current user"));
        return toProfile(employee);
    }

    @Transactional(readOnly = true)
    public EmployeeDtos.Profile getProfile(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("Employee not found"));
        return toProfile(employee);
    }

    @Transactional(readOnly = true)
    public Page<EmployeeDtos.Summary> search(String search, EmploymentStatus status, Long departmentId, Pageable pageable) {
        return employeeRepository.search(search, status, departmentId, pageable)
                .map(this::toSummary);
    }

    @Transactional
    public EmployeeDtos.Profile create(EmployeeDtos.CreateRequest request) {
        if (employeeRepository.existsByEmployeeCodeIgnoreCase(request.employeeCode())) {
            throw ApiException.conflict("Employee code already exists: " + request.employeeCode());
        }
        if (employeeRepository.existsByEmailIgnoreCase(request.email())) {
            throw ApiException.conflict("An employee with email " + request.email() + " already exists");
        }
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw ApiException.conflict("A user account with email " + request.email() + " already exists");
        }
        if (request.departmentId() == null) {
            throw ApiException.badRequest("A team is required - every employee must belong to a primary team");
        }

        Department department = request.departmentId() != null
                ? departmentRepository.findById(request.departmentId())
                        .orElseThrow(() -> ApiException.badRequest("Team not found: " + request.departmentId()))
                : null;
        Employee manager = resolveManager(request.managerId());
        if (request.dateOfJoining().isAfter(appClock.today())) {
            throw ApiException.badRequest("Date of joining cannot be in the future");
        }

        String rawPassword = request.password() == null || request.password().isBlank()
                ? defaultPassword : request.password();
        if (rawPassword.length() < 6) {
            throw ApiException.badRequest("Password must be at least 6 characters");
        }

        Role role = request.role() == null ? Role.EMPLOYEE : request.role();
        User user = User.builder()
                .email(request.email().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .build();
        user = userRepository.save(user);

        Employee employee = Employee.builder()
                .employeeCode(request.employeeCode().trim().toUpperCase())
                .user(user)
                .email(request.email().trim().toLowerCase())
                .fullName(request.fullName().trim())
                .department(department)
                .manager(manager)
                .phone(request.phone())
                .designation(request.designation())
                .location(request.location())
                .shift(request.shift())
                .weekOff(request.weekOff())
                .dateOfJoining(request.dateOfJoining())
                .dateOfBirth(request.dateOfBirth())
                .employmentStatus(EmploymentStatus.ACTIVE)
                .build();
        Employee saved = employeeRepository.save(employee);
        ensureBalances(saved, request.dateOfJoining().getYear());

        auditService.record("EMPLOYEE_CREATED", "Employee", String.valueOf(saved.getId()),
                null, Map.of("employeeCode", saved.getEmployeeCode(), "email", saved.getEmail(), "role", role.name()));

        return toProfile(saved);
    }

    @Transactional
    public EmployeeDtos.Profile update(Long employeeId, EmployeeDtos.UpdateRequest request) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("Employee not found"));
        if (!employee.getEmail().equalsIgnoreCase(request.email())
                && employeeRepository.existsByEmailIgnoreCase(request.email())) {
            throw ApiException.conflict("Email already used by another employee");
        }
        if (!employee.getEmail().equalsIgnoreCase(request.email())) {
            if (userRepository.existsByEmailIgnoreCase(request.email())) {
                throw ApiException.conflict("Email already used by another user account");
            }
        }

        Map<String, Object> oldValue = new HashMap<>();
        oldValue.put("fullName", employee.getFullName());
        oldValue.put("email", employee.getEmail());
        oldValue.put("designation", employee.getDesignation());
        oldValue.put("departmentId", employee.getDepartment() != null ? employee.getDepartment().getId() : null);
        oldValue.put("location", employee.getLocation());

        employee.setFullName(request.fullName().trim());
        employee.setEmail(request.email().trim().toLowerCase());
        if (request.departmentId() != null) {
            employee.setDepartment(departmentRepository.findById(request.departmentId()).orElse(null));
        }
        employee.setManager(resolveManager(request.managerId()));
        employee.setPhone(request.phone());
        employee.setDesignation(request.designation());
        employee.setLocation(request.location());
        employee.setShift(request.shift());
        employee.setWeekOff(request.weekOff());
        if (request.dateOfJoining() != null) {
            employee.setDateOfJoining(request.dateOfJoining());
        }
        if (request.dateOfBirth() != null) {
            employee.setDateOfBirth(request.dateOfBirth());
        }
        if (employee.getUser() != null) {
            employee.getUser().setEmail(request.email().trim().toLowerCase());
            userRepository.save(employee.getUser());
        }
        Employee saved = employeeRepository.save(employee);

        Map<String, Object> newValue = new HashMap<>();
        newValue.put("fullName", saved.getFullName());
        newValue.put("email", saved.getEmail());
        newValue.put("designation", saved.getDesignation());
        newValue.put("departmentId", saved.getDepartment() != null ? saved.getDepartment().getId() : null);
        newValue.put("location", saved.getLocation());
        auditService.record("EMPLOYEE_UPDATED", "Employee", String.valueOf(saved.getId()), oldValue, newValue);

        return toProfile(saved);
    }

    @Transactional
    public EmployeeDtos.Summary setStatus(Long employeeId, EmploymentStatus status) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("Employee not found"));
        EmployeeDtos.Summary old = toSummary(employee);
        employee.setEmploymentStatus(status);
        if (employee.getUser() != null) {
            employee.getUser().setEnabled(status == EmploymentStatus.ACTIVE);
            userRepository.save(employee.getUser());
        }
        Employee saved = employeeRepository.save(employee);
        auditService.record("EMPLOYEE_STATUS_CHANGED", "Employee", String.valueOf(saved.getId()),
                Map.of("status", old.employmentStatus().name()), Map.of("status", status.name()));
        return toSummary(saved);
    }

    @Transactional
    public Employee getEmployeeByUser(Long userId) {
        return employeeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Employee profile not found"));
    }

    @Transactional(readOnly = true)
    public EmployeeDtos.Profile toProfile(Employee employee) {
        EmployeeDtos.Stats stats = computeStats(employee);
        List<EmployeeDtos.LeaveBalanceDto> balances = balancesFor(employee, appClock.today().getYear());
        return new EmployeeDtos.Profile(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                employee.getEmail(),
                employee.getPhone(),
                employee.getDepartment() != null ? employee.getDepartment().getName() : null,
                employee.getDepartment() != null ? employee.getDepartment().getId() : null,
                employee.getDesignation(),
                employee.getManager() != null ? employee.getManager().getFullName() : null,
                employee.getManager() != null ? employee.getManager().getId() : null,
                employee.getLocation(),
                employee.getShift(),
                employee.getWeekOff(),
                employee.getDateOfJoining(),
                employee.getDateOfBirth(),
                employee.getEmploymentStatus(),
                employee.getProfilePicture(),
                stats,
                balances);
    }

    public EmployeeDtos.Summary toSummary(Employee employee) {
        return new EmployeeDtos.Summary(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                employee.getEmail(),
                employee.getPhone(),
                employee.getDesignation(),
                employee.getDepartment() != null ? employee.getDepartment().getName() : null,
                employee.getLocation(),
                employee.getShift(),
                employee.getWeekOff(),
                employee.getDateOfJoining(),
                employee.getEmploymentStatus(),
                employee.getProfilePicture(),
                employee.getManager() != null ? employee.getManager().getId() : null,
                employee.getManager() != null ? employee.getManager().getFullName() : null);
    }

    public EmployeeDtos.Stats computeStats(Employee employee) {
        LocalDate today = appClock.today();
        LocalDate doj = employee.getDateOfJoining();
        long totalDays = doj != null ? Math.max(0, ChronoUnit.DAYS.between(doj, today)) : 0;

        long wfo = doj != null
                ? attendanceRepository.countByEmployeeAndTypeAndRange(employee.getId(), AttendanceType.WORK_FROM_OFFICE, doj, today)
                : 0;
        long wfh = doj != null
                ? attendanceRepository.countByEmployeeAndTypeAndRange(employee.getId(), AttendanceType.WORK_FROM_HOME, doj, today)
                : 0;

        BigDecimal pl = leaveRequestRepository.sumApprovedDays(employee.getId(), LeaveType.PRIVILEGE_LEAVE);
        BigDecimal sl = leaveRequestRepository.sumApprovedDays(employee.getId(), LeaveType.SICK_LEAVE);
        BigDecimal co = leaveRequestRepository.sumApprovedDays(employee.getId(), LeaveType.COMP_OFF);
        BigDecimal totalLeaveDays = pl.add(sl).add(co);

        return new EmployeeDtos.Stats(
                totalDays,
                wfo + wfh,
                wfo,
                wfh,
                totalLeaveDays.longValue(),
                pl, sl, co,
                leaveRequestRepository.countByEmployeeIdAndStatus(employee.getId(), LeaveStatus.PENDING),
                leaveRequestRepository.countByEmployeeIdAndStatus(employee.getId(), LeaveStatus.APPROVED),
                leaveRequestRepository.countByEmployeeIdAndStatus(employee.getId(), LeaveStatus.REJECTED));
    }

    @Transactional
    public List<EmployeeDtos.LeaveBalanceDto> balancesFor(Employee employee, int year) {
        ensureBalances(employee, year);
        Map<LeaveType, LeaveBalance> balances = leaveBalanceRepository.findByEmployeeIdAndYear(employee.getId(), year)
                .stream().collect(Collectors.toMap(LeaveBalance::getLeaveType, Function.identity()));
        List<EmployeeDtos.LeaveBalanceDto> result = new ArrayList<>();
        for (LeaveType type : LeaveType.values()) {
            BigDecimal allocated = balances.containsKey(type) ? balances.get(type).getAllocated() : BigDecimal.ZERO;
            BigDecimal used = leaveRequestRepository.sumApprovedDays(employee.getId(), type);
            result.add(new EmployeeDtos.LeaveBalanceDto(
                    type.name(),
                    type.getCode(),
                    type.getLabel(),
                    allocated,
                    used,
                    allocated.subtract(used)));
        }
        return result;
    }

    private void ensureBalances(Employee employee, int year) {
        Map<String, BigDecimal> allocations = parseAllocations(defaultAllocations);
        for (LeaveType type : LeaveType.values()) {
            BigDecimal value = allocations.getOrDefault(type.name(), BigDecimal.ZERO);
            if (leaveBalanceRepository.findByEmployeeIdAndLeaveTypeAndYear(employee.getId(), type, year).isEmpty()) {
                leaveBalanceRepository.save(LeaveBalance.builder()
                        .employee(employee)
                        .leaveType(type)
                        .year(year)
                        .allocated(value)
                        .build());
            }
        }
    }

    private Map<String, BigDecimal> parseAllocations(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> s.contains("="))
                .map(s -> s.split("=", 2))
                .collect(Collectors.toMap(kv -> kv[0].trim(), kv -> new BigDecimal(kv[1].trim())));
    }

    private Employee resolveManager(Long managerId) {
        if (managerId == null) {
            return null;
        }
        return employeeRepository.findById(managerId)
                .orElseThrow(() -> ApiException.badRequest("Manager employee not found"));
    }
}