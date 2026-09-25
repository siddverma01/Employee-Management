package com.emplmgt.controller;

import com.emplmgt.dto.CalendarDtos;
import com.emplmgt.service.CalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;

    @GetMapping
    public ResponseEntity<List<CalendarDtos.CalendarEvent>> month(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(defaultValue = "1") int month,
            @RequestParam(required = false) Long teamId) {
        YearMonth ym = YearMonth.of(year, month);
        return ResponseEntity.ok(calendarService.month(ym.getYear(), ym.getMonthValue(), teamId));
    }
}