package com.emplmgt.dto;

import com.emplmgt.entity.AttendanceType;
import com.emplmgt.entity.LeaveType;
import com.emplmgt.entity.LeaveStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record TodayStatus(
            String mode,
            Long teamId,
            String teamName,
            List<TodayEntry> working,
            List<TodayEntry> onLeave,
            List<TeamDtos.TeamMemberAvailability> members) {
    }

    public record TodayEntry(
            Long employeeId,
            String employeeCode,
            String fullName,
            String department,
            String designation,
            String avatar,
            AttendanceType attendanceType,
            LeaveType leaveType,
            String location) {
    }

    public record EmployeeDashboard(
            String fullName,
            String department,
            String designation,
            String profilePicture,
            LocalDate today,
            String todayType,
            List<EmployeeDtos.LeaveBalanceDto> leaveBalances,
            long wfhDays,
            long wfoDays,
            long pendingLeaves,
            long approvedUpcomingLeaves,
            List<UpcomingItem> upcomingHolidays,
            List<UpcomingItem> upcomingBirthdays) {
    }

    public record UpcomingItem(LocalDate date, String name, String type) {
    }

    public record AdminSummary(
            long totalEmployees,
            long activeEmployees,
            long onLeaveToday,
            long workingToday,
            long pendingLeaves,
            long pendingSwapOffs,
            long wfhToday,
            long wfoToday) {
    }

    public record LeaveUsageByType(Map<String, Object> data) {
    }

    public record MonthlyTrend(Map<String, Object> data) {
    }

    public record DepartmentLeave(Map<String, Object> data) {
    }
}