package com.emplmgt.controller;

import com.emplmgt.dto.HistoricalImportDtos;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.HistoricalImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/historical")
@RequiredArgsConstructor
public class HistoricalImportController {

    private final HistoricalImportService historicalImportService;
    private final SecurityUtils securityUtils;

    @PostMapping("/inspect")
    public ResponseEntity<HistoricalImportDtos.InspectResponse> inspect(
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(historicalImportService.inspect(file));
    }

    @PostMapping("/preview")
    public ResponseEntity<HistoricalImportDtos.PreviewResponse> preview(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(historicalImportService.preview(file, securityUtils.currentUserId()));
    }

    @GetMapping("/imports/{id}/preview")
    public ResponseEntity<HistoricalImportDtos.PreviewResponse> previewOf(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(historicalImportService.previewOf(id, page, size));
    }

    @PostMapping("/imports/{id}/map")
    public ResponseEntity<HistoricalImportDtos.MapStagedResponse> mapStaged(
            @PathVariable Long id,
            @RequestBody HistoricalImportDtos.MapStagedRequest request) {
        return ResponseEntity.ok(historicalImportService.mapStaged(id, request));
    }

    @PostMapping("/imports/{id}/commit")
    public ResponseEntity<HistoricalImportDtos.CommitResponse> commit(
            @PathVariable Long id,
            @RequestParam(required = false) Long teamId) {
        return ResponseEntity.ok(historicalImportService.commit(id, teamId, securityUtils.currentUserId()));
    }

    @GetMapping("/imports/{id}/report")
    public ResponseEntity<byte[]> report(@PathVariable Long id) {
        String csv = historicalImportService.errorReport(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"historical-import-" + id + "-report.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/imports")
    public ResponseEntity<List<HistoricalImportDtos.HistoryItem>> history() {
        return ResponseEntity.ok(historicalImportService.history());
    }

    @GetMapping("/statuses")
    public ResponseEntity<List<HistoricalImportDtos.StatusItem>> statuses() {
        return ResponseEntity.ok(historicalImportService.statuses());
    }

    @GetMapping("/unknown-codes")
    public ResponseEntity<List<HistoricalImportDtos.UnknownCodeItem>> unknownCodes() {
        return ResponseEntity.ok(historicalImportService.unknownCodes());
    }

    @PostMapping("/unknown-codes/map")
    public ResponseEntity<HistoricalImportDtos.MapUnknownResponse> mapUnknown(
            @RequestBody HistoricalImportDtos.MapUnknownRequest request) {
        return ResponseEntity.ok(historicalImportService.mapUnknown(request));
    }

    @GetMapping("/records")
    public ResponseEntity<HistoricalImportDtos.RecordsPage> records(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String employeeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(historicalImportService.records(from, to, employeeId, page, size));
    }
}