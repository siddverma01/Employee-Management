package com.emplmgt.dto;

import com.emplmgt.entity.EventType;
import com.emplmgt.entity.HolidayType;
import com.emplmgt.entity.ScopeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
}