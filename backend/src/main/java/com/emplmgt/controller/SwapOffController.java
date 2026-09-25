package com.emplmgt.controller;

import com.emplmgt.dto.SwapOffDtos;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.SwapOffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/swap-offs")
@RequiredArgsConstructor
public class SwapOffController {

    private final SwapOffService swapOffService;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<SwapOffDtos.Response> apply(@Valid @RequestBody SwapOffDtos.ApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(swapOffService.apply(securityUtils.currentUserId(), request));
    }

    @GetMapping
    public ResponseEntity<List<SwapOffDtos.Response>> myRequests() {
        return ResponseEntity.ok(swapOffService.myRequests(securityUtils.currentUserId()));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<SwapOffDtos.Response> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(swapOffService.cancel(securityUtils.currentUserId(), id));
    }
}