package com.emplmgt.service;

import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.HolidayType;
import com.emplmgt.entity.ScopeType;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.HolidayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HolidayService {

    private final HolidayRepository holidayRepository;
    private final DepartmentRepository departmentRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<HolidayDtos.Response> list(LocalDate from, LocalDate to, String country,
                                           ScopeType scope, Long teamId) {
        LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from;
        LocalDate end = to == null ? LocalDate.of(2100, 12, 31) : to;
        List<Holiday> holidays;
        if (scope == ScopeType.GLOBAL) {
            holidays = holidayRepository.findInRangeScoped(start, end, country, ScopeType.GLOBAL, null);
        } else if (scope == ScopeType.TEAM) {
            holidays = holidayRepository.findInRangeScoped(start, end, country, ScopeType.TEAM, teamId);
        } else if (teamId != null) {
            holidays = holidayRepository.findVisibleInRange(start, end, country, teamId);
        } else {
            holidays = holidayRepository.findInRange(start, end, country);
        }
        return holidays.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<HolidayDtos.Response> upcoming(int limit, Long teamId) {
        LocalDate today = LocalDate.now();
        List<Holiday> holidays = teamId != null
                ? holidayRepository.findVisibleInRange(today, today.plusYears(1), null, teamId)
                : holidayRepository.findByHolidayDateBetweenOrderByHolidayDate(today, today.plusYears(1));
        return holidays.stream().limit(limit).map(this::toResponse).toList();
    }

    @Transactional
    public HolidayDtos.Response create(HolidayDtos.HolidayRequest request) {
        ScopeType scope = resolveScope(request.scope());
        Department team = resolveTeam(scope, request.teamId());
        Holiday holiday = Holiday.builder()
                .name(request.name().trim())
                .holidayDate(request.date())
                .country(request.country() == null || request.country().isBlank() ? "US" : request.country())
                .holidayType(request.holidayType() == null ? HolidayType.PUBLIC : request.holidayType())
                .description(request.description())
                .scope(scope)
                .team(team)
                .build();
        Holiday saved = holidayRepository.save(holiday);
        auditService.record("HOLIDAY_CREATED", "Holiday", String.valueOf(saved.getId()),
                null, Map.of("name", saved.getName(), "date", saved.getHolidayDate().toString(),
                        "country", saved.getCountry(), "scope", scope.name()));
        return toResponse(saved);
    }

    @Transactional
    public HolidayDtos.Response update(Long id, HolidayDtos.HolidayRequest request) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Holiday not found"));
        Map<String, Object> oldVal = Map.of("name", holiday.getName(),
                "date", holiday.getHolidayDate().toString(), "country", holiday.getCountry());
        ScopeType scope = resolveScope(request.scope());
        Department team = resolveTeam(scope, request.teamId());
        holiday.setName(request.name().trim());
        holiday.setHolidayDate(request.date());
        holiday.setCountry(request.country() == null || request.country().isBlank() ? "US" : request.country());
        holiday.setHolidayType(request.holidayType() == null ? HolidayType.PUBLIC : request.holidayType());
        holiday.setDescription(request.description());
        holiday.setScope(scope);
        holiday.setTeam(team);
        Holiday saved = holidayRepository.save(holiday);
        auditService.record("HOLIDAY_UPDATED", "Holiday", String.valueOf(saved.getId()),
                oldVal, Map.of("name", saved.getName(), "date", saved.getHolidayDate().toString(), "country", saved.getCountry()));
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Holiday not found"));
        auditService.record("HOLIDAY_DELETED", "Holiday", String.valueOf(id),
                Map.of("name", holiday.getName(), "date", holiday.getHolidayDate().toString()), null);
        holidayRepository.delete(holiday);
    }

    private ScopeType resolveScope(ScopeType requested) {
        return requested == null ? ScopeType.GLOBAL : requested;
    }

    private Department resolveTeam(ScopeType scope, Long teamId) {
        if (scope != ScopeType.TEAM) {
            return null;
        }
        if (teamId == null) {
            throw ApiException.badRequest("teamId is required for a team-scoped holiday");
        }
        return departmentRepository.findById(teamId)
                .orElseThrow(() -> ApiException.badRequest("Team not found: " + teamId));
    }

    private HolidayDtos.Response toResponse(Holiday h) {
        return new HolidayDtos.Response(h.getId(), h.getName(), h.getHolidayDate(),
                h.getCountry(), h.getHolidayType(), h.getDescription(),
                h.getScope(), h.getTeam() != null ? h.getTeam().getId() : null);
    }
}