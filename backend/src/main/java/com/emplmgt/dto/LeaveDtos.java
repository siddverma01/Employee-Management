package com.emplmgt.dto;

import com.emplmgt.entity.LeaveStatus;
import com.emplmgt.entity.LeaveType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class LeaveDtos {

    private LeaveDtos() {
    }

    public record ApplyRequest(
            @NotNull(message = "Leave type is required") LeaveType leaveType,
            @NotNull(message = "Start date is required") LocalDate startDate,
            @NotNull(message = "End date is required") LocalDate endDate,
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
            LeaveType leaveType,
            String leaveTypeCode,
            String leaveTypeLabel,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal days,
            String reason,
            String attachment,
            LeaveStatus status,
            String rejectionReason,
            Instant appliedOn,
            Instant decidedAt) {
    }
}