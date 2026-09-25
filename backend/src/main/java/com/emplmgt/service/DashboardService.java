package com.emplmgt.service;

import com.emplmgt.dto.DashboardDtos;
import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.dto.TeamDtos;
import com.emplmgt.entity.*;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.*;
import com.emplmgt.util.AppClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final SwapOffRequestRepository swapOffRequestRepository;
    private final HolidayRepository holidayRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final AppClock appClock;
    private final AttendanceService attendanceService;
    private final EmployeeService employeeService;
    private final com.emplmgt.util.WeekOffUtil weekOffUtil;

    @Transactional(readOnly = true)
    public DashboardDtos.AdminSummary adminSummary(Long teamId) {
        long total = teamId == null ? employeeRepository.count() : employeeRepository.countByDepartmentId(teamId);
        long active = teamId == null
                ? employeeRepository.countByEmploymentStatus(EmploymentStatus.ACTIVE)
                : employeeRepository.countByDepartmentIdAndEmploymentStatus(teamId, EmploymentStatus.ACTIVE);
        long pendingLeaves = teamId == null
                ? leaveRequestRepository.countByStatus(LeaveStatus.PENDING)
                : leaveRequestRepository.countByEmployeeDepartmentIdAndStatus(teamId, LeaveStatus.PENDING);
        long pendingSwapOffs = teamId == null
                ? swapOffRequestRepository.countByStatus(LeaveStatus.PENDING)
                : swapOffRequestRepository.countByEmployeeDepartmentIdAndStatus(teamId, LeaveStatus.PENDING);

        TeamDtos.TeamDashboard team = attendanceService.teamStatus(teamId);
        long onLeave = team.onLeaveToday();
        long working = team.workingToday();
        long wfh = team.wfhToday();
        long wfo = Math.max(0, working - wfh);

        return new DashboardDtos.AdminSummary(total, active, onLeave, working, pendingLeaves, pendingSwapOffs, wfh, wfo);
    }

    @Transactional(readOnly = true)
    public TeamDtos.TeamDashboard teamDashboard(Long teamId) {
        return attendanceService.teamStatus(teamId);
    }

    @Transactional(readOnly = true)
    public DashboardDtos.EmployeeDashboard employeeDashboard(Long userId) {
        Employee employee = employeeService.getEmployeeByUser(userId);
        LocalDate today = appClock.today();
        String todayType = todayTypeFor(employee, today);

        List<EmployeeDtos.LeaveBalanceDto> balances = myBalancesWithCoCredits(employee, today.getYear());
        long wfh = attendanceRepository.countByEmployeeAndTypeAndRange(employee.getId(), AttendanceType.WORK_FROM_HOME,
                today.withDayOfYear(1), today);
        long wfo = attendanceRepository.countByEmployeeAndTypeAndRange(employee.getId(), AttendanceType.WORK_FROM_OFFICE,
                today.withDayOfYear(1), today);
        long pendingLeaves = leaveRequestRepository.countByEmployeeIdAndStatus(employee.getId(), LeaveStatus.PENDING);
        long approvedUpcoming = leaveRequestRepository.findUpcomingForEmployee(employee.getId(), today).size();

        List<DashboardDtos.UpcomingItem> upcomingHolidays = holidayRepository
                .findByHolidayDateBetweenOrderByHolidayDate(today, today.plusDays(30)).stream()
                .limit(3)
                .map(h -> new DashboardDtos.UpcomingItem(h.getHolidayDate(), h.getName(), "HOLIDAY"))
                .toList();
        List<DashboardDtos.UpcomingItem> upcomingBirthdays = upcomingBirthdays(
                employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE), today, 30);

        return new DashboardDtos.EmployeeDashboard(
                employee.getFullName(),
                employee.getDepartment() != null ? employee.getDepartment().getName() : null,
                employee.getDesignation(),
                employee.getProfilePicture(),
                today,
                todayType,
                balances,
                wfh, wfo, pendingLeaves, approvedUpcoming,
                upcomingHolidays, upcomingBirthdays);
    }

    private String todayTypeFor(Employee employee, LocalDate today) {
        var attendance = attendanceRepository.findByEmployeeIdAndAttendanceDate(employee.getId(), today).orElse(null);
        if (attendance != null) {
            return attendance.getAttendanceType().name();
        }
        boolean onApprovedLeave = leaveRequestRepository.findApprovedInRange(today, today).stream()
                .anyMatch(lr -> lr.getEmployee().getId().equals(employee.getId()));
        if (onApprovedLeave) {
            return "LEAVE";
        }
        if (!holidayRepository.findByHolidayDate(today).isEmpty()) {
            return "HOLIDAY";
        }
        if (weekOffUtil.isWeekOff(employee, today)) {
            return "WEEK_OFF";
        }
        return "WORK_FROM_OFFICE";
    }

    private List<EmployeeDtos.LeaveBalanceDto> myBalancesWithCoCredits(Employee employee, int year) {
        return employeeService.balancesFor(employee, year).stream().map(b -> {
            if ("COMP_OFF".equals(b.leaveType())) {
                long credits = swapOffRequestRepository.countApprovedCredits(employee.getId(), year);
                BigDecimal allocated = b.allocated().add(BigDecimal.valueOf(credits));
                return new EmployeeDtos.LeaveBalanceDto(b.leaveType(), b.leaveTypeCode(), b.leaveTypeLabel(),
                        allocated, b.used(), allocated.subtract(b.used()));
            }
            return b;
        }).toList();
    }

    private List<DashboardDtos.UpcomingItem> upcomingBirthdays(List<Employee> employees, LocalDate today, int days) {
        List<DashboardDtos.UpcomingItem> items = new ArrayList<>();
        LocalDate windowEnd = today.plusDays(days);
        for (Employee e : employees) {
            if (e.getDateOfBirth() == null) {
                continue;
            }
            LocalDate next;
            int year = today.getYear();
            try {
                next = LocalDate.of(year, e.getDateOfBirth().getMonthValue(), e.getDateOfBirth().getDayOfMonth());
            } catch (Exception ex) {
                continue;
            }
            if (next.isBefore(today)) {
                try {
                    next = LocalDate.of(year + 1, e.getDateOfBirth().getMonthValue(), e.getDateOfBirth().getDayOfMonth());
                } catch (Exception ex) {
                    continue;
                }
            }
            if (!next.isAfter(windowEnd)) {
                items.add(new DashboardDtos.UpcomingItem(next, e.getFullName(), "BIRTHDAY"));
            }
        }
        items.sort(Comparator.comparing(DashboardDtos.UpcomingItem::date));
        return items.stream().limit(3).toList();
    }

    // ------------------------------------------------------------------ CHARTS

    @Transactional(readOnly = true)
    public Map<String, Object> leaveUsageByType(LocalDate from, LocalDate to, Long teamId) {
        LocalDate start = sanitizeFrom(from);
        LocalDate end = sanitizeTo(to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", new String[]{"PL", "SL", "CO"});
        BigDecimal[] values = new BigDecimal[3];
        int i = 0;
        for (LeaveType type : List.of(LeaveType.PRIVILEGE_LEAVE, LeaveType.SICK_LEAVE, LeaveType.COMP_OFF)) {
            values[i++] = leaveRequestRepository.sumApprovedDaysInRange(type, start, end, teamId);
        }
        out.put("values", values);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> wfhVsWfo(LocalDate from, LocalDate to, Long teamId) {
        LocalDate start = sanitizeFrom(from);
        LocalDate end = sanitizeTo(to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", new String[]{"WFH", "WFO"});
        out.put("values", new long[]{
                attendanceRepository.countByTypeInRange(AttendanceType.WORK_FROM_HOME, start, end, teamId),
                attendanceRepository.countByTypeInRange(AttendanceType.WORK_FROM_OFFICE, start, end, teamId)});
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> monthlyTrend(LocalDate from, LocalDate to, Long teamId) {
        LocalDate start = sanitizeFrom(from);
        LocalDate end = sanitizeTo(to);
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        LocalDate cursor = start.withDayOfMonth(1);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM");
        while (!cursor.isAfter(end)) {
            LocalDate monthStart = cursor;
            LocalDate monthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth()).isAfter(end)
                    ? end : cursor.withDayOfMonth(cursor.lengthOfMonth());
            labels.add(cursor.getYear() + " " + cursor.format(fmt));
            values.add(leaveRequestRepository.countApprovedInRange(monthStart, monthEnd, teamId));
            cursor = cursor.plusMonths(1);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", labels);
        out.put("values", values);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> departmentLeaveUsage(LocalDate from, LocalDate to) {
        LocalDate start = sanitizeFrom(from);
        LocalDate end = sanitizeTo(to);
        List<Object[]> rows = leaveRequestRepository.sumApprovedDaysByDepartment(start, end);
        List<String> labels = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        for (Object[] row : rows) {
            labels.add((String) row[0]);
            values.add((BigDecimal) row[1]);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", labels);
        out.put("values", values);
        return out;
    }

    private LocalDate sanitizeFrom(LocalDate from) {
        if (from != null) {
            return from;
        }
        return appClock.today().withDayOfYear(1);
    }

    private LocalDate sanitizeTo(LocalDate to) {
        if (to != null) {
            return to;
        }
        return appClock.today();
    }
}