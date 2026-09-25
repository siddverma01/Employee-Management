package com.emplmgt.dto;

import com.emplmgt.entity.LeaveStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;

public final class SwapOffDtos {

    private SwapOffDtos() {
    }

    public record ApplyRequest(
            @NotNull(message = "Worked date is required") LocalDate workedDate,
            @NotNull(message = "Requested off date is required") LocalDate requestedOffDate,
            @NotBlank(message = "Employee worked for is required") String workedForEmployeeCode,
            @NotBlank(message = "Reason is required") String reason,
            String attachment) {
    }

    public record DecideRequest(@NotBlank(message = "Rejection reason is required") String rejectionReason) {
    }

    public record Response(
            Long id,
            Long employeeId,
            String employeeCode,
            String employeeName,
            String department,
            Long workedForEmployeeId,
            String workedForEmployeeCode,
            String workedForEmployeeName,
            LocalDate workedDate,
            LocalDate requestedOffDate,
            String reason,
            String attachment,
            LeaveStatus status,
            String rejectionReason,
            boolean compOffCredited,
            Instant appliedOn,
            Instant decidedAt) {
    }
}