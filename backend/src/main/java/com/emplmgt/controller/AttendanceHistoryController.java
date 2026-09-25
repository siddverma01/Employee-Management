package com.emplmgt.controller;

import com.emplmgt.dto.AttendanceHistoryDtos;
import com.emplmgt.service.AttendanceHistoryService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/admin/attendance-history")
@RequiredArgsConstructor
public class AttendanceHistoryController {

    private static final String EXCEL_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AttendanceHistoryService attendanceHistoryService;

    @GetMapping
    public ResponseEntity<AttendanceHistoryDtos.SearchResponse> search(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String employeeName,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String shift,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return ResponseEntity.ok(attendanceHistoryService.search(new AttendanceHistoryDtos.HistoryQuery(
                dateFrom, dateTo, month, year, employeeName, employeeId, teamId,
                location, shift, status, q, page, size, sortBy, sortDir)));
    }

    @GetMapping("/meta")
    public ResponseEntity<AttendanceHistoryDtos.Meta> meta() {
        return ResponseEntity.ok(attendanceHistoryService.meta());
    }

    @GetMapping("/export")
    public void export(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String employeeName,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String shift,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            HttpServletResponse response) throws IOException {
        boolean excel = "xlsx".equalsIgnoreCase(format) || "excel".equalsIgnoreCase(format);
        response.setContentType(excel ? EXCEL_MIME : "text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"attendance-history-" + LocalDateTime.now().format(STAMP)
                        + (excel ? ".xlsx" : ".csv") + "\"");
        attendanceHistoryService.export(response.getOutputStream(), format,
                new AttendanceHistoryDtos.HistoryQuery(
                        dateFrom, dateTo, month, year, employeeName, employeeId, teamId,
                        location, shift, status, q, 0, AttendanceHistoryService.MAX_PAGE_SIZE,
                        "date", "asc"));
    }
}