package com.emplmgt.dto;

import java.time.LocalDate;
import java.util.Map;

public final class CalendarDtos {

    private CalendarDtos() {
    }

    /**
     * Kind drives how the event is displayed/coloured on the frontend.
     */
    public enum EventKind {
        LEAVE, COMP_OFF, HOLIDAY, BIRTHDAY, EVENT
    }

    public record CalendarEvent(
            Long id,
            String kind,
            LocalDate date,
            LocalDate startDate,
            LocalDate endDate,
            String title,
            String subtitle,
            String employeeName,
            String employeeCode,
            Long employeeId,
            String leaveType,
            String leaveTypeCode,
            String leaveTypeLabel,
            String status,
            String description,
            Map<String, Object> extra) {
    }

    public record MonthRequest(int year, int month, String view) {
    }
}