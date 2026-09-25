package com.emplmgt.dto;

import com.emplmgt.entity.AttendanceType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public final class AttendanceDtos {

    private AttendanceDtos() {
    }

    public record ManualCreateRequest(
            @NotNull String employeeCode,
            @NotNull(message = "Attendance date is required") LocalDate date,
            @NotNull(message = "Attendance type is required") AttendanceType attendanceType,
            String remarks) {
    }

    public record Response(
            Long id,
            Long employeeId,
            String employeeCode,
            String employeeName,
            String department,
            LocalDate date,
            AttendanceType attendanceType,
            String source,
            String remarks) {
    }
}