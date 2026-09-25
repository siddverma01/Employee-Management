package com.emplmgt.dto;

import com.emplmgt.entity.ApplicableLocation;
import com.emplmgt.entity.EventType;
import com.emplmgt.entity.HPEEntitlementStatus;
import com.emplmgt.entity.HolidayType;
import com.emplmgt.entity.ScopeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;

public final class HolidayDtos {

    private HolidayDtos() {
    }

    public record HolidayRequest(
            @NotBlank(message = "Holiday name is required") String name,
            @NotNull(message = "Holiday date is required") LocalDate date,
            String country,
            HolidayType holidayType,
            String description,
            ApplicableLocation applicableLocations,
            Boolean active,
            ScopeType scope,
            Long teamId) {
    }

    public record Response(
            Long id,
            String name,
            LocalDate date,
            String country,
            HolidayType holidayType,
            String description,
            ApplicableLocation applicableLocations,
            boolean active,
            ScopeType scope,
            Long teamId) {
    }

    public record EventRequest(
            @NotBlank(message = "Event title is required") String title,
            String description,
            @NotNull(message = "Event date is required") LocalDate eventDate,
            EventType eventType,
            ScopeType scope,
            Long teamId) {
    }

    public record EventResponse(
            Long id,
            String title,
            String description,
            LocalDate eventDate,
            EventType eventType,
            ScopeType scope,
            Long teamId) {
    }

    public record HPEHolidayResponse(
            Long id,
            String name,
            LocalDate date,
            String country,
            String description,
            ApplicableLocation applicableLocations,
            boolean isHpeHoliday) {
    }

    public record HPEEntitlementResponse(
            Long id,
            Long employeeId,
            String employeeName,
            Long holidayId,
            String holidayName,
            LocalDate holidayDate,
            LocalDate earnedDate,
            LocalDate expiryDate,
            String status,
            LocalDate usedDate,
            Long usedRequestId,
            String notes) {
    }

    public record HPEEntitlementCreateRequest(
            @NotNull(message = "Holiday ID is required") Long holidayId) {
    }

    public record HPEEntitlementUseRequest(
            @NotNull(message = "Entitlement ID is required") Long entitlementId,
            @NotNull(message = "Request ID is required") Long requestId) {
    }

    public record EntitlementStatusSummary(
            long available,
            long used,
            long expired) {
    }

    /** Result of scanning one master HPE holiday for employees entitled to an award. */
    public record HpeEntitlementSyncResponse(
            Long holidayId,
            String holidayName,
            LocalDate holidayDate,
            int evaluated,
            int created,
            int alreadyExists,
            int notWorking,
            int notApplicable,
            int unknownStatus) {
    }
}