package com.emplmgt.service;

import com.emplmgt.entity.AuditLog;
import com.emplmgt.entity.User;
import com.emplmgt.repository.AuditLogRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final SecurityUtils securityUtils;
    private final JsonUtil jsonUtil;

    public void record(String action, String entityType, String entityId,
                       Map<String, Object> oldValue, Map<String, Object> newValue) {
        record(action, entityType, entityId, oldValue, newValue, null);
    }

    public void record(String action, String entityType, String entityId,
                       Map<String, Object> oldValue, Map<String, Object> newValue,
                       HttpServletRequest request) {
        try {
            Long userId = securityUtils.currentUserId();
            User user = userId != null ? userRepository.findById(userId).orElse(null) : null;
            var logEntry = AuditLog.builder()
                    .user(user)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .oldValue(jsonUtil.write(oldValue))
                    .newValue(jsonUtil.write(newValue))
                    .ipAddress(request != null ? clientIp(request) : null)
                    .build();
            auditLogRepository.save(logEntry);
        } catch (Exception e) {
            // Audit must never break the primary business operation.
            log.warn("Failed to write audit log for {}: {}", action, e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}