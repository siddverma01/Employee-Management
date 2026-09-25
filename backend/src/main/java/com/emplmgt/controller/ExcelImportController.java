package com.emplmgt.controller;

import com.emplmgt.dto.ImportDtos;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.ExcelImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/excel")
@RequiredArgsConstructor
public class ExcelImportController {

    private final ExcelImportService excelImportService;
    private final SecurityUtils securityUtils;

    @PostMapping("/upload")
    public ResponseEntity<ImportDtos.UploadResponse> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(excelImportService.upload(file, securityUtils.currentUserId()));
    }

    @PostMapping("/{id}/mapping")
    public ResponseEntity<ImportDtos.PreviewResponse> setMapping(@PathVariable Long id,
                                                                 @RequestBody Map<String, Object> mapping) {
        return ResponseEntity.ok(excelImportService.mapAndPreview(id, mapping));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<ImportDtos.PreviewResponse> preview(@PathVariable Long id) {
        return ResponseEntity.ok(excelImportService.preview(id));
    }

    @PostMapping("/{id}/commit")
    public ResponseEntity<ImportDtos.CommitResponse> commit(@PathVariable Long id) {
        return ResponseEntity.ok(excelImportService.commit(id, securityUtils.currentUserId()));
    }

    @GetMapping("/imports")
    public ResponseEntity<List<ImportDtos.HistoryItem>> history() {
        return ResponseEntity.ok(excelImportService.history());
    }
}