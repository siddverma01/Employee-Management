package com.emplmgt.controller;

import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.service.HolidayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/holidays")
@RequiredArgsConstructor
public class AdminHolidayController {

    private final HolidayService holidayService;

    @PostMapping
    public ResponseEntity<HolidayDtos.Response> create(@Valid @RequestBody HolidayDtos.HolidayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holidayService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HolidayDtos.Response> update(@PathVariable Long id,
                                                       @Valid @RequestBody HolidayDtos.HolidayRequest request) {
        return ResponseEntity.ok(holidayService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        holidayService.delete(id);
        return ResponseEntity.noContent().build();
    }
}