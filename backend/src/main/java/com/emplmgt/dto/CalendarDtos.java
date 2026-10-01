package com.emplmgt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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

    // ---------------------------------------------------------------------
    // Upcoming Events
    //
    // The employee-facing "Upcoming Events" page aggregates three sources that do
    // not share a table: holidays (rows in `holidays`), birthdays (derived from
    // employees.date_of_birth, no rows at all) and meetings/sessions (rows in
    // `events`). {@link UpcomingEventCategory} is therefore a presentation-level
    // taxonomy rather than the persisted {@code events.event_type} enum: HOLIDAY
    // and BIRTHDAY never exist as event rows, so widening the DB CHECK constraint
    // to include them would be misleading.
    // ---------------------------------------------------------------------

    /**
     * Bucket an {@link UpcomingEvent} falls into, driving icon/colour on the frontend.
     * Maps 1:1 onto the categories requested for the page.
     */
    public enum UpcomingEventCategory {
        /** From {@code holidays}, location-filtered per employee. */
        HOLIDAY,
        /** Derived from employee date of birth; no stored row. */
        BIRTHDAY,
        /** {@link com.emplmgt.entity.EventType#OFFICE_MEETING} */
        OFFICE_MEETING,
        /** {@link com.emplmgt.entity.EventType#SCHEDULED_MEETING} */
        SCHEDULED_MEETING,
        /** {@link com.emplmgt.entity.EventType#CUSTOMER_REMOTE_SESSION} */
        CUSTOMER_REMOTE_SESSION
    }

    /**
     * Where an {@link UpcomingEvent} came from. Lets the frontend treat each source
     * differently (holidays are read-only, birthdays are computed, meetings are editable)
     * without inferring it from a null id.
     */
    @Getter
    public enum UpcomingEventSource {
        /** Stored row in {@code holidays}; {@code id} is the holiday id. */
        HOLIDAY,
        /** Computed from date of birth; {@code id} is the employee id. */
        BIRTHDAY,
        /** Stored row in {@code events}; {@code id} is the event id. */
        EVENT;

        private final String wireValue;

        UpcomingEventSource() {
            this.wireValue = name();
        }
    }

    /**
     * A single entry on the Upcoming Events page.
     *
     * <p>Fields that do not apply to a source are {@code null} and are omitted from the
     * JSON by the global {@code non_null} Jackson inclusion rule: a holiday has no
     * meeting link, start time or checklist, and a birthday has only a subject.</p>
     */
    public record UpcomingEvent(
            /** Holiday id, employee id or event id depending on {@link #source}. */
            Long id,
            String source,
            UpcomingEventCategory category,
            String subject,
            String description,
            /** Calendar date. Authoritative for sorting and grouping. */
            LocalDate date,
            /** Wall-clock start; {@code null} for holidays and birthdays. */
            LocalDateTime startTime,
            /** Wall-clock end, when the meeting spans a range; otherwise {@code null}. */
            LocalDateTime endTime,
            /** Client name for customer-facing meetings; {@code null} for internal events. */
            String organization,
            /**
             * Where the meeting takes place, e.g. "Virtual Teams Room"; shown in
             * the Customer Meeting card footer. {@code null} when not recorded.
             */
            String location,
            /** Meeting URL; only populated for remote sessions. */
            String meetingLink,
            Boolean setReminder,
            List<String> todoItems,
            /** Populated for birthdays so the UI can show whose birthday it is. */
            String employeeName,
            /** Raw persisted event type, {@code null} for holidays and birthdays. */
            String eventType,
            /**
             * Country of the holiday definition, e.g. {@code US} or {@code IN}.
             *
             * <p>Only set for holidays. Together with {@link #eventType} it lets the
             * feed label a holiday as a US holiday or an HPE holiday: the persisted
             * type alone is not enough, because US federal holidays are stored as
             * {@code PUBLIC}.</p>
             */
            String holidayCountry,
            /**
             * Employee the meeting is assigned to; {@code null} when unassigned.
             * Never set for holidays or birthdays.
             */
            Long assignedEngineerId,
            /** Resolved from {@link #assignedEngineerId} at read time. */
            String assignedEngineerName,
            /** Assignee's department, shown as the role on the Customer Meeting card. */
            String assignedEngineerDepartment,
            /**
             * Assignee's job title, e.g. "Lead Architect".
             *
             * <p>Preferred over {@link #assignedEngineerDepartment} for the Customer
             * Meeting card's "Name (Role)" chip, since a title describes what the
             * person is there to do whereas a department says where they sit.</p>
             */
            String assignedEngineerDesignation,
            /** Days from today until {@link #date}; {@code 0} means today. */
            long daysUntil) {
    }

    /** Aggregated response for {@code GET /api/events/upcoming}. */
    public record UpcomingEventsResponse(
            LocalDate today,
            int days,
            List<UpcomingEvent> events) {
    }

    /** Body for {@code POST /api/events}. */
    public record CreateEventRequest(
            @NotBlank(message = "Event subject is required") String subject,
            String description,
            @NotNull(message = "Event date is required") LocalDate date,
            LocalDateTime startTime,
            /** Drives the category; defaults to OFFICE_MEETING when omitted. */
            UpcomingEventCategory eventType,
            String meetingLink,
            Boolean setReminder,
            List<String> todoItems,
            /**
             * Optional engineer to assign the meeting to. {@code null} leaves the
             * event unassigned. Must reference an existing employee.
             */
            Long assignedEngineerId,
            /** Optional client name, shown in the Customer Meetings card header. */
            String organization,
            /** Optional wall-clock end, so the card can render a time range. */
            LocalDateTime endTime,
            /** Optional room or platform, shown in the Customer Meetings card footer. */
            String location) {
    }

    /**
     * Body for {@code PATCH /api/events/{id}/assignment}.
     *
     * <p>{@code assignedEngineerId} is required and may be {@code null} to unassign,
     * because "remove the current assignee" is a real user action and is not the
     * same as "field omitted".</p>
     */
    public record AssignEventRequest(
            Long assignedEngineerId) {
    }

    /**
     * Body for {@code PATCH /api/events/{id}/schedule}.
     *
     * <p>Every field is a full replacement rather than a patch: {@code date} is
     * required, and a {@code null} {@code startTime} means "all day", which is the
     * same thing clearing the field means to the editor that sent it. The service
     * rejects a window whose end is not after its start.</p>
     */
    public record RescheduleEventRequest(
            @NotNull(message = "Event date is required") LocalDate date,
            LocalDateTime startTime,
            LocalDateTime endTime) {
    }
}