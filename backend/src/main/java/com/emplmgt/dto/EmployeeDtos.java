package com.emplmgt.dto;

import com.emplmgt.entity.EmploymentStatus;
import com.emplmgt.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class EmployeeDtos {

    private EmployeeDtos() {
    }

    public record CreateRequest(
            @NotBlank(message = "Employee code is required") String employeeCode,
            @NotBlank(message = "Full name is required") String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Invalid email") String email,
            String password,
            @NotNull(message = "Role is required") Role role,
            Long departmentId,
            Long managerId,
            String phone,
            String designation,
            String location,
            String shift,
            String weekOff,
            @NotNull(message = "Date of joining is required") LocalDate dateOfJoining,
            LocalDate dateOfBirth) {
    }

    public record UpdateRequest(
            @NotBlank(message = "Full name is required") String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Invalid email") String email,
            Long departmentId,
            Long managerId,
            String phone,
            String designation,
            String location,
            String shift,
            String weekOff,
            LocalDate dateOfJoining,
            LocalDate dateOfBirth) {
    }

    public record DepartmentDto(Long id, String name, String description) {
    }

    public record DepartmentCreateRequest(@NotBlank String name, String description) {
    }

    public record Summary(
            Long id,
            String employeeCode,
            String fullName,
            String email,
            String phone,
            String designation,
            String department,
            String location,
            String shift,
            String weekOff,
            LocalDate dateOfJoining,
            EmploymentStatus employmentStatus,
            String avatar,
            Long managerId,
            String managerName) {
    }

    public record Stats(
            long totalDaysSinceJoining,
            long totalWorkingDays,
            long workFromOfficeDays,
            long workFromHomeDays,
            long totalLeaveDays,
            BigDecimal plDays,
            BigDecimal slDays,
            BigDecimal coDays,
            long pendingLeaves,
            long approvedLeaves,
            long rejectedLeaves) {
    }

    public record Profile(
            Long id,
            String employeeCode,
            String fullName,
            String email,
            String phone,
            String department,
            Long departmentId,
            String designation,
            String manager,
            Long managerId,
            String location,
            String shift,
            String weekOff,
            LocalDate dateOfJoining,
            LocalDate dateOfBirth,
            EmploymentStatus employmentStatus,
            String avatar,
            Stats stats,
            List<LeaveBalanceDto> leaveBalances) {
    }

    public record LeaveBalanceDto(String leaveType, String leaveTypeCode, String leaveTypeLabel,
                                  BigDecimal allocated, BigDecimal used, BigDecimal available) {
    }

    public record Simple(Long id, String employeeCode, String fullName, String department) {
    }
}