package com.emplmgt.controller;

import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.ScopeType;
import com.emplmgt.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public ResponseEntity<List<HolidayDtos.EventResponse>> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) ScopeType scope,
            @RequestParam(required = false) Long teamId) {
        return ResponseEntity.ok(eventService.list(from, to, scope, teamId));
    }
}