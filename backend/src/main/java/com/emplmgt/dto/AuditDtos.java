package com.emplmgt.dto;

import java.time.Instant;
import java.util.Map;

public final class AuditDtos {

    private AuditDtos() {
    }

    public record Response(
            Long id,
            String userEmail,
            String action,
            String entityType,
            String entityId,
            Map<String, Object> oldValue,
            Map<String, Object> newValue,
            String ipAddress,
            Instant createdAt) {
    }
}