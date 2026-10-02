package com.emplmgt.service;

import com.emplmgt.dto.CalendarDtos;
import com.emplmgt.dto.CalendarDtos.UpcomingEvent;
import com.emplmgt.dto.CalendarDtos.UpcomingEventCategory;
import com.emplmgt.dto.CalendarDtos.UpcomingEventSource;
import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.EmploymentStatus;
import com.emplmgt.entity.Event;
import com.emplmgt.entity.EventType;
import com.emplmgt.entity.ScopeType;
import com.emplmgt.entity.User;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.EventRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Backing service for the employee-facing Upcoming Events page.
 *
 * <p>Aggregates three independent sources into one chronological feed:</p>
 * <ol>
 *   <li><b>Holidays</b> - {@code holidays} rows, reusing
 *       {@link HolidayService#listForEmployee} so the same location filtering
 *       ({@code active} + {@code applicableLocations}) applies here as on the
 *       Holidays page and the Calendar.</li>
 *   <li><b>Birthdays</b> - derived from employee date of birth via
 *       {@link EmployeeService#upcomingBirthdays}; no stored rows.</li>
 *   <li><b>Meetings / sessions</b> - {@code events} rows visible to the caller's team
 *       (global scope) plus anything the caller created themselves.</li>
 * </ol>
 *
 * <p>Holiday management, the admin console and the Calendar are untouched; this service
 * only reads from those sources and writes {@code events} rows for user-created meetings.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UpcomingEventsService {

    /** Default look-ahead window when the caller does not specify one. */
    public static final int DEFAULT_WINDOW_DAYS = 30;

    /** Hard cap on the look-ahead window, mirroring the birthday cap. */
    public static final int MAX_WINDOW_DAYS = 365;

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final HolidayService holidayService;
    private final EmployeeService employeeService;
    private final SecurityUtils securityUtils;
    private final AuditService auditService;
    private final AppClock appClock;
    private final JsonUtil jsonUtil;

    /**
     * Aggregated upcoming feed for the current user.
     *
     * @param days look-ahead window in days, inclusive of today; clamped to [1, {@value #MAX_WINDOW_DAYS}]
     * @param teamId optional team filter; {@code null} means the caller's own team
     */
    @Transactional(readOnly = true)
    public CalendarDtos.UpcomingEventsResponse upcoming(int days, Long teamId) {
        int window = Math.min(Math.max(days, 1), MAX_WINDOW_DAYS);
        LocalDate today = appClock.today();
        LocalDate lastDay = today.plusDays(window - 1L);

        List<UpcomingEvent> events = new ArrayList<>();
        events.addAll(holidays(today, lastDay));
        events.addAll(birthdays(today, lastDay));
        events.addAll(storedEvents(today, lastDay, teamId));

        // Chronological, then by category so the order is stable for same-day entries.
        events.sort(Comparator.comparing(UpcomingEvent::date)
                .thenComparing(e -> e.category().name())
                .thenComparing(e -> e.subject() == null ? "" : e.subject()));

        List<UpcomingEvent> limited = events.size() > MAX_RESULTS
                ? List.copyOf(events.subList(0, MAX_RESULTS))
                : List.copyOf(events);
        return new CalendarDtos.UpcomingEventsResponse(today, window, limited);
    }

    /**
     * Creates a user-scheduled meeting, remote session or holiday.
     *
     * <p>The three categories deliberately take different storage paths. A holiday
     * is a {@code holidays} row, not an {@code events} row, so it is delegated to
     * {@link HolidayService#create} below; everything else is stored as an event.</p>
     */
    @Transactional
    public UpcomingEvent create(CalendarDtos.CreateEventRequest request) {
        LocalDate today = appClock.today();
        if (request.date().isBefore(today)) {
            throw ApiException.badRequest("Event date cannot be in the past");
        }

        Long userId = securityUtils.currentUserId();
        if (userId == null) {
            throw ApiException.badRequest("No authenticated user");
        }
        User creator = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found: " + userId));

        UpcomingEventCategory category = request.eventType() == null
                ? UpcomingEventCategory.OFFICE_MEETING
                : request.eventType();

        // Routing by category is the whole point: the feed reads holidays from the
        // `holidays` table, so persisting one as an `events` row would make it
        // resurface under Office Meetings and never reach the holiday calendar.
        if (category == UpcomingEventCategory.HOLIDAY) {
            return createHoliday(request);
        }

        Employee assignee = resolveAssignee(request.assignedEngineerId());


        Event event = Event.builder()
                .title(request.subject().trim())
                .description(request.description())
                .eventDate(request.date())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .organization(normalise(request.organization()))
                .location(normalise(request.location()))
                .eventType(toEventType(category))
                .meetingLink(normalise(request.meetingLink()))
                .setReminder(Boolean.TRUE.equals(request.setReminder()))
                .todoItems(request.todoItems() == null || request.todoItems().isEmpty()
                        ? null
                        : jsonUtil.writeList(request.todoItems()))
                // User-created meetings default to their creator's team so teammates see them.
                .scope(ScopeType.TEAM)
                .team(creatorTeam(userId))
                .createdBy(creator)
                .assignedTo(assignee)
                .build();

        Event saved = eventRepository.save(event);
        auditService.record("EVENT_CREATED", "Event", String.valueOf(saved.getId()),
                null, Map.of("title", saved.getTitle(), "date", saved.getEventDate().toString(),
                        "category", category.name()));
        log.info("Upcoming event {} created by user {}", saved.getId(), userId);
        return toUpcomingEvent(saved, appClock.today());
    }

    /**
     * Creates a holiday from the create-event form.
     *
     * <p>Delegates to {@link HolidayService} rather than writing a row here, so a
     * form-created holiday is visible everywhere a seeded one is: same location
     * filtering, same audit trail, same {@code active} / {@code holidayType} /
     * {@code applicableLocations} defaults. The form collects only a purpose and a
     * date, so the remaining fields are defaulted by that service instead of being
     * invented here. Meeting-only inputs (assignee, reminders, todos) do not apply
     * to a holiday and are deliberately dropped.</p>
     */
    private UpcomingEvent createHoliday(CalendarDtos.CreateEventRequest request) {
        HolidayDtos.Response saved = holidayService.create(new HolidayDtos.HolidayRequest(
                request.subject().trim(),
                request.date(),
                // country, holidayType, applicableLocations and active are all
                // defaulted by HolidayService: US / PUBLIC / ALL / active.
                null,
                null,
                request.description(),
                null,
                null,
                null,
                null));
        log.info("Holiday {} created from the create-event form", saved.id());
        return toHolidayUpcomingEvent(saved, appClock.today());
    }

    /**
     * Assigns, reassigns or clears the engineer on an existing meeting.

     *
     * <p>Backs the Customer Meetings card's "Assign Engineer" / "Reassign" actions.
     * Passing {@code null} clears the assignee, which is how an unassigned meeting
     * goes back to being unassigned.</p>
     *
     * @throws ApiException 400 when the id does not resolve; 404 when the event does
     *                      not exist or is not visible to the caller
     */
    @Transactional
    public UpcomingEvent assign(Long eventId, Long assignedEngineerId) {
        Long userId = securityUtils.currentUserId();
        if (userId == null) {
            throw ApiException.badRequest("No authenticated user");
        }

        // Reuse the feed's visibility rules so reassignment cannot be used to
        // reach an event the caller could not otherwise see.
        Event event = eventRepository.findById(eventId)
                .filter(e -> isVisibleTo(e, userId))
                .orElseThrow(() -> ApiException.notFound("Event not found: " + eventId));

        Employee previous = event.getAssignedTo();
        event.setAssignedTo(resolveAssignee(assignedEngineerId));
        Event saved = eventRepository.save(event);

        auditService.record("EVENT_ASSIGNED", "Event", String.valueOf(saved.getId()),
                previous == null ? null
                        : Map.of("assignedEngineerId", previous.getId(), "assignedEngineerName",
                                String.valueOf(previous.getFullName())),
                assignedEngineerId == null ? Map.of("assignedEngineerId", "none")
                        : Map.of("assignedEngineerId", String.valueOf(assignedEngineerId),
                                "assignedEngineerName",
                                String.valueOf(saved.getAssignedTo().getFullName())));
        log.info("Upcoming event {} assigned to {} by user {}", saved.getId(), assignedEngineerId, userId);
        return toUpcomingEvent(saved, appClock.today());
    }

    /**
     * Moves a meeting to a new date and/or time window.
     *
     * <p>Backs the inline "Edit" control on the event details dialog. Reuses the same
     * {@link #isVisibleTo} guard as {@link #assign} so rescheduling cannot be used to
     * reach an event the caller could not otherwise see. Holidays and birthdays are
     * synthesized from their own tables and so have no event row to move, which
     * surfaces here as a 404.</p>
     *
     * @throws ApiException 400 when the date is in the past or the window is
     *                      inverted; 404 when the event is not visible to the caller
     */
    @Transactional
    public UpcomingEvent reschedule(Long eventId, CalendarDtos.RescheduleEventRequest request) {
        Long userId = securityUtils.currentUserId();
        if (userId == null) {
            throw ApiException.badRequest("No authenticated user");
        }

        Event event = eventRepository.findById(eventId)
                .filter(e -> isVisibleTo(e, userId))
                .orElseThrow(() -> ApiException.notFound("Event not found: " + eventId));

        // Matches create()'s rule, so an event can never be edited into the past.
        if (request.date().isBefore(appClock.today())) {
            throw ApiException.badRequest("Event date cannot be in the past");
        }
        if (request.startTime() != null && request.endTime() != null
                && !request.endTime().isAfter(request.startTime())) {
            throw ApiException.badRequest("End time must be after start time");
        }

        // String.valueOf rather than the raw value: audit's maps are Map.of, which
        // rejects nulls, and an all-day event legitimately has no times.
        String previousDate = String.valueOf(event.getEventDate());
        String previousStart = String.valueOf(event.getStartTime());
        String previousEnd = String.valueOf(event.getEndTime());

        event.setEventDate(request.date());
        event.setStartTime(request.startTime());
        event.setEndTime(request.endTime());
        Event saved = eventRepository.save(event);

        auditService.record("EVENT_RESCHEDULED", "Event", String.valueOf(saved.getId()),
                Map.of("date", previousDate, "startTime", previousStart, "endTime", previousEnd),
                Map.of("date", String.valueOf(saved.getEventDate()),
                        "startTime", String.valueOf(saved.getStartTime()),
                        "endTime", String.valueOf(saved.getEndTime())));
        log.info("Upcoming event {} rescheduled to {} by user {}", saved.getId(),
                saved.getEventDate(), userId);
        return toUpcomingEvent(saved, appClock.today());
    }

    /**
     * Whether the caller may see this event on the upcoming feed.

     *
     * <p>Mirrors {@link EventRepository#findVisibleInRange}: global events are open to
     * everyone, team events to that team, and a caller's own event stays visible to
     * them even when they have no team.</p>
     */
    private boolean isVisibleTo(Event event, Long userId) {
        if (event.getScope() == ScopeType.GLOBAL) {
            return true;
        }
        if (event.getCreatedBy() != null && event.getCreatedBy().getId().equals(userId)) {
            return true;
        }
        if (securityUtils.isAdmin()) {
            return true;
        }
        // Must mirror EventRepository.findVisibleInRange, which admits a team event
        // whenever the caller's team is unknown (`:teamId is null`). A caller with no
        // team -- the admin, who has no employee profile -- therefore sees every team
        // event on the feed, and a stricter check here made Reassign and Reschedule
        // 404 on rows the feed had just rendered.
        Long callerTeamId = securityUtils.currentTeamId();
        if (callerTeamId == null) {
            return true;
        }
        Long teamId = event.getTeam() == null ? null : event.getTeam().getId();
        return teamId != null && teamId.equals(callerTeamId);
    }

    /**
     * Engineers that can be assigned to a meeting, for the Add Event dropdown.
     *
     * <p>Only active employees are offered, since a meeting cannot sensibly be assigned to
     * someone who has left. Ordered by name so the dropdown is stable between loads.</p>
     */
    @Transactional(readOnly = true)
    public List<EmployeeDtos.Simple> assignableEngineers() {
        return employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(e -> e.getFullName() == null ? "" : e.getFullName(),
                        String.CASE_INSENSITIVE_ORDER))
                .map(e -> new EmployeeDtos.Simple(e.getId(), e.getEmployeeCode(), e.getFullName(),
                        e.getDepartment() == null ? null : e.getDepartment().getName()))
                .toList();
    }

    // ------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------

    /** Location-filtered holidays, reusing the shared Holidays visibility rules. */
    private List<UpcomingEvent> holidays(LocalDate today, LocalDate lastDay) {
        List<HolidayDtos.Response> visible = holidayRows(today, lastDay);
        List<UpcomingEvent> result = new ArrayList<>();
        for (HolidayDtos.Response h : visible) {
            result.add(toHolidayUpcomingEvent(h, today));
        }
        return result;
    }

    /**
     * Maps a holiday onto the feed's presentation shape.
     *
     * <p>Shared by the feed and by {@link #createHoliday} so a holiday created in the
     * form is returned in exactly the form the feed will later return it, rather
     * than a meeting-shaped approximation.</p>
     */
    private UpcomingEvent toHolidayUpcomingEvent(HolidayDtos.Response h, LocalDate today) {
        return new UpcomingEvent(
                h.id(),
                UpcomingEventSource.HOLIDAY.name(),
                UpcomingEventCategory.HOLIDAY,
                h.name(),
                h.description(),
                h.date(),
                // startTime, endTime, organization, location, meetingLink, setReminder, todoItems
                null,
                null,
                null,
                null,
                null,
                null,
                null,
// employeeName
                    null,
                    h.holidayType() == null ? null : h.holidayType().name(),
                    // holidayCountry
                    h.country(),
                    // assignedEngineerId / Name / Department / Designation
                null,
                null,
                null,
                null,
                daysUntil(today, h.date()));
    }


    /**
     * Holidays in range, scoped to the caller.
     *
     * <p>Reuses {@link HolidayService#listForEmployee} so the Upcoming Events feed applies
     * exactly the same {@code active} + {@code applicableLocations} rules as the Holidays
     * page. A caller with no employee profile has no location, so it falls back to the
     * unfiltered list restricted to active definitions.</p>
     */
    private List<HolidayDtos.Response> holidayRows(LocalDate today, LocalDate lastDay) {
        Long employeeId = currentEmployeeId();
        if (employeeId != null) {
            return holidayService.listForEmployee(employeeId, today, lastDay);
        }
        return holidayService.list(today, lastDay, null, null, null).stream()
                .filter(HolidayDtos.Response::active)
                .toList();
    }

    /** Birthdays computed from employee date of birth. */
    private List<UpcomingEvent> birthdays(LocalDate today, LocalDate lastDay) {
        int window = (int) Math.min(ChronoUnit.DAYS.between(today, lastDay) + 1, MAX_WINDOW_DAYS);
        List<UpcomingEvent> result = new ArrayList<>();
        for (EmployeeService.Birthday b : employeeService.upcomingBirthdays(window)) {
            result.add(new UpcomingEvent(
                    b.employeeId(),
                    UpcomingEventSource.BIRTHDAY.name(),
                    UpcomingEventCategory.BIRTHDAY,
                    b.employeeName(),
                    null,
                    b.date(),
                    // startTime, endTime, organization, location, meetingLink, setReminder, todoItems
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    b.employeeName(),
                    // eventType / holidayCountry
                    null,
                    null,
                    // assignedEngineerId / Name / Department / Designation
                    null,
                    null,
                    null,
                    null,
                    daysUntil(today, b.date())));
        }
        return result;
    }

    /** Meeting / session rows: team-visible plus anything the caller created. */
    private List<UpcomingEvent> storedEvents(LocalDate today, LocalDate lastDay, Long teamId) {
        Long scopedTeamId = teamId != null ? teamId : securityUtils.currentTeamId();
        List<Event> visible = new ArrayList<>(
                eventRepository.findVisibleInRange(today, lastDay, scopedTeamId));
        visible.sort(Comparator.comparing(Event::getEventDate).thenComparing(Event::getId));
        return visible.stream().map(e -> toUpcomingEvent(e, today)).toList();
    }

    // ------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------

    private UpcomingEvent toUpcomingEvent(Event e, LocalDate today) {
        return new UpcomingEvent(
                e.getId(),
                UpcomingEventSource.EVENT.name(),
                toCategory(e.getEventType()),
                e.getTitle(),
                e.getDescription(),
                e.getEventDate(),
                e.getStartTime(),
                e.getEndTime(),
                e.getOrganization(),
                e.getLocation(),
                e.getMeetingLink(),
                e.isSetReminder(),
                jsonUtil.readList(e.getTodoItems(), String.class),
                e.getCreatedBy() != null ? e.getCreatedBy().getEmail() : null,
                e.getEventType() == null ? null : e.getEventType().name(),
                // holidayCountry: never set for a stored event
                null,
                e.getAssignedTo() != null ? e.getAssignedTo().getId() : null,
                e.getAssignedTo() != null ? e.getAssignedTo().getFullName() : null,
                e.getAssignedTo() != null && e.getAssignedTo().getDepartment() != null
                        ? e.getAssignedTo().getDepartment().getName()
                        : null,
                e.getAssignedTo() != null ? e.getAssignedTo().getDesignation() : null,
                daysUntil(today, e.getEventDate()));
    }

    /** Maps a stored event type onto a page category, defaulting to OFFICE_MEETING. */
    private UpcomingEventCategory toCategory(EventType type) {
        if (type == null) {
            return UpcomingEventCategory.OFFICE_MEETING;
        }
        return switch (type) {
            case CUSTOMER_REMOTE_SESSION -> UpcomingEventCategory.CUSTOMER_REMOTE_SESSION;
            case SCHEDULED_MEETING -> UpcomingEventCategory.SCHEDULED_MEETING;
            // COMPANY_EVENT / CONFERENCE / TEAM_MEETING / CUSTOM all surface as office meetings.
            default -> UpcomingEventCategory.OFFICE_MEETING;
        };
    }

    private EventType toEventType(UpcomingEventCategory category) {
        return switch (category) {
            case CUSTOMER_REMOTE_SESSION -> EventType.CUSTOMER_REMOTE_SESSION;
            case SCHEDULED_MEETING -> EventType.SCHEDULED_MEETING;
            case OFFICE_MEETING -> EventType.OFFICE_MEETING;
            // `create` routes HOLIDAY to createHoliday before reaching here. This
            // case exists to keep the switch exhaustive while still failing loudly:
            // the previous `case HOLIDAY, BIRTHDAY -> OFFICE_MEETING` silently
            // turned a requested holiday into an office meeting, which then surfaced
            // in the wrong feed section with no error anywhere. BIRTHDAY is computed
            // from employee date of birth and is likewise not creatable.
            case HOLIDAY, BIRTHDAY -> throw ApiException.badRequest(
                    category + " cannot be stored as a meeting");
        };
    }


    private long daysUntil(LocalDate today, LocalDate date) {
        return ChronoUnit.DAYS.between(today, date);
    }

    private String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Looks up the employee to assign the meeting to.
     *
     * <p>A {@code null} id means "Unassigned" and is legitimate. A non-null id that does
     * not resolve is a client bug rather than a transient state, so it is rejected
     * instead of silently creating an unassigned event.</p>
     */
    private Employee resolveAssignee(Long assignedEngineerId) {
        if (assignedEngineerId == null) {
            return null;
        }
        return employeeRepository.findById(assignedEngineerId)
                .orElseThrow(() -> ApiException.badRequest(
                        "Assigned engineer not found: " + assignedEngineerId));
    }

    /**
     * Employee id of the caller.
     *
     * <p>Holiday visibility is inherently employee-scoped, so a caller without an employee
     * profile (e.g. a login-only admin) has no location to filter against. Rather than
     * failing the whole feed, such callers fall back to unfiltered holidays.</p>
     */
    private Long currentEmployeeId() {
        return employeeService.findEmployeeByUser(securityUtils.currentUserId())
                .map(Employee::getId)
                .orElse(null);
    }

    /** Department of the caller, or null when the account has no employee profile. */
    private Department creatorTeam(Long userId) {
        return employeeService.findEmployeeByUser(userId)
                .map(Employee::getDepartment)
                .orElse(null);
    }

    /** Caps the feed so a wide window cannot return an unbounded payload. */
    private static final int MAX_RESULTS = 500;
}
