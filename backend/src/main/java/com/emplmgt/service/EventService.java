package com.emplmgt.service;

import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.Event;
import com.emplmgt.entity.EventType;
import com.emplmgt.entity.ScopeType;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final DepartmentRepository departmentRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<HolidayDtos.EventResponse> list(LocalDate from, LocalDate to, ScopeType scope, Long teamId) {
        LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from;
        LocalDate end = to == null ? LocalDate.of(2100, 12, 31) : to;
        List<Event> events;
        if (scope == ScopeType.GLOBAL) {
            events = eventRepository.findInRangeScoped(start, end, ScopeType.GLOBAL, null);
        } else if (scope == ScopeType.TEAM) {
            events = eventRepository.findInRangeScoped(start, end, ScopeType.TEAM, teamId);
        } else if (teamId != null) {
            events = eventRepository.findVisibleInRange(start, end, teamId);
        } else {
            events = eventRepository.findByEventDateBetweenOrderByEventDate(start, end);
        }
        return events.stream().map(this::toResponse).toList();
    }

    @Transactional
    public HolidayDtos.EventResponse create(HolidayDtos.EventRequest request) {
        ScopeType scope = resolveScope(request.scope());
        Department team = resolveTeam(scope, request.teamId());
        Event event = Event.builder()
                .title(request.title().trim())
                .description(request.description())
                .eventDate(request.eventDate())
                .eventType(request.eventType() == null ? EventType.COMPANY_EVENT : request.eventType())
                .scope(scope)
                .team(team)
                .build();
        Event saved = eventRepository.save(event);
        auditService.record("EVENT_CREATED", "Event", String.valueOf(saved.getId()),
                null, Map.of("title", saved.getTitle(), "date", saved.getEventDate().toString(), "scope", scope.name()));
        return toResponse(saved);
    }

    @Transactional
    public HolidayDtos.EventResponse update(Long id, HolidayDtos.EventRequest request) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Event not found"));
        Map<String, Object> oldVal = Map.of("title", event.getTitle(), "date", event.getEventDate().toString());
        ScopeType scope = resolveScope(request.scope());
        Department team = resolveTeam(scope, request.teamId());
        event.setTitle(request.title().trim());
        event.setDescription(request.description());
        event.setEventDate(request.eventDate());
        event.setEventType(request.eventType() == null ? EventType.COMPANY_EVENT : request.eventType());
        event.setScope(scope);
        event.setTeam(team);
        Event saved = eventRepository.save(event);
        auditService.record("EVENT_UPDATED", "Event", String.valueOf(saved.getId()),
                oldVal, Map.of("title", saved.getTitle(), "date", saved.getEventDate().toString()));
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Event not found"));
        auditService.record("EVENT_DELETED", "Event", String.valueOf(id),
                Map.of("title", event.getTitle()), null);
        eventRepository.delete(event);
    }

    private ScopeType resolveScope(ScopeType requested) {
        return requested == null ? ScopeType.GLOBAL : requested;
    }

    private Department resolveTeam(ScopeType scope, Long teamId) {
        if (scope != ScopeType.TEAM) {
            return null;
        }
        if (teamId == null) {
            throw ApiException.badRequest("teamId is required for a team-scoped event");
        }
        return departmentRepository.findById(teamId)
                .orElseThrow(() -> ApiException.badRequest("Team not found: " + teamId));
    }

    private HolidayDtos.EventResponse toResponse(Event e) {
        return new HolidayDtos.EventResponse(e.getId(), e.getTitle(), e.getDescription(), e.getEventDate(),
                e.getEventType(), e.getScope(), e.getTeam() != null ? e.getTeam().getId() : null);
    }
}