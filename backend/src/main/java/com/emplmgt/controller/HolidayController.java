package com.emplmgt.controller;

import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.ScopeType;
import com.emplmgt.service.HolidayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/holidays")
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService holidayService;

    @GetMapping
    public ResponseEntity<List<HolidayDtos.Response>> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) ScopeType scope,
            @RequestParam(required = false) Long teamId) {
        return ResponseEntity.ok(holidayService.list(from, to, country, scope, teamId));
    }

    @GetMapping("/upcoming")
    public ResponseEntity<List<HolidayDtos.Response>> upcoming(@RequestParam(defaultValue = "10") int limit,
                                                               @RequestParam(required = false) Long teamId) {
        return ResponseEntity.ok(holidayService.upcoming(limit, teamId));
    }
}