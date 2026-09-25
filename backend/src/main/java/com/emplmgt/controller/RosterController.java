package com.emplmgt.controller;

import com.emplmgt.dto.RosterDtos;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.RosterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/roster")
@RequiredArgsConstructor
public class RosterController {

    private final RosterService rosterService;
    private final SecurityUtils securityUtils;

    @PostMapping("/import-preview")
    public ResponseEntity<RosterDtos.ImportPreviewResponse> importPreview(
            @RequestParam("teamId") Long teamId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(rosterService.preview(teamId, file, securityUtils.currentUserId()));
    }

    @PostMapping("/save")
    public ResponseEntity<RosterDtos.SaveResponse> save(@Valid @RequestBody RosterDtos.SaveRequest request) {
        return ResponseEntity.ok(rosterService.save(request, securityUtils.currentUserId()));
    }

    @GetMapping
    public ResponseEntity<RosterDtos.MonthResponse> get(
            @RequestParam(value = "teamId", required = false) Long teamId,
            @RequestParam("month") String month) {
        return ResponseEntity.ok(rosterService.get(teamId, month));
    }

    @GetMapping("/months")
    public ResponseEntity<List<String>> months(@RequestParam(value = "teamId", required = false) Long teamId) {
        return ResponseEntity.ok(rosterService.months(teamId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRow(@PathVariable Long id) {
        rosterService.deleteRow(id);
        return ResponseEntity.noContent().build();
    }
}