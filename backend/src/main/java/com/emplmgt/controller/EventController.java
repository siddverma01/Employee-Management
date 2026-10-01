package com.emplmgt.controller;

import com.emplmgt.dto.CalendarDtos;
import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.ScopeType;
import com.emplmgt.service.EventService;
import com.emplmgt.service.UpcomingEventsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Event endpoints.
 *
 * <p>{@code GET /api/events} and the admin CRUD under {@code /api/admin/events} are the
 * pre-existing raw-event surface, unchanged. The employee-facing Upcoming Events feed
 * lives at {@code GET /api/events/upcoming} and is served by {@link UpcomingEventsService}.</p>
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final UpcomingEventsService upcomingEventsService;

    @GetMapping
    public ResponseEntity<List<HolidayDtos.EventResponse>> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) ScopeType scope,
            @RequestParam(required = false) Long teamId) {
        return ResponseEntity.ok(eventService.list(from, to, scope, teamId));
    }

    /**
     * Aggregated upcoming feed combining holidays, birthdays and meetings/sessions,
     * sorted chronologically.
     *
     * @param days look-ahead window in days, inclusive of today (default 30)
     */
    @GetMapping("/upcoming")
    public ResponseEntity<CalendarDtos.UpcomingEventsResponse> upcoming(
            @RequestParam(name = "days", defaultValue = "" + UpcomingEventsService.DEFAULT_WINDOW_DAYS) int days,
            @RequestParam(required = false) Long teamId) {
        return ResponseEntity.ok(upcomingEventsService.upcoming(days, teamId));
    }

    /**
     * Engineers that can be assigned to a meeting, for the Add Event dropdown.
     *
     * <p>Separate from {@code GET /api/events/upcoming} so the page can populate the
     * select without widening the aggregated feed payload.</p>
     */
    @GetMapping("/assignable-engineers")
    public ResponseEntity<List<EmployeeDtos.Simple>> assignableEngineers() {
        return ResponseEntity.ok(upcomingEventsService.assignableEngineers());
    }

    /**
     * Assigns, reassigns or clears the engineer on a meeting.
     *
     * <p>Drives the Assign Engineer / Reassign actions on the Customer Meetings cards.
     * A {@code null} {@code assignedEngineerId} unassigns the meeting.</p>
     */
    @PatchMapping("/{id}/assignment")
    public ResponseEntity<CalendarDtos.UpcomingEvent> assign(
            @PathVariable Long id,
            @RequestBody CalendarDtos.AssignEventRequest request) {
        return ResponseEntity.ok(upcomingEventsService.assign(id, request.assignedEngineerId()));
    }

    /**
     * Moves a meeting to a new date and/or time window.
     *
     * <p>Drives the inline Scheduled Time editor on the event details dialog. A
     * {@code null} {@code startTime} makes the event all-day.</p>
     */
    @PatchMapping("/{id}/schedule")
    public ResponseEntity<CalendarDtos.UpcomingEvent> reschedule(
            @PathVariable Long id,
            @Valid @RequestBody CalendarDtos.RescheduleEventRequest request) {
        return ResponseEntity.ok(upcomingEventsService.reschedule(id, request));
    }

    /** Creates a user-scheduled meeting, scheduled meeting or remote session. */
    @PostMapping
    public ResponseEntity<CalendarDtos.UpcomingEvent> create(
            @Valid @RequestBody CalendarDtos.CreateEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(upcomingEventsService.create(request));
    }
}